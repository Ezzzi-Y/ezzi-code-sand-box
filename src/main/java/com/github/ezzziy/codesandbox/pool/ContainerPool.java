package com.github.ezzziy.codesandbox.pool;

import com.github.dockerjava.api.DockerClient;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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

    private final DockerClient dockerClient;
    private final ContainerManager containerManager;
    private final List<LanguageStrategy> languageStrategies;

    /**
     * 容器池：按语言分组管理
     * Key: 语言名称（如 "java8", "python3"）
     * Value: 该语言的容器列表
     */
    private final Map<String, CopyOnWriteArrayList<PooledContainer>> containerPool = new ConcurrentHashMap<>();

    @Value("${sandbox.pool.min-size:2}")
    private int minPoolSize;

    @Value("${sandbox.pool.max-size:10}")
    private int maxPoolSize;

    @Value("${sandbox.pool.max-idle-minutes:10}")
    private int maxIdleMinutes;

    @Value("${sandbox.pool.max-use-count:100}")
    private int maxUseCount;

    /**
     * 初始化容器池
     */
    @PostConstruct
    public void init() {
        log.info("初始化容器池: minSize={}, maxSize={}, maxIdleMinutes={}, maxUseCount={}",
                minPoolSize, maxPoolSize, maxIdleMinutes, maxUseCount);

        // 为每种语言预热最小数量的容器
        for (LanguageStrategy strategy : languageStrategies) {
            String language = strategy.getLanguage().name();
            containerPool.put(language, new CopyOnWriteArrayList<>());

            // 预热容器
            for (int i = 0; i < minPoolSize; i++) {
                try {
                    createAndAddContainer(strategy);
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
        CopyOnWriteArrayList<PooledContainer> containers = containerPool.get(language);

        if (containers == null) {
            log.warn("未找到语言对应的容器池: {}", language);
            containers = new CopyOnWriteArrayList<>();
            containerPool.put(language, containers);
        }

        // 1. 尝试获取空闲容器
        for (PooledContainer container : containers) {
            if (!container.isInUse()) {
                synchronized (container) {
                    if (!container.isInUse()) {
                        container.setInUse(true);
                        container.setLastUsedTime(LocalDateTime.now());
                        container.setUseCount(container.getUseCount() + 1);
                        log.debug("从池中获取容器: language={}, containerId={}, useCount={}",
                                language, container.getContainerId(), container.getUseCount());
                        return container;
                    }
                }
            }
        }

        // 2. 没有空闲容器，检查是否可以创建新容器
        if (containers.size() < maxPoolSize) {
            try {
                PooledContainer newContainer = createAndAddContainer(strategy);
                newContainer.setInUse(true);
                log.info("创建新容器: language={}, containerId={}, poolSize={}",
                        language, newContainer.getContainerId(), containers.size());
                return newContainer;
            } catch (Exception e) {
                log.error("创建新容器失败: language={}", language, e);
                throw new RuntimeException("无法创建容器", e);
            }
        }

        // 3. 池已满，等待空闲容器
        log.warn("容器池已满，等待空闲容器: language={}, poolSize={}", language, containers.size());
        for (int i = 0; i < 30; i++) { // 最多等待 30 秒
            try {
                TimeUnit.SECONDS.sleep(1);
                for (PooledContainer container : containers) {
                    if (!container.isInUse()) {
                        synchronized (container) {
                            if (!container.isInUse()) {
                                container.setInUse(true);
                                container.setLastUsedTime(LocalDateTime.now());
                                container.setUseCount(container.getUseCount() + 1);
                                log.debug("等待后获取容器: language={}, containerId={}",
                                        language, container.getContainerId());
                                return container;
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待容器被中断", e);
            }
        }

        throw new RuntimeException("等待容器超时，所有容器都在使用中");
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

        // 检查容器是否需要重建（使用次数过多）
        if (container.getUseCount() >= maxUseCount) {
            log.info("容器使用次数达到上限，重建容器: language={}, containerId={}, useCount={}",
                    language, container.getContainerId(), container.getUseCount());
            removeAndRecreateContainer(container);
            return;
        }

        // 任务子目录已由调用方清理，这里无需额外清理操作

        // 标记为空闲
        synchronized (container) {
            container.setInUse(false);
            container.setLastUsedTime(LocalDateTime.now());
        }
        log.debug("归还容器到池: language={}, containerId={}, useCount={}",
                language, container.getContainerId(), container.getUseCount());
    }

    /**
     * 创建容器并添加到池中
     * <p>
     * 容器生命周期：create → start → verify running → ready
     * 沙箱镜像已内置 sandbox 用户和 /sandbox/workspace 目录，无需运行时初始化
     */
    private PooledContainer createAndAddContainer(LanguageStrategy strategy) {
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
        
        containerPool.get(language).add(pooledContainer);

        return pooledContainer;
    }

    /**
     * 移除并重建容器
     */
    private void removeAndRecreateContainer(PooledContainer oldContainer) {
        String language = oldContainer.getStrategy().getLanguage().name();
        CopyOnWriteArrayList<PooledContainer> containers = containerPool.get(language);

        try {
            // 移除旧容器
            containerManager.removeContainer(oldContainer.getContainerId());
            containers.remove(oldContainer);

            // 创建新容器
            createAndAddContainer(oldContainer.getStrategy());
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

        for (Map.Entry<String, CopyOnWriteArrayList<PooledContainer>> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            CopyOnWriteArrayList<PooledContainer> containers = entry.getValue();

            // 保留最小数量的容器
            int idleCount = (int) containers.stream().filter(c -> !c.isInUse()).count();
            int canRemoveCount = idleCount - minPoolSize;

            if (canRemoveCount <= 0) {
                continue;
            }

            for (PooledContainer container : containers) {
                if (canRemoveCount <= 0) {
                    break;
                }

                if (!container.isInUse()) {
                    long idleMinutes = ChronoUnit.MINUTES.between(container.getLastUsedTime(), now);
                    if (idleMinutes >= maxIdleMinutes) {
                        log.info("清理空闲超时容器: language={}, containerId={}, idleMinutes={}",
                                language, container.getContainerId(), idleMinutes);
                        try {
                            containerManager.removeContainer(container.getContainerId());
                            containers.remove(container);
                            canRemoveCount--;
                        } catch (Exception e) {
                            log.error("清理容器失败: containerId={}", container.getContainerId(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 销毁容器池
     */
    @PreDestroy
    public void destroy() {
        log.info("销毁容器池");
        for (Map.Entry<String, CopyOnWriteArrayList<PooledContainer>> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            CopyOnWriteArrayList<PooledContainer> containers = entry.getValue();

            for (PooledContainer container : containers) {
                try {
                    containerManager.removeContainer(container.getContainerId());
                    log.debug("销毁容器: language={}, containerId={}", language, container.getContainerId());
                } catch (Exception e) {
                    log.error("销毁容器失败: containerId={}", container.getContainerId(), e);
                }
            }
            containers.clear();
        }
        containerPool.clear();
    }

    /**
     * 获取容器池统计信息
     */
    public Map<String, Map<String, Object>> getPoolStats() {
        Map<String, Map<String, Object>> stats = new ConcurrentHashMap<>();

        for (Map.Entry<String, CopyOnWriteArrayList<PooledContainer>> entry : containerPool.entrySet()) {
            String language = entry.getKey();
            CopyOnWriteArrayList<PooledContainer> containers = entry.getValue();

            long inUseCount = containers.stream().filter(PooledContainer::isInUse).count();
            long idleCount = containers.stream().filter(c -> !c.isInUse()).count();

            stats.put(language, Map.of(
                    "total", containers.size(),
                    "inUse", inUseCount,
                    "idle", idleCount
            ));
        }

        return stats;
    }
}
