package com.github.ezzziy.codesandbox.pool;

import com.github.ezzziy.codesandbox.executor.ContainerManager;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 容器池管理器 - 热容器机制
 * <p>
 * 维护一组长驻的 Docker 容器，避免每次评测都创建/销毁容器，大幅提升性能
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerPool {

    private final ContainerManager containerManager;
    private final List<LanguageStrategy> languageStrategies;

    /**
     * 容器池：按语言分组管理
     * Key: 语言名称（如 "java8", "python3"）
    * Value: 该语言的容器池状态
     */
    private final Map<String, PoolState> containerPool = new ConcurrentHashMap<>();

    @Value("${sandbox.pool.min-size:2}")
    private int minPoolSize;

    @Value("${sandbox.pool.max-size:10}")
    private int maxPoolSize;

    @Value("${sandbox.pool.max-idle-minutes:10}")
    private int maxIdleMinutes;

    @Value("${sandbox.pool.max-use-count:100}")
    private int maxUseCount;

    private static final int MIN_POOL_SIZE_FLOOR = 1;
    private static final int ACQUIRE_WAIT_SECONDS = 30;
    private static final int ZOMBIE_CHECK_DELAY_MILLIS = 10 * 60 * 1000;

    private static class PoolState {
        private final ConcurrentLinkedQueue<PooledContainer> containers = new ConcurrentLinkedQueue<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition available = lock.newCondition();
    }

    /**
     * 初始化容器池
     */
    @PostConstruct
    public void init() {
        if (minPoolSize < MIN_POOL_SIZE_FLOOR) {
            log.warn("minPoolSize 小于 1，已调整为 {}", MIN_POOL_SIZE_FLOOR);
            minPoolSize = MIN_POOL_SIZE_FLOOR;
        }
        log.info("初始化容器池: minSize={}, maxSize={}, maxIdleMinutes={}, maxUseCount={}",
                minPoolSize, maxPoolSize, maxIdleMinutes, maxUseCount);

        // 为每种语言预热最小数量的容器
        for (LanguageStrategy strategy : languageStrategies) {
            String language = strategy.getLanguage().name();
            PoolState state = new PoolState();
            containerPool.put(language, state);

            // 预热容器
            for (int i = 0; i < minPoolSize; i++) {
                try {
                    createAndAddContainer(strategy, state);
                } catch (Exception e) {
                    log.error("预热容器失败: language={}", language, e);
                }
            }
            log.info("容器池预热完成: language={}, initialSize={}", language, minPoolSize);
        }
    }

    /**
     * 从池中获取容器（如果没有可用容器，则创建新容器）
     */
    public PooledContainer acquireContainer(LanguageStrategy strategy) {
        String language = strategy.getLanguage().name();
        PoolState state = getOrCreatePoolState(language);
        LocalDateTime now = LocalDateTime.now();

        state.lock.lock();
        try {
            // 1. 优先获取空闲且存活的容器
            PooledContainer container = findAvailableContainerLocked(state, now);
            if (container != null) {
                log.debug("从池中获取容器: language={}, containerId={}, useCount={}",
                        language, container.getContainerId(), container.getUseCount());
                return container;
            }

            // 2. 没有空闲容器，检查是否可以创建新容器
            if (state.containers.size() < maxPoolSize) {
                try {
                    PooledContainer newContainer = createAndAddContainer(strategy, state);
                    newContainer.setInUse(true);
                    newContainer.setLastUsedTime(now);
                    newContainer.setUseCount(newContainer.getUseCount() + 1);
                    log.info("创建新容器: language={}, containerId={}, poolSize={}",
                            language, newContainer.getContainerId(), state.containers.size());
                    return newContainer;
                } catch (Exception e) {
                    log.error("创建新容器失败: language={}", language, e);
                    throw new RuntimeException("无法创建容器", e);
                }
            }

            // 3. 池已满，等待空闲容器
            log.warn("容器池已满，等待空闲容器: language={}, poolSize={}", language, state.containers.size());
            long nanos = TimeUnit.SECONDS.toNanos(ACQUIRE_WAIT_SECONDS);
            while (nanos > 0) {
                try {
                    nanos = state.available.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待容器被中断", e);
                }

                container = findAvailableContainerLocked(state, LocalDateTime.now());
                if (container != null) {
                    log.debug("等待后获取容器: language={}, containerId={}",
                            language, container.getContainerId());
                    return container;
                }
            }

            throw new RuntimeException("等待容器超时，所有容器都在使用中");
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * 归还容器到池中
     * <p>
     * 由于采用任务子目录隔离模型，任务目录的清理由 DockerCodeExecutor 在归还前完成，
     * 这里只需检查使用次数并标记容器为空闲状态
     */
    public void releaseContainer(PooledContainer container) {
        if (container == null) {
            return;
        }

        String language = container.getStrategy().getLanguage().name();
        PoolState state = getOrCreatePoolState(language);

        state.lock.lock();
        try {
            if (!containerManager.isContainerRunning(container.getContainerId())) {
                removeDeadContainerLocked(state, container, "release");
                ensureAtLeastOneContainerLocked(state, container.getStrategy());
                return;
            }

            // 检查容器是否需要重建（使用次数过多）
            if (container.getUseCount() >= maxUseCount) {
                log.info("容器使用次数达到上限，重建容器: language={}, containerId={}, useCount={}",
                        language, container.getContainerId(), container.getUseCount());
                removeAndRecreateContainerLocked(state, container);
                return;
            }

            // 任务子目录已由调用方清理，这里无需额外清理操作

            // 标记为空闲
            container.setInUse(false);
            container.setLastUsedTime(LocalDateTime.now());
            state.available.signal();
            log.debug("归还容器到池: language={}, containerId={}, useCount={}",
                    language, container.getContainerId(), container.getUseCount());
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * 创建容器并添加到池中
     * <p>
     * 容器生命周期：create → start → verify running → ready
     * 沙箱镜像已内置 sandbox 用户和 /sandbox/workspace 目录，无需运行时初始化
     */
    private PooledContainer createAndAddContainer(LanguageStrategy strategy, PoolState state) {
        String language = strategy.getLanguage().name();
        String image = strategy.getDockerImage();
        
        log.info("开始创建容器: language={}, image={}", language, image);
        
        // 容器池模式：workDir 传 null，不挂载目录，直接使用容器内部文件系统
        String containerId = containerManager.createContainer(
                strategy,
                "pool-" + System.nanoTime(),
                null  // 不挂载目录
        );
        
        log.info("容器创建完成，开始启动: containerId={}, language={}", 
                containerId.substring(0, 12), language);
        
        // 启动容器 - 必须先启动再纳入池中，确保容器处于 running 状态
        containerManager.startContainer(containerId);
        
        // 等待容器完全启动并验证状态（最多重试 5 次，每次间隔 200ms）
        boolean running = false;
        for (int retry = 0; retry < 5; retry++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            if (containerManager.isContainerRunning(containerId)) {
                running = true;
                break;
            }
            log.debug("等待容器启动，重试 {}/5: containerId={}", retry + 1, containerId.substring(0, 12));
        }
        
        // 验证容器状态 - 确保容器已完全启动且保持运行
        if (!running) {
            log.error("容器启动失败，状态异常（可能镜像未包含常驻进程或安全配置问题）: containerId={}, image={}", 
                    containerId.substring(0, 12), image);
            containerManager.removeContainer(containerId);
            throw new RuntimeException("容器启动后状态异常，请确认: 1) 镜像已重新构建 2) 镜像包含 CMD [\"sleep\", \"infinity\"]");
        }
        
        log.info("容器创建并启动成功，状态 running: containerId={}, image={}", 
                containerId.substring(0, 12), image);

        PooledContainer pooledContainer = new PooledContainer(
                containerId,
                strategy,
                false,
                LocalDateTime.now(),
                LocalDateTime.now(),
                0
        );
        
        state.containers.add(pooledContainer);

        return pooledContainer;
    }

    /**
     * 移除并重建容器
     */
    private void removeAndRecreateContainerLocked(PoolState state, PooledContainer oldContainer) {
        String language = oldContainer.getStrategy().getLanguage().name();

        try {
            // 移除旧容器
            containerManager.removeContainer(oldContainer.getContainerId());
            state.containers.remove(oldContainer);

            // 创建新容器
            createAndAddContainer(oldContainer.getStrategy(), state);
        } catch (Exception e) {
            log.error("重建容器失败: language={}, oldContainerId={}",
                    language, oldContainer.getContainerId(), e);
        }
    }

    /**
     * 定时清理空闲超时的容器
     */
    @Scheduled(fixedDelay = 60000) // 每分钟执行一次
    public void cleanIdleContainers() {
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<String, PoolState> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            PoolState state = entry.getValue();

            state.lock.lock();
            try {
                int minKeep = Math.max(minPoolSize, MIN_POOL_SIZE_FLOOR);
                int totalCount = state.containers.size();
                if (totalCount <= minKeep) {
                    continue;
                }

                for (PooledContainer container : state.containers) {
                    if (state.containers.size() <= minKeep) {
                        break;
                    }

                    if (!container.isInUse()) {
                        long idleMinutes = ChronoUnit.MINUTES.between(container.getLastUsedTime(), now);
                        if (idleMinutes >= maxIdleMinutes) {
                            log.info("清理空闲超时容器: language={}, containerId={}, idleMinutes={}",
                                    language, container.getContainerId(), idleMinutes);
                            try {
                                containerManager.removeContainer(container.getContainerId());
                                state.containers.remove(container);
                            } catch (Exception e) {
                                log.error("清理容器失败: containerId={}", container.getContainerId(), e);
                            }
                        }
                    }
                }
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * 定时检查僵尸容器并修复池
     */
    @Scheduled(fixedDelay = ZOMBIE_CHECK_DELAY_MILLIS)
    public void cleanZombieContainers() {
        for (Map.Entry<String, PoolState> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            PoolState state = entry.getValue();

            state.lock.lock();
            try {
                for (PooledContainer container : state.containers) {
                    if (!containerManager.isContainerRunning(container.getContainerId())) {
                        removeDeadContainerLocked(state, container, "scheduled");
                    }
                }

                if (state.containers.isEmpty()) {
                    LanguageStrategy strategy = findStrategyByLanguage(language);
                    if (strategy != null) {
                        log.warn("所有容器均已失效，重建一个容器: language={}", language);
                        createAndAddContainer(strategy, state);
                    }
                }
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * 销毁容器池
     */
    @PreDestroy
    public void destroy() {
        log.info("销毁容器池");
        for (Map.Entry<String, PoolState> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            PoolState state = entry.getValue();

            state.lock.lock();
            try {
                for (PooledContainer container : state.containers) {
                    try {
                        containerManager.removeContainer(container.getContainerId());
                        log.debug("销毁容器: language={}, containerId={}", language, container.getContainerId());
                    } catch (Exception e) {
                        log.error("销毁容器失败: containerId={}", container.getContainerId(), e);
                    }
                }
                state.containers.clear();
            } finally {
                state.lock.unlock();
            }
        }
        containerPool.clear();
    }

    /**
     * 获取容器池统计信息
     */
    public Map<String, Map<String, Object>> getPoolStats() {
        Map<String, Map<String, Object>> stats = new ConcurrentHashMap<>();

        for (Map.Entry<String, PoolState> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            PoolState state = entry.getValue();

            state.lock.lock();
            try {
                long inUseCount = state.containers.stream().filter(PooledContainer::isInUse).count();
                long idleCount = state.containers.stream().filter(c -> !c.isInUse()).count();

                stats.put(language, Map.of(
                        "total", state.containers.size(),
                        "inUse", inUseCount,
                        "idle", idleCount
                ));
            } finally {
                state.lock.unlock();
            }
        }

        return stats;
    }

    private PoolState getOrCreatePoolState(String language) {
        return containerPool.computeIfAbsent(language, key -> new PoolState());
    }

    private PooledContainer findAvailableContainerLocked(PoolState state, LocalDateTime now) {
        for (PooledContainer container : state.containers) {
            if (container.isInUse()) {
                continue;
            }

            if (!containerManager.isContainerRunning(container.getContainerId())) {
                removeDeadContainerLocked(state, container, "acquire");
                continue;
            }

            container.setInUse(true);
            container.setLastUsedTime(now);
            container.setUseCount(container.getUseCount() + 1);
            return container;
        }
        return null;
    }

    private void removeDeadContainerLocked(PoolState state, PooledContainer container, String reason) {
        log.warn("清理僵尸容器: containerId={}, reason={}", container.getContainerId(), reason);
        state.containers.remove(container);
        containerManager.removeContainer(container.getContainerId());
    }

    private void ensureAtLeastOneContainerLocked(PoolState state, LanguageStrategy strategy) {
        if (state.containers.isEmpty()) {
            createAndAddContainer(strategy, state);
        }
    }

    private LanguageStrategy findStrategyByLanguage(String language) {
        for (LanguageStrategy strategy : languageStrategies) {
            if (strategy.getLanguage().name().equals(language)) {
                return strategy;
            }
        }
        return null;
    }
}
