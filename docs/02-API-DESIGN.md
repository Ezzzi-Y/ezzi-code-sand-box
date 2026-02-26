# OJ 代码执行沙箱服务 - API 设计与数据模型

## 1. API 概览

### 1.1 接口列表

| 接口 | 方法 | 路径 | 描述 |
|------|------|------|------|
| 单次执行 | POST | `/execute/single` | 执行单个输入并返回结果 |
| 批量执行 | POST | `/execute/batch` | 批量执行多个测试用例 |
| 健康检查 | GET | `/health` | 服务健康状态 |
| 语言支持 | GET | `/languages` | 获取支持的语言列表 |

### 1.2 通用响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1706889600000,
  "traceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

---

## 2. 核心接口设计

### 2.1 单次执行接口

#### 请求

```http
POST /execute/single
Content-Type: application/json
X-Request-ID: uuid
```

```json
{
  "language": "cpp11",
  "code": "#include <iostream>\nusing namespace std;\nint main() {\n    int a, b;\n    cin >> a >> b;\n    cout << a + b << endl;\n    return 0;\n}",
  "input": "1 2",
  "timeLimit": 1000,
  "memoryLimit": 256
}
```

#### 请求参数说明

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `language` | String | ✅ | 编程语言：`c`, `cpp11`, `java8`, `java17`, `python3` |
| `code` | String | ✅ | 用户源代码（Base64 或明文） |
| `input` | String | ❌ | 直接输入内容，单次接口仅支持直接输入 |
| `timeLimit` | Integer | ❌ | 时间限制（毫秒），默认 5000，最大 30000 |
| `memoryLimit` | Integer | ❌ | 内存限制（MB），默认 256，最大 512 |

#### 响应

```json
{
  "code": 200,
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
  },
  "timestamp": 1706889600000,
  "traceId": "trace-uuid-456"
}
```

#### 响应参数说明

| 字段 | 类型 | 描述 |
|------|------|------|
| `status` | String | 执行状态（见状态码枚举）|
| `compileResult` | Object | 编译结果（解释型语言为 null）|
| `compileResult.success` | Boolean | 编译是否成功 |
| `compileResult.output` | String | 编译标准输出 |
| `compileResult.errorOutput` | String | 编译错误输出 |
| `compileResult.timeUsed` | Long | 编译耗时（毫秒）|
| `runResult` | Object | 运行结果（编译失败时为 null）|
| `runResult.stdout` | String | 程序标准输出 |
| `runResult.stderr` | String | 程序错误输出 |
| `runResult.exitCode` | Integer | 程序退出码 |
| `runResult.timeUsed` | Long | 运行耗时（毫秒）|
| `runResult.memoryUsed` | Long | 内存使用（KB）|
| `runResult.signal` | String | 终止信号（如 SIGKILL）|
| `executionId` | String | 执行唯一标识 |
| `totalTime` | Long | 总耗时（毫秒）|

---

### 2.2 批量执行接口

#### 请求

```http
POST /execute/batch
Content-Type: application/json
```

```json
{
  "language": "java17",
  "code": "import java.util.*;\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int a = sc.nextInt();\n        int b = sc.nextInt();\n        System.out.println(a + b);\n    }\n}",
  "inputDataUrl": "https://example.com/questions/1001/inputs.zip?signature=xxx",
  "timeLimit": 2000,
  "memoryLimit": 256
}
```

#### 请求参数说明

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `inputDataUrl` | String | ✅ | 预签名 URL，必须指向 zip 输入数据包 |

> zip 文件中仅包含输入文件，命名规则为 `1.in`、`2.in`、`3.in`...

