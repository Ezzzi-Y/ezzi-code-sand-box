# 代码执行沙箱服务 - Docker 执行器核心实现

## 1. 概述

Docker 执行器是代码沙箱的核心组件，负责：
- 通过容器池管理预热的 Docker 容器
- 在容器中编译和执行用户代码
- 使用 shell 脚本采集精确执行时间和内存用量
- 危险代码扫描（集成在语言策略中）
- 清理任务目录，容器复用

---

## 2. Docker 配置

### 2.1 DockerConfig

```java
@Slf4j
@Configuration
public class DockerConfig {

    @Value("${sandbox.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${sandbox.docker.connect-timeout:30}")
    private int connectTimeout;       // 秒

    @Value("${sandbox.docker.response-timeout:60}")
    private int responseTimeout;      // 秒

    @Value("${sandbox.docker.max-connections:100}")
    private int maxConnections;

    @Bean
    public DockerClientConfig dockerClientConfig() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .withDockerTlsVerify(false)
                .build();
    }

    @Bean
    public DockerHttpClient dockerHttpClient(DockerClientConfig config) {
        return new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(connectTimeout))
                .responseTimeout(Duration.ofSeconds(responseTimeout))
                .maxConnections(maxConnections)
                .build();
    }

    @Bean
    public DockerClient dockerClient(DockerClientConfig config, DockerHttpClient httpClient) {
        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        // 启动时验证 Docker 连接
        var info = client.infoCmd().exec();
        log.info("Docker 连接成功, Server Version: {}", info.getServerVersion());
        return client;
    }
}
```

> 注意：使用 `ZerodepDockerHttpClient`（zerodep transport），内置 Unix socket 支持，无需 Apache HttpClient5。

### 2.2 执行限制配置

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.execution")
public class ExecutionConfig {

    private int compileTimeout = 30;           // 编译超时（秒）
    private int runTimeout = 10;               // 运行超时（秒）
    private int totalTimeout = 300;            // 总超时（秒）
    private int memoryLimit = 256;             // 内存限制（MB）
    private double cpuLimit = 1.0;             // CPU 限制
    private int outputLimit = 65536;           // 最大输出（字节）
    private int maxProcesses = 1024;           // 最大进程数
    private int maxOpenFiles = 256;            // 最大打开文件数
    private int maxTestCases = 100;            // 最大测试用例数
    private String workDir = "/tmp/sandbox";   // 宿主机工作目录
    private boolean enableCodeScan = true;     // 是否启用危险代码扫描
    private int maxConcurrentContainers = 10;  // 最大并发容器数
}
```

---

## 3. 容器管理器

### 3.1 ContainerManager

`ContainerManager` 是一个具体类（非接口），负责 Docker 容器的全生命周期管理。

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerManager {

    private final DockerClient dockerClient;
    private final ExecutionConfig executionConfig;

    /**
     * 创建容器
     */
    public String createContainer(LanguageStrategy strategy, String requestId, String workDir);

    /**
     * 启动容器
     */
    public void startContainer(String containerId);

    /**
     * 停止容器
     */
    public void stopContainer(String containerId);

    /**
     * 删除容器（stop + force remove）
     */
    public void removeContainer(String containerId);

    /**
     * 获取任务工作目录路径
     */
    public String getTaskDirectory(String jobId);

    /**
     * 清理任务目录（在容器内执行 rm -rf）
     */
    public void cleanupTaskDirectory(String containerId, String taskDir);

    /**
     * 强制终止容器
     */
    public void killContainer(String containerId);

    /**
     * 清理容器工作目录内所有文件
     */
    public void cleanContainer(String containerId);

    /**
     * 检查容器是否正在运行
     */
    public boolean isContainerRunning(String containerId);

    /**
     * 获取活跃容器数
     */
    public int getActiveContainerCount();
}
```

### 3.2 容器创建参数

