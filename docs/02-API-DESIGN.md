# OJ 代码执行沙箱服务 - API 设计与数据模型

## 1. API 概览

### 1.1 接口列表

| 接口 | 方法 | 路径 | 描述 |
|------|------|------|------|
| 单次执行 | POST | `/execute/single` | 执行单个输入并返回结果 |
| 批量执行 | POST | `/execute/batch` | 批量执行多个测试用例 |
| 语言支持 | GET | `/execute/languages` | 获取支持的语言列表 |
| 健康检查 | GET | `/health` | 服务健康状态（详细） |
| 存活探针 | GET | `/health/ping` | 简单存活检测 |
| 存活探针 | GET | `/health/liveness` | K8s 存活探针 |
| 就绪探针 | GET | `/health/readiness` | K8s 就绪探针 |

### 1.2 通用响应格式

```json
{
  "code": 1,
  "message": "success",
  "data": { ... }
}
```

> 注：`code` 为 `1` 表示成功，`0` 表示失败。

---

## 2. 核心接口设计

### 2.1 单次执行接口

#### 请求

```http
POST /execute/single
Content-Type: application/json
```

```json
{
  "requestId": "abc123",
  "language": "cpp11",
  "code": "#include <iostream>\nusing namespace std;\nint main() {\n    int a, b;\n    cin >> a >> b;\n    cout << a + b << endl;\n    return 0;\n}",
  "input": "1 2",
  "timeLimit": 1000,
  "memoryLimit": 256
}
```

#### 请求参数说明（SingleExecuteRequest）

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `requestId` | String | ❌ | 请求标识，为空时自动生成 UUID |
| `language` | String | ✅ | 编程语言：`c`, `cpp11`, `java8`, `java17`, `python3` |
| `code` | String | ✅ | 用户源代码（最大 64KB） |
| `input` | String | ❌ | 标准输入内容，为空则不提供 stdin |
| `timeLimit` | Integer | ❌ | 时间限制（毫秒），默认使用配置 `run-timeout * 1000` |
| `memoryLimit` | Integer | ❌ | 内存限制（MB），默认使用配置 `memory-limit` |

#### 响应

```json
{
  "code": 1,
  "message": "success",
  "data": {
    "status": "SUCCESS",
    "compileOutput": null,
    "errorMessage": null,
    "result": {
      "index": 1,
      "status": "SUCCESS",
      "output": "3",
      "errorOutput": "",
      "time": 15,
      "memory": 2048,
      "exitCode": 0
    },
    "totalTime": 32
  }
}
```

#### 响应参数说明（SingleExecuteResponse）

| 字段 | 类型 | 描述 |
|------|------|------|
| `status` | ExecutionStatus | 总体执行状态 |
| `compileOutput` | String | 编译输出（含 stdout+stderr），无编译或编译无输出时为 null |
| `errorMessage` | String | 错误信息（系统错误/危险代码等） |
| `result` | ExecutionResult | 测试用例运行结果（编译失败时为 null） |
| `result.index` | Integer | 用例序号（从 1 开始） |
| `result.status` | ExecutionStatus | 单用例执行状态 |
| `result.output` | String | 程序标准输出（已 trim） |
| `result.errorOutput` | String | 程序标准错误 |
| `result.time` | Long | 运行耗时（毫秒） |
| `result.memory` | Long | 内存使用（KB） |
| `result.exitCode` | Integer | 程序退出码 |
| `totalTime` | Long | 总耗时（毫秒，含编译） |

---

### 2.2 批量执行接口

#### 请求

```http
POST /execute/batch
Content-Type: application/json
```

```json
{
  "requestId": "batch-001",
  "language": "java17",
  "code": "import java.util.*;\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int a = sc.nextInt();\n        int b = sc.nextInt();\n        System.out.println(a + b);\n    }\n}",
  "inputDataUrl": "https://example.com/questions/1001/inputs.zip?signature=xxx",
  "timeLimit": 2000,
  "memoryLimit": 256
}
```

#### 请求参数说明（BatchExecuteRequest）

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `requestId` | String | ❌ | 请求标识 |
| `language` | String | ✅ | 编程语言 |
| `code` | String | ✅ | 用户源代码（最大 64KB） |
| `inputDataUrl` | String | ✅ | 预签名 URL，指向 zip 输入数据包 |
| `timeLimit` | Integer | ❌ | 每个用例的时间限制（毫秒） |
| `memoryLimit` | Integer | ❌ | 内存限制（MB） |

> zip 文件中仅包含输入文件，命名规则为 `1.in`、`2.in`、`3.in`...

#### 响应