#### 响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "compileResult": {
      "success": true,
      "output": "",
      "errorOutput": "",
      "timeUsed": 2500
    },
    "results": [
      {
        "caseId": "case-1",
        "status": "ACCEPTED",
        "runResult": {
          "stdout": "3\n",
          "stderr": "",
          "exitCode": 0,
          "timeUsed": 35,
          "memoryUsed": 15360
        }
      },
      {
        "caseId": "case-2",
        "status": "ACCEPTED",
        "runResult": {
          "stdout": "7\n",
          "stderr": "",
          "exitCode": 0,
          "timeUsed": 28,
          "memoryUsed": 15360
        }
      },
      {
        "caseId": "case-3",
        "status": "TIME_LIMIT_EXCEEDED",
        "runResult": {
          "stdout": "",
          "stderr": "",
          "exitCode": 137,
          "timeUsed": 2000,
          "memoryUsed": 15360
        }
      }
    ],
    "summary": {
      "total": 3,
      "accepted": 2,
      "failed": 1,
      "totalTime": 5600
    },
    "executionId": "batch-exec-uuid-789"
  },
  "timestamp": 1706889600000
}
```

---

### 2.3 输入数据缓存（批量 URL 模式）

- 批量执行时如果使用 `inputDataUrl`，服务会在每次执行前发起元数据查询。
- 使用 `ETag` 和 `Last-Modified` 与本地缓存元数据比对。
- 一致则使用本地缓存，不一致则重新下载并覆盖。
- 不再提供手动触发缓存更新的 API。
        "message": "refreshed"
      },
      {
        "objectKey": "questions/1001/testcases/2.in",
        "success": true,
        "message": "refreshed"
      }
    ]
  }
}
```

---

### 2.4 健康检查接口

#### 请求

```http
GET /health
```

#### 响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "status": "UP",
    "components": {
      "docker": {
        "status": "UP",
        "version": "20.10.21",
        "apiVersion": "1.41"
      },
      "oss": {
        "status": "UP",
        "type": "minio",
        "bucket": "oj-testcases"
      },
      "cache": {
        "status": "UP",
        "size": 156,
        "maxSize": 1000
      }
    },
    "languages": ["c", "cpp", "java8", "java11", "python3", "golang"],
    "uptime": 86400000
  }
}
```

---

### 2.5 语言支持接口

#### 请求

```http
GET /languages
```

#### 响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "languages": [
      {
        "id": "c",
        "name": "C",
        "version": "GCC 11",
        "extension": ".c",
        "compilable": true,
        "image": "gcc:11-bullseye",
        "available": true
      },
      {
        "id": "cpp",
        "name": "C++",
        "version": "G++ 11 (C++11)",
        "extension": ".cpp",
        "compilable": true,
        "image": "gcc:11-bullseye",
        "available": true
      },
      {
        "id": "java8",
        "name": "Java",
        "version": "OpenJDK 8",
        "extension": ".java",
        "compilable": true,
        "image": "openjdk:8-jdk-slim",
        "available": true
      },
      {
        "id": "java11",
        "name": "Java",
        "version": "OpenJDK 11",
        "extension": ".java",
        "compilable": true,
        "image": "openjdk:11-jdk-slim",
        "available": true
      },
      {
        "id": "python3",
        "name": "Python",
        "version": "Python 3.10",
        "extension": ".py",
        "compilable": false,
        "image": "python:3.10-slim",
        "available": true
      },
      {
        "id": "golang",
        "name": "Go",
        "version": "Go 1.20",
        "extension": ".go",
        "compilable": true,
        "image": "golang:1.20-alpine",
        "available": true
      }
    ]
  }
}
```

---

## 3. 数据模型

### 3.1 执行状态枚举

```java
public enum ExecutionStatus {
    
    // 成功状态
    ACCEPTED("AC", "执行成功"),
    
    // 编译相关
    COMPILE_ERROR("CE", "编译错误"),
    
    // 运行时错误
    RUNTIME_ERROR("RE", "运行时错误"),
    TIME_LIMIT_EXCEEDED("TLE", "超时"),
    MEMORY_LIMIT_EXCEEDED("MLE", "内存超限"),
    OUTPUT_LIMIT_EXCEEDED("OLE", "输出超限"),
    
    // 系统错误
    SYSTEM_ERROR("SE", "系统错误"),
    
    // 其他
    UNKNOWN_ERROR("UE", "未知错误");
    
    private final String code;
    private final String description;
}
```

