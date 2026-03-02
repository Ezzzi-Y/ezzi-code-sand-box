# OJ 代码执行沙箱服务 - 核心代码实现

## 1. 概述

本文档汇总核心代码实现，包括执行服务、异常体系、枚举定义、统一返回结果和配置。

---

## 2. 执行服务

### 2.1 ExecutionService 接口

```java
package com.github.ezzziy.codesandbox.service;

public interface ExecutionService {

    /** 单次执行 */
    SingleExecuteResponse executeSingle(SingleExecuteRequest request);

    /** 批量执行（多测试用例，使用 inputDataUrl 下载 ZIP） */
    BatchExecuteResponse executeBatch(BatchExecuteRequest request);

    /** 获取支持的编程语言列表 */
    List<LanguageInfo> getSupportedLanguages();

    /** 语言信息 record */
    record LanguageInfo(String code, String name, String dockerImage, String extension) {}
}
```

### 2.2 ExecutionServiceImpl 核心流程

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    private final LanguageStrategyFactory strategyFactory;
    private final DockerCodeExecutor dockerCodeExecutor;
    private final ExecutionConfig executionConfig;
    private final InputDataService inputDataService;

    // ... (无 CacheService、DangerousCodeScanner、SecurityAuditLogger)
}
```

#### executeSingle 流程

```
1. 生成/校验 requestId
2. 检查语言是否支持 → strategyFactory.isSupported()
3. 获取 LanguageStrategy
4. 设置默认时间/内存限制（取 request 值或 executionConfig 默认值）
5. 调用 dockerCodeExecutor.execute(strategy, code, inputList, requestId, timeLimit, memoryLimit)
6. 构建 SingleExecuteResponse 返回
```

异常处理直接在方法内 catch，返回对应状态的 response：
- `CompileException` → `ExecutionStatus.COMPILE_ERROR`
- `DangerousCodeException` → `ExecutionStatus.DANGEROUS_CODE`
- `IllegalArgumentException` → `ExecutionStatus.SYSTEM_ERROR`
- `Exception` → `ExecutionStatus.SYSTEM_ERROR`

#### executeBatch 流程

```
1. 生成/校验 requestId
2. 检查语言支持 → strategyFactory.isSupported()
3. 解析输入数据 → inputDataService.getInputDataSet(request.getInputDataUrl())
4. 检查输入数量 ≤ executionConfig.maxTestCases
5. 调用 dockerCodeExecutor.execute(strategy, code, inputList, requestId, timeLimit, memoryLimit)
6. 统计 success/failed 数量，计算 overallStatus
7. 构建 BatchExecuteResponse 返回
```

#### getSupportedLanguages

```java
public List<LanguageInfo> getSupportedLanguages() {
    return strategyFactory.getSupportedLanguages().stream()
            .map(lang -> new LanguageInfo(
                    lang.getCode(), lang.getDisplayName(),
                    lang.getDockerImage(), lang.getExtension()))
            .toList();
}
```

---

## 3. 异常体系

### 3.1 SandboxException（基类）

```java
@Getter
public class SandboxException extends RuntimeException {
    private final ExecutionStatus status;   // 对应的执行状态枚举
    private final String requestId;         // 请求 ID

    public SandboxException(String message);                                    // status=SYSTEM_ERROR
    public SandboxException(String message, Throwable cause);                  // status=SYSTEM_ERROR
    public SandboxException(ExecutionStatus status, String message);
    public SandboxException(ExecutionStatus status, String message, String requestId);
    public SandboxException(ExecutionStatus status, String message, Throwable cause);
}
```

> 注意：异常使用 `ExecutionStatus` 枚举（非 int code），携带 `requestId` 用于追踪。

### 3.2 具体异常类

| 异常类 | 对应状态 | 额外字段 |
|-------|---------|---------|
| `CompileException` | `COMPILE_ERROR` | — |
| `DangerousCodeException` | `DANGEROUS_CODE` | `pattern`（匹配到的危险模式） |
| `RuntimeErrorException` | `RUNTIME_ERROR` | `exitCode`, `errorOutput` |
| `TimeLimitException` | `TIME_LIMIT_EXCEEDED` | `timeLimit` |
| `MemoryLimitException` | `MEMORY_LIMIT_EXCEEDED` | `memoryLimit`, `actualMemory` |

```java
// 示例：DangerousCodeException
public class DangerousCodeException extends SandboxException {
    private final String pattern;