```json
{
  "code": 1,
  "message": "success",
  "data": {
    "status": "RUNTIME_ERROR",
    "compileOutput": null,
    "errorMessage": null,
    "results": [
      {
        "index": 1,
        "status": "SUCCESS",
        "output": "3",
        "errorOutput": "",
        "time": 35,
        "memory": 15360,
        "exitCode": 0
      },
      {
        "index": 2,
        "status": "SUCCESS",
        "output": "7",
        "errorOutput": "",
        "time": 28,
        "memory": 15360,
        "exitCode": 0
      },
      {
        "index": 3,
        "status": "TIME_LIMIT_EXCEEDED",
        "output": null,
        "errorOutput": null,
        "time": 2000,
        "memory": 0,
        "exitCode": null
      }
    ],
    "summary": {
      "total": 3,
      "success": 2,
      "failed": 1
    },
    "totalTime": 5600
  }
}
```

#### 响应参数说明（BatchExecuteResponse）

| 字段 | 类型 | 描述 |
|------|------|------|
| `status` | ExecutionStatus | 总体状态（全部成功为 SUCCESS，否则为首个非成功状态） |
| `compileOutput` | String | 编译输出 |
| `errorMessage` | String | 错误信息 |
| `results` | List&lt;ExecutionResult&gt; | 每个测试用例结果 |
| `summary.total` | Integer | 总用例数 |
| `summary.success` | Integer | 成功用例数 |
| `summary.failed` | Integer | 失败用例数 |
| `totalTime` | Long | 总耗时（毫秒） |

---

### 2.3 输入数据缓存（批量 URL 模式）

- 批量执行使用 `inputDataUrl` 时，服务发起 GET 请求下载 ZIP。
- 从 GET 响应头提取 `ETag` / `Last-Modified` 与本地缓存元数据比对。
- 一致则使用本地缓存（忽略本次下载体），不一致则解压落盘并更新元数据。
- 本地缓存目录由 `ObjectKey` 决定，目录中保存 `*.in` 文件与 `_meta.properties`。
- 当前版本无独立缓存管理 API（无 `/cache/*` 路由）。

---

### 2.4 健康检查接口

#### 请求

```http
GET /health
```

#### 响应

```json
{
  "code": 1,
  "message": "success",
  "data": {
    "status": "UP",
    "timestamp": "2026-03-02T10:00:00Z",
    "uptime": "1d 2h 30m 15s",
    "jvm": {
      "version": "21.0.x",
      "vendor": "Eclipse Adoptium",
      "heapUsed": "128.5 MB",
      "heapMax": "512.0 MB",
      "heapUsage": "25.1%",
      "nonHeapUsed": "64.2 MB",
      "availableProcessors": 4
    },
    "docker": {
      "status": "UP",
      "serverVersion": "20.10.21",
      "containers": 5,
      "containersRunning": 5,
      "images": 10,
      "driver": "overlay2",
      "cpus": 4
    },
    "containers": {
      "active": 5
    }
  }
}
```

#### 其他健康端点

| 端点 | 描述 |
|------|------|
| `GET /health/ping` | 返回 `{"status": "UP", "timestamp": ...}` |
| `GET /health/liveness` | K8s 存活探针，始终返回 UP |
| `GET /health/readiness` | K8s 就绪探针，检查 Docker 是否可用 |

---

### 2.5 语言支持接口

#### 请求

```http
GET /execute/languages
```

#### 响应

```json
{
  "code": 1,
  "message": "success",
  "data": [
    {
      "code": "c",
      "name": "C",
      "dockerImage": "sandbox-gcc:latest",
      "extension": ".c"
    },
    {
      "code": "cpp11",
      "name": "C++11",
      "dockerImage": "sandbox-gcc:latest",
      "extension": ".cpp"
    },
    {
      "code": "java8",
      "name": "Java 8",
      "dockerImage": "sandbox-java8:latest",
      "extension": ".java"
    },
    {
      "code": "java17",
      "name": "Java 17",
      "dockerImage": "sandbox-java17:latest",
      "extension": ".java"
    },
    {
      "code": "python3",
      "name": "Python 3",
      "dockerImage": "sandbox-python:latest",
      "extension": ".py"
    }
  ]
}
```

---

## 3. 数据模型

### 3.1 执行状态枚举

```java
public enum ExecutionStatus {
    SUCCESS(0, "Success", "执行成功"),
    COMPILE_ERROR(1, "Compile Error", "编译错误"),
    RUNTIME_ERROR(2, "Runtime Error", "运行时错误"),
    TIME_LIMIT_EXCEEDED(3, "Time Limit Exceeded", "时间超限"),
    MEMORY_LIMIT_EXCEEDED(4, "Memory Limit Exceeded", "内存超限"),
    OUTPUT_LIMIT_EXCEEDED(5, "Output Limit Exceeded", "输出超限"),
    SYSTEM_ERROR(6, "System Error", "系统错误"),
    DANGEROUS_CODE(7, "Dangerous Code", "危险代码");
}
```