```java
private HostConfig buildHostConfig(String workDir) {
    long memoryBytes = executionConfig.getMemoryLimit() * 1024L * 1024L;
    long cpuPeriod = 100000L;
    long cpuQuota = (long) (cpuPeriod * executionConfig.getCpuLimit());

    HostConfig hostConfig = HostConfig.newHostConfig()
            .withMemory(memoryBytes)
            .withMemorySwap(memoryBytes)        // 禁用 swap
            .withOomKillDisable(false)
            .withCpuPeriod(cpuPeriod)
            .withCpuQuota(cpuQuota)
            .withPidsLimit((long) executionConfig.getMaxProcesses())
            .withReadonlyRootfs(true)
            .withCapDrop(Capability.ALL)
            .withSecurityOpts(List.of("no-new-privileges:true"))
            .withUlimits(List.of(
                    new Ulimit("nofile", maxOpenFiles, maxOpenFiles),
                    new Ulimit("nproc", maxProcesses, maxProcesses),
                    new Ulimit("fsize", outputLimit, outputLimit)
            ))
            .withTmpFs(Map.of(
                    "/tmp", "rw,noexec,nosuid,size=64m",
                    "/sandbox/workspace", "rw,exec,nosuid,size=64m"
            ));

    // 挂载卷
    if (workDir != null) {
        hostConfig.withBinds(
                new Bind(workDir, new Volume("/sandbox/workspace")),
                new Bind("/var/lib/sandbox-inputs", new Volume("/sandbox/inputs"), AccessMode.ro)
        );
    } else {
        hostConfig.withBinds(
                new Bind("/var/lib/sandbox-inputs", new Volume("/sandbox/inputs"), AccessMode.ro)
        );
    }
    return hostConfig;
}
```

容器创建命令：

```java
dockerClient.createContainerCmd(image)
        .withName(containerName)
        .withHostConfig(hostConfig)
        .withNetworkDisabled(true)
        .withWorkingDir("/sandbox/workspace")
        .withEnv(envList)                    // LANG=C.UTF-8, LC_ALL=C.UTF-8, ...
        .withCmd("tail", "-f", "/dev/null")  // 保持容器运行
        .withStdinOpen(true)
        .withTty(false)
        .exec();
```

---

## 4. 容器池

### 4.1 ContainerPool

容器池为每种语言维护一组预热的容器，避免每次执行都创建/销毁容器。

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ContainerPool {

    @Value("${sandbox.pool.min-size:2}")
    private int minPoolSize;         // 每语言最小池大小

    @Value("${sandbox.pool.max-size:10}")
    private int maxPoolSize;         // 每语言最大池大小

    @Value("${sandbox.pool.max-idle-minutes:10}")
    private int maxIdleMinutes;      // 空闲超时

    @Value("${sandbox.pool.max-use-count:100}")
    private int maxUseCount;         // 单容器最大使用次数

    public PooledContainer acquireContainer(LanguageStrategy strategy);
    public void releaseContainer(PooledContainer container);

    @Scheduled(fixedDelay = 60000)
    public void cleanIdleContainers();       // 清理空闲超时容器

    @Scheduled(fixedDelay = 600000)
    public void cleanZombieContainers();     // 清理僵尸容器

    @PreDestroy
    public void destroy();                   // 销毁所有容器
}
```

容器生命周期：

```
create → start → [ready in pool] → acquire → execute → release → [ready in pool] → ...
                                                                       ↓
                                                            (使用次数达 maxUseCount)
                                                                       ↓
                                                               remove → recreate
```

---

## 5. DockerCodeExecutor

### 5.1 执行入口

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCodeExecutor {

    public record ExecuteResult(String compileOutput, List<ExecutionResult> results) {}

    public ExecuteResult execute(LanguageStrategy strategy,
                                 String code,
                                 List<String> inputList,
                                 String requestId,
                                 int timeLimit,
                                 int memoryLimit) {
        // 1. 危险代码扫描（如果启用）
        // 2. 从容器池获取容器（或传统模式创建新容器）
        // 3. 写入源代码到容器任务目录
        // 4. 编译（如需要）
        // 5. 逐个运行测试用例
        // 6. 清理任务目录，归还容器
    }
}
```

### 5.2 Shell 命令包装（计时 + 内存采集）

所有命令通过 shell 脚本包装，采集精确的执行时间和内存用量：

**有输入路径：**
```bash
cd <taskDir> && \
MEMFILE=/tmp/mem_$$; \
START=$(date +%s%N); \
printf '%s' '<input>' | /usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <cmd>'; \
EXIT_CODE=$?; \
END=$(date +%s%N); \
echo '__EXEC_TIME_NS__:'$((END-START)); \
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; \
echo '__EXEC_MEM_KB__:'$MEM; \
exit $EXIT_CODE
```

**无输入路径：**
```bash
cd <taskDir> && \
MEMFILE=/tmp/mem_$$; \
START=$(date +%s%N); \
/usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <cmd>'; \
EXIT_CODE=$?; \
END=$(date +%s%N); \
echo '__EXEC_TIME_NS__:'$((END-START)); \
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; \
echo '__EXEC_MEM_KB__:'$MEM; \
exit $EXIT_CODE
```