### 3.2 请求模型

```java
/**
 * 代码执行请求
 */
@Data
@Builder
public class ExecuteRequest {
    
    /**
     * 编程语言
     */
    @NotBlank(message = "语言不能为空")
    private String language;
    
    /**
     * 语言版本（可选）
     */
    private String languageVersion;
    
    /**
     * 用户代码
     */
    @NotBlank(message = "代码不能为空")
    @Size(max = 65536, message = "代码长度不能超过64KB")
    private String code;
    
    /**
     * OSS 输入数据 Key
     */
    @NotBlank(message = "输入数据Key不能为空")
    private String inputDataKey;
    
    /**
     * 时间限制（毫秒）
     */
    @Min(value = 100, message = "时间限制最小100ms")
    @Max(value = 30000, message = "时间限制最大30s")
    private Integer timeLimit = 5000;
    
    /**
     * 内存限制（MB）
     */
    @Min(value = 16, message = "内存限制最小16MB")
    @Max(value = 512, message = "内存限制最大512MB")
    private Integer memoryLimit = 256;
    
    /**
     * 是否启用网络（默认禁用）
     */
    private Boolean enableNetwork = false;
}
```

### 3.3 响应模型

```java
/**
 * 执行结果
 */
@Data
@Builder
public class ExecutionResult {
    
    /**
     * 执行状态
     */
    private ExecutionStatus status;
    
    /**
     * 编译结果（解释型语言为 null）
     */
    private CompileResult compileResult;
    
    /**
     * 运行结果（编译失败时为 null）
     */
    private RunResult runResult;
    
    /**
     * 执行唯一标识
     */
    private String executionId;
    
    /**
     * 总耗时（毫秒）
     */
    private Long totalTime;
    
    /**
     * 错误信息（系统错误时填充）
     */
    private String errorMessage;
}

/**
 * 编译结果
 */
@Data
@Builder
public class CompileResult {
    
    /**
     * 编译是否成功
     */
    private Boolean success;
    
    /**
     * 标准输出
     */
    private String output;
    
    /**
     * 错误输出
     */
    private String errorOutput;
    
    /**
     * 编译耗时（毫秒）
     */
    private Long timeUsed;
}

/**
 * 运行结果
 */
@Data
@Builder
public class RunResult {
    
    /**
     * 标准输出
     */
    private String stdout;
    
    /**
     * 错误输出
     */
    private String stderr;
    
    /**
     * 退出码
     */
    private Integer exitCode;
    
    /**
     * 运行耗时（毫秒）
     */
    private Long timeUsed;
    
    /**
     * 内存使用（KB）
     */
    private Long memoryUsed;
    
    /**
     * 终止信号（如 SIGKILL、SIGSEGV）
     */
    private String signal;
}
```

### 3.4 批量执行模型

```java
/**
 * 批量执行请求
 */
@Data
@Builder
public class BatchExecuteRequest {
    
    @NotBlank
    private String language;
    
    private String languageVersion;
    
    @NotBlank
    @Size(max = 65536)
    private String code;
    
    @NotEmpty(message = "测试用例不能为空")
    @Size(max = 100, message = "测试用例最多100个")
    private List<TestCase> testCases;
    
    private Integer timeLimit = 5000;
    private Integer memoryLimit = 256;
    private Boolean stopOnFirstFailure = false;
}

/**
 * 测试用例
 */
@Data
public class TestCase {
    
    @NotBlank
    private String inputDataKey;
    
    @NotBlank
    private String caseId;
}

/**
 * 批量执行结果
 */
@Data
@Builder
public class BatchExecutionResult {
    
    private CompileResult compileResult;
    private List<CaseResult> results;
    private ExecutionSummary summary;
    private String executionId;
}

/**
 * 单个用例执行结果
 */
@Data
@Builder
public class CaseResult {
    
    private String caseId;
    private ExecutionStatus status;
    private RunResult runResult;
}

/**
 * 执行汇总
 */
@Data
@Builder
public class ExecutionSummary {
    
    private Integer total;
    private Integer accepted;
    private Integer failed;
    private Long totalTime;
}
```