### 3.2 请求模型

```java
/**
 * 单次执行请求
 */
@Data
@Builder
public class SingleExecuteRequest {
    private String requestId;

    @NotBlank(message = "代码不能为空")
    @Size(max = 65536, message = "代码长度不能超过64KB")
    private String code;

    @NotBlank(message = "编程语言不能为空")
    private String language;

    private String input;
    private Integer timeLimit;
    private Integer memoryLimit;
}

/**
 * 批量执行请求
 */
@Data
@Builder
public class BatchExecuteRequest {
    private String requestId;

    @NotBlank(message = "代码不能为空")
    @Size(max = 65536, message = "代码长度不能超过64KB")
    private String code;

    @NotBlank(message = "编程语言不能为空")
    private String language;

    @NotBlank(message = "inputDataUrl 不能为空，且必须是 zip 文件 URL")
    private String inputDataUrl;

    private Integer timeLimit;
    private Integer memoryLimit;
}
```

### 3.3 响应模型

```java
/**
 * 单次执行响应
 */
@Data
@Builder
public class SingleExecuteResponse {
    private ExecutionStatus status;
    private String compileOutput;
    private String errorMessage;
    private ExecutionResult result;
    private Long totalTime;
}

/**
 * 批量执行响应
 */
@Data
@Builder
public class BatchExecuteResponse {
    private ExecutionStatus status;
    private String compileOutput;
    private String errorMessage;
    private List<ExecutionResult> results;
    private Summary summary;
    private Long totalTime;

    @Data
    @Builder
    public static class Summary {
        private Integer total;
        private Integer success;
        private Integer failed;
    }
}

/**
 * 单测试用例结果
 */
@Data
@Builder
public class ExecutionResult {
    private Integer index;
    private ExecutionStatus status;
    private String output;
    private String errorOutput;
    private Long time;
    private Long memory;
    private Integer exitCode;
}
```

### 3.4 统一响应包装

```java
@Data
public class Result<T> {
    private Integer code;      // 1=成功, 0=失败
    private String message;
    private T data;

    public static <T> Result<T> success(T data) { ... }
    public static <T> Result<T> error(String message) { ... }
    public static <T> Result<T> error(T data) { ... }
}
```

---

## 4. 异常处理

### 4.1 GlobalExceptionHandler

异常类型与响应映射：

| 异常类型 | HTTP 状态 | 响应处理 |
|---------|-----------|---------|
| `SandboxException` | 200 | 返回 `ExecuteResponse` 含 `status` 和 `errorMessage` |
| `MethodArgumentNotValidException` | 400 | 返回参数校验错误 Map |
| `IllegalArgumentException` | 400 | 返回错误消息 |
| `Exception` | 500 | 返回"系统内部错误" |

> 注：`CompileException` 和 `DangerousCodeException` 作为 `SandboxException` 子类，由统一的 `SandboxException` handler 处理，返回 HTTP 200 + 业务状态码。

---

## 5. Controller 实现

### 5.1 ExecuteController

```java
@Slf4j
@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
public class ExecuteController {

    private final ExecutionService executionService;

    @PostMapping("/single")
    public Result<SingleExecuteResponse> executeSingle(
            @Valid @RequestBody SingleExecuteRequest request) {
        SingleExecuteResponse response = executionService.executeSingle(request);
        return Result.success(response);
    }

    @PostMapping("/batch")
    public Result<BatchExecuteResponse> executeBatch(
            @Valid @RequestBody BatchExecuteRequest request) {
        BatchExecuteResponse response = executionService.executeBatch(request);
        return Result.success(response);
    }

    @GetMapping("/languages")
    public Result<List<ExecutionService.LanguageInfo>> getSupportedLanguages() {
        return Result.success(executionService.getSupportedLanguages());
    }
}
```

### 5.2 HealthController

```java
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping() {
        return Result.success(healthService.ping());
    }

    @GetMapping
    public Result<Map<String, Object>> health() {
        return Result.success(healthService.getHealthInfo());
    }

    @GetMapping("/liveness")
    public Result<Map<String, Object>> liveness() {
        return Result.success(Map.of("status", "UP", "probe", "liveness"));
    }

    @GetMapping("/readiness")
    public Result<Map<String, Object>> readiness() {
        // 检查 Docker 是否可用
        ...
    }
}
```

---

## 6. 下一步

下一篇文档将详细介绍 **Docker 执行器**的核心实现，包括：
- Docker 容器的创建与管理
- 容器池化复用
- 编译与执行流程
- 结果采集与解析

详见 [03-DOCKER-EXECUTOR.md](03-DOCKER-EXECUTOR.md)
