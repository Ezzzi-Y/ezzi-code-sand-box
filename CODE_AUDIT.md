# 代码质量审计报告

**项目名称：** ezzi-code-sand-box  
**审计日期：** 2026-03-11  
**审计角色：** 首席软件工程师  
**审计范围：** 全代码库审查 — 架构设计、代码异味、安全性及重构建议

---

## 目录

1. [总体概述](#1-总体概述)
2. [代码功能说明](#2-代码功能说明)
3. [模块与类清单](#3-模块与类清单)
4. [架构评估](#4-架构评估)
5. [代码异味分析](#5-代码异味分析)
6. [详细发现](#6-详细发现)
7. [重构建议](#7-重构建议)
8. [改进后的结构示例](#8-改进后的结构示例)
9. [总结与优先级行动项](#9-总结与优先级行动项)

---

## 1. 总体概述

**ezzi-code-sand-box** 是一个基于 Docker 的远程代码执行沙箱，使用 Spring Boot 3.5.7 和 Java 21 构建。支持五种编程语言（C、C++11、Java 8、Java 17、Python 3），通过 REST API 提供单次和批量代码执行功能。项目展现了扎实的领域理解和良好的安全实践（网络隔离、能力丢弃、危险代码扫描、沙箱用户执行）。

**架构总评：B+** — 分层设计（Controller → Service → Executor）合理，策略模式在语言支持上的应用恰当。但 `DockerCodeExecutor` 中的**重复代码**和**过长函数**等代码异味降低了可维护性，增加了 Bug 传播的风险。

### 关键指标

| 指标 | 值 |
|---|---|
| Java 源文件总数 | ~35 个生产文件 + 1 个测试文件 |
| 最大文件 | `DockerCodeExecutor.java`（约 930 行） |
| 支持语言数 | 5（C、C++11、Java 8、Java 17、Python 3） |
| REST 端点数 | 7 |
| 测试覆盖率 | 极低（仅 1 个上下文加载测试） |

---

## 2. 代码功能说明

本应用是一个**在线评测 / 代码执行引擎**，主要流程如下：

1. **接收代码** — 通过 REST API（`/execute/single`、`/execute/batch`）接收代码、语言类型和输入数据。
2. **危险代码扫描** — 使用按语言定义的正则规则检测危险模式（策略模式）。
3. **获取 Docker 容器** — 从热容器池中获取（高性能模式）或新建容器（传统模式）。
4. **写入源码** — 通过 Docker TAR 拷贝 API 将源码写入容器。
5. **编译代码** — 对编译型语言在容器内执行编译。
6. **运行代码** — 逐个执行测试用例，捕获 stdout/stderr，同时进行纳秒级计时和内存监测。
7. **返回结果** — 返回结构化的执行结果，包含状态、输出、用时和内存使用量。
8. **资源清理** — 清理任务目录，归还容器到池中。

### 执行流程

```
HTTP 请求 → ExecuteController → ExecutionServiceImpl → DockerCodeExecutor
                                                           ├── LanguageStrategyFactory → LanguageStrategy（扫描、编译/运行命令）
                                                           ├── ContainerPool → PooledContainer（获取/归还）
                                                           ├── ContainerManager（创建、启动、停止、删除）
                                                           └── Docker API（exec、copy、inspect）
```

---

## 3. 模块与类清单

### 3.1 分层与职责

| 层级 | 包名 | 类 | 职责 |
|---|---|---|---|
| **API 层** | `controller` | `ExecuteController`、`HealthController` | REST 端点定义、请求校验 |
| **服务层** | `service` / `service.impl` | `ExecutionService(Impl)`、`HealthService(Impl)`、`InputDataService(Impl)` | 业务编排、输入解析、健康检查 |
| **执行器层** | `executor` | `DockerCodeExecutor`、`ContainerManager`、`CommandResult` | Docker 命令执行核心、容器生命周期 |
| **连接池层** | `pool` | `ContainerPool`、`PooledContainer` | 热容器池管理（含锁机制） |
| **策略层** | `strategy` | `LanguageStrategy`（接口）、`LanguageStrategyFactory`、5 个具体策略 | 语言相关的编译/执行/安全规则 |
| **模型层** | `model.dto` / `model.vo` | DTO 和 VO（共 8 个类） | 数据传输与响应结构 |
| **配置层** | `config` | `DockerConfig`、`ExecutionConfig` | Spring 配置 Bean |
| **异常层** | `exception` | `SandboxException` + 5 个子类、`GlobalExceptionHandler` | 异常体系与统一异常处理 |
| **公共层** | `common.enums` / `common.result` | `ExecutionStatus`、`LanguageEnum`、`Result` | 共享枚举与统一响应包装 |
| **工具层** | `util` | `OssUrlParser` | URL 解析工具 |

### 3.2 关键类代码行数（近似值）

| 类 | 行数 | 关注点 |
|---|---|---|
| `DockerCodeExecutor` | ~930 | ⚠️ 最大 — 执行编排、Shell 命令构建、输出解析、文件 I/O |
| `ContainerPool` | ~460 | 容器池生命周期、锁机制、定时清理 |
| `InputDataServiceImpl` | ~441 | ZIP 下载、缓存、版本比对 |
| `ContainerManager` | ~373 | Docker 容器 CRUD 操作 |
| `ExecutionServiceImpl` | ~260 | 业务逻辑编排 |
| 各语言策略类 | 每个约 100–125 | 语言特定命令与规则 |

---

## 4. 架构评估

### 4.1 优势

| 领域 | 评估 |
|---|---|
| **分层架构** | ✅ 清晰的 Controller → Service → Executor 分层，基于接口的服务契约 |
| **策略模式** | ✅ 语言支持上运用得当，新增语言只需实现 `LanguageStrategy` 接口 |
| **容器池** | ✅ 实现稳健，具备正确的锁机制、定时清理和僵尸容器检测 |
| **安全性** | ✅ 多层防护：禁用网络、丢弃能力、沙箱用户、代码扫描、资源限制 |
| **配置管理** | ✅ 通过 `ExecutionConfig` 外部化配置，默认值合理 |
| **异常体系** | ✅ 以 `SandboxException` 为根的领域异常层次清晰 |
| **Docker 集成** | ✅ 正确使用 docker-java API，基于 TAR 的文件拷贝和 exec 命令执行 |

### 4.2 不足

| 领域 | 评估 |
|---|---|
| **`DockerCodeExecutor` 体量** | ⚠️ 930 行 — 有上帝对象（God Object）倾向，混合了编排、Shell 构建、输出解析、文件 I/O |
| **重复代码** | ⚠️ `executeCommand()` 和 `executeCommandInDir()` 约 90% 重复（各约 160 行） |
| **重复结果映射** | ⚠️ `runCode()` 和 `runCodeInTaskDir()` 具有几乎相同的结果映射逻辑 |
| **测试覆盖** | ❌ 仅 1 个上下文加载测试；策略、执行器、连接池、服务均无单元测试 |
| **异常处理** | ⚠️ 多处使用 `RuntimeException` 而非领域特定异常 |
| **格式不一致** | ⚠️ `ExecutionServiceImpl` 中存在缩进不一致（4 空格与 16 空格混用） |
| **未使用的依赖** | ⚠️ `pom.xml` 中包含 JJWT、POI、MyBatis Plus、OKHttp — 源码中未引用 |
| **硬编码值** | ⚠️ 存在分散的魔法数字/字符串（如重试次数、休眠时长、路径前缀） |

### 4.3 关注点分离

| 关注点 | 当前位置 | 评估 |
|---|---|---|
| Shell 命令构建 | `DockerCodeExecutor` | ⚠️ 应抽取到专用的构建器类 |
| 输出/时间/内存解析 | `DockerCodeExecutor` | ⚠️ 应抽取到解析器类 |
| 文件 I/O（TAR 创建、文件写入） | `DockerCodeExecutor` | ⚠️ 可分离到工具类 |
| 结果映射（CommandResult → ExecutionResult） | `DockerCodeExecutor` | ⚠️ 在传统模式和池模式之间重复 |
| 容器生命周期 | `ContainerManager` | ✅ 封装良好 |
| 池管理 | `ContainerPool` | ✅ 封装良好 |

### 4.4 依赖管理

- **`pom.xml` 中未使用的依赖**：`jjwt-api`、`jjwt-impl`、`jjwt-jackson`、`poi`、`poi-ooxml`、`mybatis-plus-spring-boot3-starter`、`okhttp` — 源码中均未导入。这增加了不必要的构建时间和安全攻击面。
- **Hutool** 仅在 `InputDataServiceImpl` 中用于 HTTP 请求（`cn.hutool.http`），是否值得引入整个库值得评估。

---

## 5. 代码异味分析

### 5.1 代码异味总览

| # | 异味类型 | 严重程度 | 位置 | 描述 |
|---|---|---|---|---|
| 1 | **过长函数 / 上帝对象** | 🔴 高 | `DockerCodeExecutor`（930 行） | 单个类承担编排、Shell 构建、输出解析、文件 I/O 和结果映射 |
| 2 | **重复代码** | 🔴 高 | `executeCommand()` vs `executeCommandInDir()` | 约 160 行重复，仅差 `cd` 前缀 |
| 3 | **重复代码** | 🟠 中 | `runCode()` vs `runCodeInTaskDir()` | 几乎相同的结果映射逻辑（约 50 行） |
| 4 | **重复代码** | 🟠 中 | `executeTraditional()` vs `executeWithPool()` | 相同的编译→运行→收集模式，仅有细微差异 |
| 5 | **重复代码** | 🟡 低 | `executeSingle()` vs `executeBatch()` | 类似的校验、策略查找和异常处理 |
| 6 | **基本类型偏执** | 🟠 中 | `DockerCodeExecutor.execute()` | 6 个基本类型参数，缺少请求/上下文对象 |
| 7 | **基本类型偏执** | 🟠 中 | `HealthServiceImpl` | 返回 `Map<String, Object>` 而非类型化的响应对象 |
| 8 | **数据泥团** | 🟡 低 | `(containerId, strategy, taskDir)` | 频繁一起传递，可以封装为对象 |
| 9 | **特性依恋** | 🟡 低 | `DockerCodeExecutor` | 大量操作 `CommandResult` 字段 — 解析逻辑应属于 `CommandResult` |
| 10 | **霰弹式修改** | 🟡 低 | 新增执行模式时 | 需要修改 `DockerCodeExecutor`（2 处）、`ContainerManager`、`ContainerPool` |
| 11 | **格式不一致** | 🟡 低 | `ExecutionServiceImpl` | `executeBatch()` 方法中缩进风格混乱 |

### 5.2 异味详细分析

#### 5.2.1 重复代码 — `executeCommand()` vs `executeCommandInDir()`（严重）

`DockerCodeExecutor` 中这两个方法各约 160 行，几乎完全相同。唯一的区别是 `executeCommandInDir()` 在 Shell 命令前加了 `cd <workDir> &&`。两个方法都执行以下步骤：

1. 使用 `date +%s%N` 和 `/usr/bin/time` 构建计时 Shell 命令
2. 处理输入转义和管道
3. 创建 Docker exec 实例
4. 捕获 stdout/stderr
5. 等待完成或超时
6. 从输出中解析时间标记和内存标记
7. 检查输出限制
8. 返回 `CommandResult`

**风险：** 任何 Bug 修复或功能变更（如修改计时机制）都必须在两处同时修改，随着时间推移极易产生分歧。

#### 5.2.2 重复代码 — `runCode()` vs `runCodeInTaskDir()`（中等）

两个方法都执行相同的 `CommandResult` → `ExecutionResult` 映射链：检查超时 → 检查内存超限 → 检查输出超限 → 检查退出码 → 返回成功。唯一区别在于可执行文件路径的构建方式。

#### 5.2.3 过长函数 / 上帝对象 — `DockerCodeExecutor`（严重）

该类约 930 行，承担了过多职责：
- **编排**：`execute()`、`executeWithPool()`、`executeTraditional()`
- **Shell 命令构建**：构建包含计时/内存监测的复杂 Shell 脚本
- **输出解析**：从 stdout 中提取时间/内存标记
- **文件 I/O**：`writeSourceCodeToContainer()`、`writeSourceCode()`、`createWorkDirectory()`
- **结果映射**：将 `CommandResult` 转换为 `ExecutionResult`

#### 5.2.4 基本类型偏执 — execute() 参数

```java
public ExecuteResult execute(LanguageStrategy strategy,
                             String code,
                             List<String> inputList,
                             String requestId,
                             int timeLimit,
                             int memoryLimit)
```

六个参数（且继续向内部方法传递）表明缺少一个 **ExecutionContext（执行上下文）** 概念。

---

## 6. 详细发现

### 6.1 封装问题

1. **`PooledContainer`** 使用公开的 setter（`setInUse()`、`setUseCount()`、`setLastUsedTime()`），被 `ContainerPool` 调用。这些状态变更应封装为 `PooledContainer` 的行为方法（如 `markInUse()`、`markIdle()`、`incrementUseCount()`）。

2. **`CommandResult`** 是一个被动的数据持有者。`DockerCodeExecutor` 中的输出解析逻辑（提取时间/内存标记）属于**特性依恋** — 将其作为 `CommandResult` 的工厂方法会更内聚。

### 6.2 异常处理

1. **滥用 RuntimeException**：`ContainerManager.createContainer()`、`InputDataServiceImpl` 以及 `DockerCodeExecutor` 的多处抛出带中文消息的 `RuntimeException`。应使用领域特定异常（如 `ContainerLimitException`、`InputDataException`）。

2. **静默 catch 块**：`ContainerManager.stopContainer()` 和 `killContainer()` 捕获异常后仅记录日志，可能隐藏清理过程中的关键故障。

### 6.3 未使用的依赖（pom.xml）

以下依赖已声明但源码中未使用：

| 依赖 | Group ID | 是否使用 |
|---|---|---|
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | `io.jsonwebtoken` | ❌ 未使用 |
| `poi` / `poi-ooxml` | `org.apache.poi` | ❌ 未使用 |
| `mybatis-plus-spring-boot3-starter` | `com.baomidou` | ❌ 未使用 |
| `okhttp` | `com.squareup.okhttp3` | ❌ 未使用 |

这些依赖增加了约 30+ MB 的最终构建产物体积，并扩大了安全攻击面。

### 6.4 硬编码配置

| 硬编码值 | 位置 | 建议 |
|---|---|---|
| `"sandbox"` 用户名 | `ContainerManager`、`DockerCodeExecutor` | 抽取到 `ExecutionConfig` |
| `"/sandbox/workspace"` 路径 | `ContainerManager`、`DockerCodeExecutor` | 抽取为常量或配置项 |
| 重试次数 `5`、休眠 `200ms` | `ContainerPool.createAndAddContainer()` | 抽取到配置 |
| `ACQUIRE_WAIT_SECONDS = 30` | `ContainerPool` | 已是常量 ✅，可改为配置驱动 |

### 6.5 测试覆盖

项目仅有一个测试（`CodeSandBoxApplicationTests`），执行基本的 Spring 上下文加载。以下模块无单元测试：

- 语言策略（危险代码模式检测）
- `DockerCodeExecutor`（输出解析、Shell 转义）
- `ContainerPool`（获取/归还/清理逻辑）
- `InputDataServiceImpl`（缓存、ZIP 解压）
- `OssUrlParser`（URL 解析）
- `ExecutionServiceImpl`（请求校验、异常处理）

---

## 7. 重构建议

### 7.1 优先级 1（高影响）— 消除 DockerCodeExecutor 中的重复代码

**问题：** `executeCommand()` 和 `executeCommandInDir()` 约 90% 重复。

**方案：** 合并为一个接受可选 `workDir` 参数的方法。

```java
// 重构前：两个约 160 行的方法
private CommandResult executeCommand(String containerId, String[] cmd, String input, long timeoutMs) { ... }
private CommandResult executeCommandInDir(String containerId, String[] cmd, String input, long timeoutMs, String workDir) { ... }

// 重构后：统一方法，workDir 可为 null
private CommandResult executeCommand(String containerId, String[] cmd, String input, long timeoutMs, String workDir) {
    String cdPrefix = (workDir != null) ? "cd " + workDir + " && " : "";
    // ... 统一实现 ...
}
```

同样，通过抽取共享的结果映射逻辑来统一 `runCode()` 和 `runCodeInTaskDir()`。

### 7.2 优先级 2（高影响）— 抽取 Shell 命令构建器

**问题：** 复杂的 Shell 命令构建逻辑内联在执行方法中。

**方案：** 抽取 `ShellCommandBuilder` 工具类。

```java
public class ShellCommandBuilder {
    public static String buildTimedCommand(String command, String input, String workDir) {
        StringBuilder sb = new StringBuilder();
        if (workDir != null) {
            sb.append("cd ").append(workDir).append(" && ");
        }
        sb.append("MEMFILE=/tmp/mem_$$; ");
        sb.append("START=$(date +%s%N); ");
        if (input != null && !input.isEmpty()) {
            sb.append(String.format("printf '%%s' '%s' | ", escapeShellInput(input)));
        }
        sb.append(String.format("/usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; ", command));
        sb.append("EXIT_CODE=$?; END=$(date +%s%N); ");
        sb.append(String.format("echo '%s'$((END-START)); ", TIME_MARKER));
        sb.append("MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; ");
        sb.append(String.format("echo '%s'$MEM; ", MEMORY_MARKER));
        sb.append("exit $EXIT_CODE");
        return sb.toString();
    }
}
```

### 7.3 优先级 3（中等影响）— 抽取输出解析器

**问题：** 时间/内存标记解析与执行逻辑交织。

**方案：** 创建 `CommandOutputParser` 封装解析逻辑：

```java
public class CommandOutputParser {
    public record ParsedOutput(String cleanOutput, long executionTimeMs, long memoryUsageKB) {}

    public static ParsedOutput parse(String rawStdout, long fallbackTimeMs) {
        // 提取 TIME_MARKER、MEMORY_MARKER，清理输出
        // 返回结构化结果
    }
}
```

### 7.4 优先级 4（中等影响）— 引入 ExecutionContext

**问题：** 6 个以上的基本类型参数在多个方法调用间传递。

**方案：** 引入上下文对象：

```java
public record ExecutionContext(
    LanguageStrategy strategy,
    String code,
    List<String> inputList,
    String requestId,
    int timeLimitMs,
    int memoryLimitMB
) {}
```

### 7.5 优先级 5（中等影响）— 统一结果映射

**问题：** `runCode()` 和 `runCodeInTaskDir()` 中 `CommandResult` → `ExecutionResult` 的映射逻辑重复。

**方案：** 抽取共享方法：

```java
private ExecutionResult mapToExecutionResult(CommandResult result, int index, int timeLimit, int memoryLimit) {
    long executionTime = result.getExecutionTime();
    if (result.isTimeout()) return ExecutionResult.timeout(index, timeLimit);
    if (result.isMemoryExceeded()) return ExecutionResult.memoryExceeded(index, executionTime, result.getMemoryUsage());
    if (result.isOutputExceeded()) return ExecutionResult.runtimeError(index, normalizeOutput(result.getStdout()), "Output limit exceeded", executionTime, result.getMemoryUsage(), result.getExitCode());
    if (!result.isSuccess()) return ExecutionResult.runtimeError(index, normalizeOutput(result.getStdout()), result.getStderr(), executionTime, result.getMemoryUsage(), result.getExitCode());
    return ExecutionResult.success(index, normalizeOutput(result.getStdout()), result.getStderr(), executionTime, result.getMemoryUsage());
}
```

### 7.6 优先级 6（低影响）— 移除未使用的依赖

从 `pom.xml` 中移除：
- `io.jsonwebtoken:jjwt-api`、`jjwt-impl`、`jjwt-jackson`
- `org.apache.poi:poi`、`poi-ooxml`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `com.squareup.okhttp3:okhttp`

### 7.7 优先级 7（低影响）— 修复 ExecutionServiceImpl 的缩进

`executeBatch()` 方法存在缩进不一致（4 空格与 16 空格混用），应统一格式化。

### 7.8 优先级 8（低影响）— 补充单元测试

优先测试目标：
1. **语言策略模式检测** — 对每个策略的 `checkDangerousCode()` 编写单元测试
2. **Shell 输入转义** — `escapeShellInput()` 的边界情况测试
3. **输出解析** — 时间/内存标记提取测试
4. **OssUrlParser** — 不同云服务商的 URL 解析测试
5. **ContainerPool** — 获取/归还逻辑（可 mock `ContainerManager`）

---

## 8. 改进后的结构示例

### 8.1 建议的包结构（执行器重构）

```
executor/
├── DockerCodeExecutor.java          // 仅保留编排逻辑（约 300 行）
├── ContainerManager.java            // 容器生命周期（不变）
├── CommandResult.java               // 执行结果 DTO（不变）
├── CommandOutputParser.java         // 新增：解析输出中的时间/内存标记
├── ShellCommandBuilder.java         // 新增：构建计时 Shell 命令
├── ExecutionContext.java            // 新增：封装执行参数
└── ResultMapper.java               // 新增：CommandResult → ExecutionResult 映射
```

### 8.2 重构后的 DockerCodeExecutor（概念示例）

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCodeExecutor {

    private final DockerClient dockerClient;
    private final ContainerManager containerManager;
    private final ContainerPool containerPool;
    private final ExecutionConfig executionConfig;

    @Value("${sandbox.pool.enabled:true}")
    private boolean poolEnabled;

    public ExecuteResult execute(ExecutionContext ctx) {
        // 1. 危险代码扫描
        scanForDangerousCode(ctx);

        // 2. 根据模式委派执行
        return poolEnabled
            ? executeWithPool(ctx)
            : executeTraditional(ctx);
    }

    private ExecuteResult executeWithPool(ExecutionContext ctx) {
        PooledContainer pooled = null;
        String taskDir = null;
        try {
            pooled = containerPool.acquireContainer(ctx.strategy());
            taskDir = containerManager.getTaskDirectory("job-" + ctx.requestId());

            writeSourceCodeToContainer(pooled.getContainerId(), taskDir, ctx);
            String compileOutput = compileIfNeeded(pooled.getContainerId(), ctx, taskDir);
            List<ExecutionResult> results = runAllTestCases(pooled.getContainerId(), ctx, taskDir);

            return new ExecuteResult(compileOutput, results);
        } finally {
            cleanup(pooled, taskDir, ctx.requestId());
        }
    }

    private CommandResult executeCommand(String containerId, String[] cmd,
                                          String input, long timeoutMs, String workDir) {
        // 统一方法 — workDir 为 null 时表示传统模式
        String timedCmd = ShellCommandBuilder.buildTimedCommand(
            String.join(" ", cmd), input, workDir);
        // ... Docker exec 逻辑 ...
        // ... 使用 CommandOutputParser 解析输出 ...
    }

    private ExecutionResult runSingleTestCase(String containerId, ExecutionContext ctx,
                                               String taskDir, String input, int index) {
        CommandResult result = executeCommand(/* ... */);
        return ResultMapper.map(result, index, ctx.timeLimitMs(), ctx.memoryLimitMB());
    }
}
```

### 8.3 重构前后代码行数对比

| 组件 | 重构前 | 重构后（预估） |
|---|---|---|
| `DockerCodeExecutor` | ~930 行 | ~350 行 |
| `ShellCommandBuilder` | — | ~60 行 |
| `CommandOutputParser` | — | ~70 行 |
| `ResultMapper` | — | ~40 行 |
| `ExecutionContext` | — | ~15 行 |
| **合计** | **~930 行** | **~535 行** |

通过去重和抽取，净减少约 395 行代码，同时每个抽取的组件都具备更好的可读性和可测试性。

---

## 9. 总结与优先级行动项

### 紧急（应立即修复）

| # | 事项 | 影响 | 工作量 |
|---|---|---|---|
| 1 | 合并 `executeCommand()` / `executeCommandInDir()` 消除约 160 行重复 | 高 — 降低 Bug 风险 | 中 |
| 2 | 从 `DockerCodeExecutor` 抽取 `ShellCommandBuilder` | 高 — 提升可读性和可测试性 | 中 |
| 3 | 从 `DockerCodeExecutor` 抽取 `CommandOutputParser` | 高 — 隔离解析逻辑 | 低 |
| 4 | 统一 `runCode()` / `runCodeInTaskDir()` 的结果映射逻辑 | 高 — 消除约 50 行重复 | 低 |

### 建议（应纳入计划）

| # | 事项 | 影响 | 工作量 |
|---|---|---|---|
| 5 | 为语言策略、输出解析、Shell 转义补充单元测试 | 高 — 防止回归 | 中 |
| 6 | 引入 `ExecutionContext` 参数对象 | 中 — 更清晰的 API | 低 |
| 7 | 从 `pom.xml` 移除未使用的依赖 | 中 — 减小构建产物体积和攻击面 | 低 |
| 8 | 将 `RuntimeException` 替换为领域特定异常 | 中 — 更好的异常处理 | 低 |

### 锦上添花（低优先级）

| # | 事项 | 影响 | 工作量 |
|---|---|---|---|
| 9 | 修复 `ExecutionServiceImpl` 中的缩进不一致 | 低 — 代码可读性 | 低 |
| 10 | 将硬编码值（`"sandbox"`、`"/sandbox/workspace"`）抽取到配置 | 低 — 灵活性 | 低 |
| 11 | 为 `PooledContainer` 添加行为方法（`markInUse()`、`markIdle()`） | 低 — 封装性 | 低 |
| 12 | 评估 Hutool 依赖是否值得仅为 HTTP 功能引入 | 低 — 依赖卫生 | 低 |

---

*审计报告结束。*