关键设计：
- `EXIT_CODE=$?` 在用户命令执行后立即捕获退出码
- `exit $EXIT_CODE` 在脚本末尾传递退出码给 Docker exec
- `/usr/bin/time -o $MEMFILE` 将内存值写入临时文件，不污染 stdout/stderr
- 时间标记 `__EXEC_TIME_NS__` 和 `__EXEC_MEM_KB__` 从 stdout 末尾提取后移除

### 5.3 命令执行与结果解析

```java
private CommandResult executeCommand(String containerId, String[] cmd,
                                      String input, long timeoutMs) {
    // 1. 构建计时 shell 命令
    // 2. 创建 Docker exec 实例（withUser("sandbox")）
    // 3. 等待完成或超时
    // 4. 从 stdout 提取时间/内存标记
    // 5. 检查输出是否超限
    // 6. 返回 CommandResult（success/failure/timeout/error）
}
```

### 5.4 编译与运行

```java
// 编译（池化模式）
private CommandResult compileInTaskDir(String containerId, LanguageStrategy strategy, String taskDir) {
    String source = taskDir + "/" + strategy.getSourceFileName();
    String output = taskDir + "/" + strategy.getExecutableFileName();
    String[] compileCmd = strategy.getCompileCommand(source, output);
    return executeCommandInDir(containerId, compileCmd, null,
            strategy.getCompileTimeout() * 1000L, taskDir);
}

// 运行（池化模式）
private ExecutionResult runCodeInTaskDir(String containerId, LanguageStrategy strategy,
                                          String taskDir, String input,
                                          int index, int timeLimit, int memoryLimit) {
    CommandResult result = executeCommandInDir(containerId,
            strategy.getRunCommand(taskDir + "/" + strategy.getExecutableFileName()),
            input, timeLimit, taskDir);
    // 将 CommandResult 映射为 ExecutionResult（SUCCESS/TIMEOUT/MLE/RE/OLE）
}
```

### 5.5 文件写入

源代码通过 exec + base64 编码方式写入容器（Docker archive API 被 `readonlyRootfs(true)` 阻止）：

```java
private void writeSourceCodeToContainer(String containerId, String taskDir,
                                         String fileName, String code) {
    ensureTaskDirExists(containerId, taskDir);  // mkdir -p
    // 将代码 Base64 编码后通过 exec 写入
    String base64Code = Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
    String filePath = taskDir + "/" + fileName;
    String[] cmd = {"sh", "-c",
            "echo '" + base64Code + "' | base64 -d > " + filePath};
    // 通过 docker exec 在容器内执行写入
    dockerClient.execCreateCmd(containerId)
            .withUser("sandbox")
            .withCmd(cmd)
            .exec();
}
```

> 设计说明：由于启用了 `readonlyRootfs(true)`，Docker 的 `copyArchiveToContainerCmd`（tar 归档写入）无法写入容器内的只读路径。改为通过 exec 在容器内执行 base64 解码写入 tmpfs 挂载的 `/sandbox/workspace`，绕过此限制。

---

## 6. 执行模式

### 6.1 容器池模式（默认）

```
1. 从容器池获取容器 (acquireContainer)
2. 创建任务目录 (mkdir -p /sandbox/workspace/job-{requestId})
3. 写入源代码 (exec + base64)
4. 编译（如需编译）
5. 逐个运行测试用例
6. 清理任务目录 (rm -rf)
7. 归还容器到池 (releaseContainer)
```

### 6.2 传统模式（pool.enabled=false）

```
1. 创建宿主工作目录写入源代码
2. 创建容器（挂载工作目录）
3. 启动容器
4. 编译 + 运行
5. 停止并删除容器
6. 清理宿主工作目录
```

---

## 7. CommandResult

```java
@Data
@Builder
public class CommandResult {
    private boolean success;
    private int exitCode;
    private String stdout;
    private String stderr;
    private long executionTime;      // 毫秒
    private long memoryUsage;        // KB
    private boolean timeout;
    private boolean memoryExceeded;
    private boolean outputExceeded;
    private String errorMessage;

    public static CommandResult success(String stdout, String stderr, long time, long mem);
    public static CommandResult failure(int exitCode, String stdout, String stderr, long time, long mem);
    public static CommandResult timeout(long timeLimit);
    public static CommandResult memoryExceeded(long memoryUsage);
    public static CommandResult error(String message);
}
```