---

## 4. 错误码定义

### 4.1 业务错误码

| 错误码 | HTTP 状态 | 描述 |
|--------|-----------|------|
| 200 | 200 | 成功 |
| 40001 | 400 | 参数校验失败 |
| 40002 | 400 | 不支持的语言 |
| 40003 | 400 | 代码长度超限 |
| 40004 | 400 | 时间/内存限制超出范围 |
| 40401 | 404 | 输入数据不存在 |
| 50001 | 500 | Docker 服务不可用 |
| 50002 | 500 | OSS 服务不可用 |
| 50003 | 500 | 容器创建失败 |
| 50004 | 500 | 执行超时（系统级）|
| 50005 | 500 | 未知系统错误 |

### 4.2 错误响应示例

```json
{
  "code": 40002,
  "message": "不支持的编程语言: rust",
  "data": null,
  "timestamp": 1706889600000,
  "traceId": "trace-uuid-789",
  "details": {
    "supportedLanguages": ["c", "cpp", "java8", "java11", "python3", "golang"]
  }
}
```

---

## 5. Controller 实现

### 5.1 ExecuteController

```java
package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.model.request.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.request.BatchExecuteRequest;
import com.github.ezzziy.codesandbox.model.response.ExecutionResult;
import com.github.ezzziy.codesandbox.model.response.BatchExecutionResult;
import com.github.ezzziy.codesandbox.model.response.Result;
import com.github.ezzziy.codesandbox.service.ExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
@Tag(name = "代码执行", description = "代码执行相关接口")
public class ExecuteController {

    private final ExecutionService executionService;

    /**
     * 执行单个测试用例
     */
    @PostMapping
    @Operation(summary = "执行代码", description = "在沙箱中执行用户代码")
    public Result<ExecutionResult> execute(
            @Valid @RequestBody ExecuteRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        
        log.info("收到执行请求: language={}, inputKey={}, requestId={}", 
                request.getLanguage(), request.getInputDataKey(), requestId);
        
        ExecutionResult result = executionService.execute(request);
        
        log.info("执行完成: status={}, time={}ms, requestId={}", 
                result.getStatus(), result.getTotalTime(), requestId);
        
        return Result.success(result);
    }

    /**
     * 批量执行多个测试用例
     */
    @PostMapping("/batch")
    @Operation(summary = "批量执行", description = "批量执行多个测试用例")
    public Result<BatchExecutionResult> batchExecute(
            @Valid @RequestBody BatchExecuteRequest request,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        
        log.info("收到批量执行请求: language={}, caseCount={}, requestId={}", 
                request.getLanguage(), request.getTestCases().size(), requestId);
        
        BatchExecutionResult result = executionService.batchExecute(request);
        
        log.info("批量执行完成: accepted={}/{}, totalTime={}ms, requestId={}", 
                result.getSummary().getAccepted(), 
                result.getSummary().getTotal(),
                result.getSummary().getTotalTime(), 
                requestId);
        
        return Result.success(result);
    }
}
```

### 5.2 CacheController