    public DangerousCodeException(String pattern) {
        super(ExecutionStatus.DANGEROUS_CODE,
              String.format("检测到危险代码模式: %s", pattern));
        this.pattern = pattern;
    }

    public DangerousCodeException(String pattern, String requestId) {
        super(ExecutionStatus.DANGEROUS_CODE,
              String.format("检测到危险代码模式: %s", pattern), requestId);
        this.pattern = pattern;
    }
}
```

> 项目中不存在 `UnsupportedLanguageException`、`ContainerException`、`CacheException`、`OSSException`。不支持的语言通过 `IllegalArgumentException` 处理。

### 3.3 GlobalExceptionHandler

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** SandboxException → ResponseEntity<ExecuteResponse> (HTTP 200) */
    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<ExecuteResponse> handleSandboxException(SandboxException e);

    /** MethodArgumentNotValidException → ResponseEntity<Map> (HTTP 400) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(...);

    /** IllegalArgumentException → ResponseEntity<Map> (HTTP 400) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(...);

    /** Exception → ResponseEntity<Map> (HTTP 500) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(...);
}
```

SandboxException 处理示例：
```java
@ExceptionHandler(SandboxException.class)
public ResponseEntity<ExecuteResponse> handleSandboxException(SandboxException e) {
    ExecuteResponse response;
    switch (e.getStatus()) {
        case COMPILE_ERROR -> response = ExecuteResponse.builder()
                .status(e.getStatus())
                .compileOutput(e.getMessage())
                .build();
        default -> response = ExecuteResponse.builder()
                .status(e.getStatus())
                .errorMessage(e.getMessage())
                .build();
    }
    return ResponseEntity.ok(response);
}
```

> 注意：所有 SandboxException 始终返回 HTTP 200，错误信息在 `ExecuteResponse` 的 `status` 字段中体现。

---

## 4. 枚举定义

### 4.1 ExecutionStatus

```java
package com.github.ezzziy.codesandbox.common.enums;

@Getter
@AllArgsConstructor
public enum ExecutionStatus {

    SUCCESS(0, "Success", "执行成功"),
    COMPILE_ERROR(1, "Compile Error", "编译错误"),
    RUNTIME_ERROR(2, "Runtime Error", "运行时错误"),
    TIME_LIMIT_EXCEEDED(3, "Time Limit Exceeded", "时间超限"),
    MEMORY_LIMIT_EXCEEDED(4, "Memory Limit Exceeded", "内存超限"),
    OUTPUT_LIMIT_EXCEEDED(5, "Output Limit Exceeded", "输出超限"),
    SYSTEM_ERROR(6, "System Error", "系统错误"),
    DANGEROUS_CODE(7, "Dangerous Code", "危险代码");

    private final int code;          // 整型状态码
    private final String name;       // 英文名称
    private final String description; // 中文描述

    public static ExecutionStatus fromCode(int code);
    public boolean isSuccess();      // this == SUCCESS
    public boolean isError();        // this != SUCCESS
}
```

### 4.2 LanguageEnum

```java
package com.github.ezzziy.codesandbox.common.enums;

@Getter
@AllArgsConstructor
public enum LanguageEnum {

    C("c", "C", "gcc:11", ".c"),
    CPP("cpp11", "C++11", "gcc:11", ".cpp"),
    JAVA8("java8", "Java 8", "eclipse-temurin:8-jdk-alpine", ".java"),
    JAVA17("java17", "Java 17", "eclipse-temurin:17-jdk-alpine", ".java"),
    PYTHON3("python3", "Python 3", "python:3.10", ".py");

    private final String code;        // 语言标识符
    private final String displayName; // 显示名称
    private final String dockerImage; // 基准镜像名
    private final String extension;   // 源文件扩展名

    public static LanguageEnum fromCode(String code);
    public boolean isJava();
}
```

> 注意：枚举中 `dockerImage` 为基准值，策略类 `getDockerImage()` 返回实际使用的 `sandbox-*:latest` 镜像。

---

## 5. 统一返回结果

### 5.1 Result

```java
package com.github.ezzziy.codesandbox.common.result;

@Data
public class Result<T> {
    private Integer code;     // 1=成功, 0=失败
    private String message;
    private T data;

    public static <T> Result<T> success();            // code=1, message="success"
    public static <T> Result<T> success(T data);      // code=1, message="success", data=data
    public static <T> Result<T> error(String message); // code=0, message=message
    public static <T> Result<T> error(T data);         // code=0, message="error", data=data
}
```

> `Result` 类用于 HealthController 等通用接口。执行相关接口直接返回 `SingleExecuteResponse` / `BatchExecuteResponse`，不经过 `Result` 包装。

---

## 6. 应用启动类

```java
@SpringBootApplication
@EnableScheduling
public class CodeSandBoxApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeSandBoxApplication.class, args);
    }
}
```

> 仅使用 `@EnableScheduling`（容器池定时清理）。无 `@EnableCaching`、`@EnableAsync`。

---

## 7. 配置文件

### 7.1 application.yml（实际配置）

```yaml
server:
  port: 6060

spring:
  application:
    name: code-sand-box

sandbox:
  docker:
    host: unix:///var/run/docker.sock
    connect-timeout: 30        # 秒
    response-timeout: 60       # 秒
    max-connections: 100

  pool:
    enabled: true
    min-size: 2
    max-size: 10
    max-idle-minutes: 10
    max-use-count: 100

  execution:
    compile-timeout: 30        # 秒
    run-timeout: 10            # 秒
    total-timeout: 300         # 秒
    memory-limit: 256          # MB
    cpu-limit: 1.0
    output-limit: 65536        # 字节
    max-processes: 1024
    max-open-files: 256
    max-test-cases: 100
    work-dir: /tmp/sandbox
    enable-code-scan: true
    max-concurrent-containers: 10

  input-data:
    storage-dir: /var/lib/sandbox-inputs
    download-timeout: 30000    # 毫秒
    max-file-size: 10485760    # 10MB

logging:
  level:
    root: INFO
    com.github.ezzziy.codesandbox: DEBUG
    com.github.dockerjava: WARN
```

---

## 8. Maven 依赖（实际 pom.xml）

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.7</version>
</parent>

<properties>
    <java.version>21</java.version>
    <docker-java.version>3.3.4</docker-java.version>
    <hutool.version>5.8.38</hutool.version>
    <commons-compress.version>1.26.0</commons-compress.version>
    <commons-io.version>2.15.1</commons-io.version>
</properties>

<dependencies>
    <!-- Spring Boot -->
    spring-boot-starter-web
    spring-boot-starter-validation

    <!-- Docker Java Client (zerodep transport) -->
    docker-java-core 3.3.4
    docker-java-transport-zerodep 3.3.4

    <!-- Hutool HTTP -->
    hutool-all 5.8.38

    <!-- Apache Commons -->
    commons-compress 1.26.0
    commons-io 2.15.1

    <!-- Lombok -->
    lombok

    <!-- Test -->
    spring-boot-starter-test
</dependencies>
```

> 不包含 MinIO SDK、Caffeine、Aliyun OSS SDK、SpringDoc/Swagger、Actuator。

---

## 9. 总结

### 关键特性

- 支持 5 种编程语言（C, C++, Java 8, Java 17, Python 3）
- Docker 容器池化执行
- 完善的资源限制（内存、CPU、进程数、输出、时间）
- 危险代码正则检测（集成在语言策略中）
- 输入数据 ZIP 下载 + 本地磁盘缓存
- 统一的异常处理和状态枚举
- 健康检查接口

### 文档索引

1. [01-ARCHITECTURE.md](01-ARCHITECTURE.md) - 系统架构与模块划分
2. [02-API-DESIGN.md](02-API-DESIGN.md) - API 设计与数据模型
3. [03-DOCKER-EXECUTOR.md](03-DOCKER-EXECUTOR.md) - Docker 执行器核心实现
4. [04-LANGUAGE-SUPPORT.md](04-LANGUAGE-SUPPORT.md) - 多语言支持配置
5. [05-SECURITY.md](05-SECURITY.md) - 安全机制实现
6. [06-CACHE-OSS.md](06-CACHE-OSS.md) - 输入数据缓存体系
7. [07-CORE-IMPLEMENTATION.md](07-CORE-IMPLEMENTATION.md) - 核心代码实现（本文档）
8. [08-TASK-ISOLATION.md](08-TASK-ISOLATION.md) - 容器任务隔离
9. [09-INPUT-CACHE-REFACTOR-PLAN.md](09-INPUT-CACHE-REFACTOR-PLAN.md) - 输入缓存重构计划
10. [10-SIGNED-GET-ETAG-SOLUTION.md](10-SIGNED-GET-ETAG-SOLUTION.md) - 签名 GET + ETag 方案
11. [11-EXECUTION-STATUS-BUG-REPORT.md](11-EXECUTION-STATUS-BUG-REPORT.md) - 执行状态 Bug 报告