```java
package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.model.request.CacheRefreshRequest;
import com.github.ezzziy.codesandbox.model.response.CacheRefreshResult;
import com.github.ezzziy.codesandbox.model.response.Result;
import com.github.ezzziy.codesandbox.service.CacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/cache")
@RequiredArgsConstructor
@Tag(name = "缓存管理", description = "输入数据缓存管理接口")
public class CacheController {

    private final CacheService cacheService;

    /**
     * 刷新指定缓存
     */
    @PostMapping("/refresh")
    @Operation(summary = "刷新缓存", description = "删除并重新下载指定的输入数据缓存")
    public Result<CacheRefreshResult> refresh(@Valid @RequestBody CacheRefreshRequest request) {
        log.info("收到缓存刷新请求: keys={}", request.getObjectKeys());
        CacheRefreshResult result = cacheService.refresh(request.getObjectKeys(), request.getForceDownload());
        return Result.success(result);
    }

    /**
     * 清空所有缓存
     */
    @DeleteMapping("/clear")
    @Operation(summary = "清空缓存", description = "清空所有输入数据缓存")
    public Result<Void> clearAll() {
        log.info("收到清空缓存请求");
        cacheService.clearAll();
        return Result.success();
    }

    /**
     * 获取缓存状态
     */
    @GetMapping("/status")
    @Operation(summary = "缓存状态", description = "获取当前缓存状态信息")
    public Result<CacheStatus> getStatus() {
        return Result.success(cacheService.getStatus());
    }
}
```

### 5.3 HealthController

```java
package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.model.response.HealthStatus;
import com.github.ezzziy.codesandbox.model.response.LanguageInfo;
import com.github.ezzziy.codesandbox.model.response.Result;
import com.github.ezzziy.codesandbox.service.HealthService;
import com.github.ezzziy.codesandbox.service.LanguageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("")
@RequiredArgsConstructor
@Tag(name = "系统", description = "系统状态相关接口")
public class HealthController {

    private final HealthService healthService;
    private final LanguageService languageService;

    /**
     * 健康检查
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查服务各组件的健康状态")
    public Result<HealthStatus> health() {
        return Result.success(healthService.check());
    }

    /**
     * 获取支持的语言列表
     */
    @GetMapping("/languages")
    @Operation(summary = "语言列表", description = "获取所有支持的编程语言及其配置")
    public Result<List<LanguageInfo>> languages() {
        return Result.success(languageService.getSupportedLanguages());
    }
}
```

---

## 6. 统一响应封装

```java
package com.github.ezzziy.codesandbox.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {
    
    private Integer code;
    private String message;
    private T data;
    private Long timestamp;
    private String traceId;
    
    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
    
    public static <T> Result<T> success() {
        return success(null);
    }
    
    public static <T> Result<T> error(int code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
```

---

## 7. 全局异常处理

```java
package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.model.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验异常
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(Exception e) {
        String message = "参数校验失败";
        if (e instanceof MethodArgumentNotValidException ex) {
            message = ex.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .findFirst()
                    .orElse(message);
        }
        log.warn("参数校验失败: {}", message);
        return Result.error(40001, message);
    }

    /**
     * 沙箱业务异常
     */
    @ExceptionHandler(SandboxException.class)
    public Result<Void> handleSandboxException(SandboxException e) {
        log.error("沙箱异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 编译异常
     */
    @ExceptionHandler(CompileException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<ExecutionResult> handleCompileException(CompileException e) {
        log.warn("编译失败: {}", e.getMessage());
        return Result.success(ExecutionResult.builder()
                .status(ExecutionStatus.COMPILE_ERROR)
                .compileResult(CompileResult.builder()
                        .success(false)
                        .errorOutput(e.getCompileOutput())
                        .build())
                .build());
    }

    /**
     * 未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(50005, "系统内部错误");
    }
}
```

---

## 8. 下一步

下一篇文档将详细介绍 **Docker 执行器**的核心实现，包括：
- Docker 容器的创建与管理
- 资源限制的配置
- 编译与执行流程
- 结果采集与解析

详见 [03-DOCKER-EXECUTOR.md](03-DOCKER-EXECUTOR.md)
