# OJ 代码执行沙箱服务 - 核心代码实现

## 1. 概述

本文档汇总核心代码实现，包括完整的执行流程、服务层、异常处理和配置。

> 2026-02 说明：本文件中部分 `CacheService` 相关代码片段属于历史设计示例，不代表当前输入数据缓存实现。当前实现请以 `InputDataServiceImpl` 与文档 `06-CACHE-OSS.md` 为准。

---

## 2. 执行服务实现

### 2.1 ExecutionService 接口

```java
package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.model.request.BatchExecuteRequest;
import com.github.ezzziy.codesandbox.model.request.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.response.BatchExecutionResult;
import com.github.ezzziy.codesandbox.model.response.ExecutionResult;

public interface ExecutionService {

    /**
     * 执行单个测试用例
     */
    ExecutionResult execute(ExecuteRequest request);

    /**
     * 批量执行多个测试用例
     */
    BatchExecutionResult batchExecute(BatchExecuteRequest request);
}
```

### 2.2 ExecutionServiceImpl 完整实现

```java
package com.github.ezzziy.codesandbox.service.impl;

import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.exception.SandboxException;
import com.github.ezzziy.codesandbox.executor.CodeExecutor;
import com.github.ezzziy.codesandbox.executor.strategy.LanguageStrategy;
import com.github.ezzziy.codesandbox.executor.strategy.LanguageStrategyFactory;
import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.request.BatchExecuteRequest;
import com.github.ezzziy.codesandbox.model.request.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.response.*;
import com.github.ezzziy.codesandbox.security.DangerousCodeScanner;
import com.github.ezzziy.codesandbox.security.SecurityAuditLogger;
import com.github.ezzziy.codesandbox.service.CacheService;
import com.github.ezzziy.codesandbox.service.ExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    private final CodeExecutor codeExecutor;
    private final CacheService cacheService;
    private final LanguageStrategyFactory strategyFactory;
    private final DangerousCodeScanner codeScanner;
    private final SecurityAuditLogger auditLogger;
    private final ExecutionConfig executionConfig;

    // 用于批量执行的线程池
    private final ExecutorService batchExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
    );

    @Override
    public ExecutionResult execute(ExecuteRequest request) {
        String executionId = generateExecutionId();
        long startTime = System.currentTimeMillis();

        try {
            // 1. 记录审计日志
            auditLogger.logExecutionRequest(executionId, request.getLanguage(),
                    "internal", request.getCode().length());

            // 2. 参数校验和规范化
            validateAndNormalize(request);

            // 3. 安全扫描
            codeScanner.scan(request.getLanguage(), request.getCode());

            // 4. 获取语言策略
            LanguageStrategy strategy = strategyFactory.getStrategy(
                    request.getLanguage(),
                    request.getLanguageVersion()
            );

            // 5. 获取输入数据
            String inputData = cacheService.getInputData(request.getInputDataKey());

            // 6. 构建执行上下文
            ExecutionContext context = ExecutionContext.builder()
                    .executionId(executionId)
                    .language(request.getLanguage())
                    .languageVersion(request.getLanguageVersion())
                    .code(request.getCode())
                    .inputData(inputData)
                    .timeLimit(request.getTimeLimit())
                    .memoryLimit(request.getMemoryLimit())
                    .enableNetwork(request.getEnableNetwork())
                    .build();

            // 7. 执行代码
            ExecutionResult result = codeExecutor.execute(context);

            // 8. 记录完成日志
            auditLogger.logExecutionCompleted(executionId,
                    result.getStatus().name(),
                    System.currentTimeMillis() - startTime);

            return result;

        } catch (SandboxException e) {
            log.error("执行失败: executionId={}, error={}", executionId, e.getMessage());
            auditLogger.logSecurityViolation(executionId, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("系统错误: executionId={}", executionId, e);
            return ExecutionResult.builder()
                    .status(ExecutionStatus.SYSTEM_ERROR)
                    .errorMessage(e.getMessage())
                    .executionId(executionId)
                    .totalTime(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public BatchExecutionResult batchExecute(BatchExecuteRequest request) {
        String batchId = generateExecutionId();
        long startTime = System.currentTimeMillis();

        try {
            // 1. 参数校验
            validateBatchRequest(request);

            // 2. 安全扫描
            codeScanner.scan(request.getLanguage(), request.getCode());

            // 3. 获取语言策略
            LanguageStrategy strategy = strategyFactory.getStrategy(
                    request.getLanguage(),
                    request.getLanguageVersion()
            );

            // 4. 先编译一次（编译型语言）
            CompileResult compileResult = null;
            String compiledArtifactPath = null;

            if (strategy.needsCompilation()) {
                // 单独编译，获取编译产物
                compileResult = compileCode(request, strategy);
                if (!compileResult.getSuccess()) {
                    return BatchExecutionResult.builder()
                            .compileResult(compileResult)
                            .results(List.of())
                            .summary(ExecutionSummary.builder()
                                    .total(request.getTestCases().size())
                                    .accepted(0)
                                    .failed(request.getTestCases().size())
                                    .totalTime(System.currentTimeMillis() - startTime)
                                    .build())
                            .executionId(batchId)
                            .build();
                }
            }

            // 5. 执行每个测试用例
            List<CaseResult> results = new ArrayList<>();
            int accepted = 0;
            int failed = 0;

            for (var testCase : request.getTestCases()) {
                try {
                    String inputData = cacheService.getInputData(testCase.getInputDataKey());

                    ExecutionContext context = ExecutionContext.builder()
                            .executionId(batchId + "-" + testCase.getCaseId())
                            .language(request.getLanguage())
                            .languageVersion(request.getLanguageVersion())
                            .code(request.getCode())
                            .inputData(inputData)
                            .timeLimit(request.getTimeLimit())
                            .memoryLimit(request.getMemoryLimit())
                            .enableNetwork(false)
                            .build();

                    ExecutionResult execResult = codeExecutor.execute(context);

                    CaseResult caseResult = CaseResult.builder()
                            .caseId(testCase.getCaseId())
                            .status(execResult.getStatus())
                            .runResult(execResult.getRunResult())
                            .build();

                    results.add(caseResult);

                    if (execResult.getStatus() == ExecutionStatus.ACCEPTED) {
                        accepted++;
                    } else {
                        failed++;
                        // 是否遇到失败就停止
                        if (Boolean.TRUE.equals(request.getStopOnFirstFailure())) {
                            break;
                        }
                    }

                } catch (Exception e) {
                    log.error("测试用例执行失败: caseId={}", testCase.getCaseId(), e);
                    results.add(CaseResult.builder()
                            .caseId(testCase.getCaseId())
                            .status(ExecutionStatus.SYSTEM_ERROR)
                            .build());
                    failed++;

                    if (Boolean.TRUE.equals(request.getStopOnFirstFailure())) {
                        break;
                    }
                }
            }

            return BatchExecutionResult.builder()
                    .compileResult(compileResult)
                    .results(results)
                    .summary(ExecutionSummary.builder()
                            .total(request.getTestCases().size())
                            .accepted(accepted)
                            .failed(failed)
                            .totalTime(System.currentTimeMillis() - startTime)
                            .build())
                    .executionId(batchId)
                    .build();

        } catch (Exception e) {
            log.error("批量执行失败: batchId={}", batchId, e);
            throw new SandboxException("批量执行失败: " + e.getMessage());
        }
    }

    /**
     * 参数校验和规范化
     */
    private void validateAndNormalize(ExecuteRequest request) {
        // 设置默认值
        if (request.getTimeLimit() == null) {
            request.setTimeLimit(executionConfig.getDefaultTimeLimit());
        }
        if (request.getMemoryLimit() == null) {
            request.setMemoryLimit(executionConfig.getDefaultMemoryLimit());
        }
        if (request.getEnableNetwork() == null) {
            request.setEnableNetwork(false);
        }

        // 限制最大值
        request.setTimeLimit(Math.min(request.getTimeLimit(), executionConfig.getMaxTimeLimit()));
        request.setMemoryLimit(Math.min(request.getMemoryLimit(), executionConfig.getMaxMemoryLimit()));

        // 检查代码长度
        if (request.getCode().length() > 65536) {
            throw new SandboxException("代码长度超过限制（64KB）");
        }
    }

    /**
     * 批量请求校验
     */
    private void validateBatchRequest(BatchExecuteRequest request) {
        if (request.getTestCases().size() > 100) {
            throw new SandboxException("测试用例数量超过限制（100）");
        }

        // 复用单个请求的校验逻辑
        ExecuteRequest singleRequest = ExecuteRequest.builder()
                .language(request.getLanguage())
                .languageVersion(request.getLanguageVersion())
                .code(request.getCode())
                .timeLimit(request.getTimeLimit())
                .memoryLimit(request.getMemoryLimit())
                .build();
        validateAndNormalize(singleRequest);

        // 回写规范化后的值
        request.setTimeLimit(singleRequest.getTimeLimit());
        request.setMemoryLimit(singleRequest.getMemoryLimit());
    }

    /**
     * 编译代码（用于批量执行）
     */
    private CompileResult compileCode(BatchExecuteRequest request, LanguageStrategy strategy) {
        ExecutionContext context = ExecutionContext.builder()
                .executionId(generateExecutionId())
                .language(request.getLanguage())
                .languageVersion(request.getLanguageVersion())
                .code(request.getCode())
                .inputData("")
                .timeLimit(request.getTimeLimit())
                .memoryLimit(request.getMemoryLimit())
                .enableNetwork(false)
                .build();

        // 仅编译
        ExecutionResult result = codeExecutor.execute(context);
        return result.getCompileResult();
    }

    /**
     * 生成执行 ID
     */
    private String generateExecutionId() {
        return "exec-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
```

---

## 3. 异常体系

### 3.1 基础异常类

```java
package com.github.ezzziy.codesandbox.exception;

import lombok.Getter;

/**
 * 沙箱基础异常
 */
@Getter
public class SandboxException extends RuntimeException {
    
    private final int code;

    public SandboxException(String message) {
        super(message);
        this.code = 50005;
    }

    public SandboxException(int code, String message) {
        super(message);
        this.code = code;
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
        this.code = 50005;
    }
}
```

### 3.2 具体异常类

```java
package com.github.ezzziy.codesandbox.exception;

/**
 * 编译异常
 */
@Getter
public class CompileException extends SandboxException {
    
    private final String compileOutput;

    public CompileException(String message, String compileOutput) {
        super(40101, message);
        this.compileOutput = compileOutput;
    }
}

/**
 * 不支持的语言异常
 */
@Getter
public class UnsupportedLanguageException extends SandboxException {
    
    private final List<String> supportedLanguages;

    public UnsupportedLanguageException(String message, List<String> supportedLanguages) {
        super(40002, message);
        this.supportedLanguages = supportedLanguages;
    }
}

/**
 * 危险代码异常
 */
public class DangerousCodeException extends SandboxException {
    
    public DangerousCodeException(String reason) {
        super(40003, "检测到危险代码: " + reason);
    }
}

/**
 * 容器异常
 */
public class ContainerException extends SandboxException {
    
    public ContainerException(String message) {
        super(50003, message);
    }

    public ContainerException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 缓存异常
 */
public class CacheException extends SandboxException {
    
    public CacheException(String message) {
        super(50006, message);
    }

    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * OSS 异常
 */
public class OSSException extends SandboxException {
    
    public OSSException(String message) {
        super(50002, message);
    }

    public OSSException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * 超时异常
 */
public class TimeoutException extends SandboxException {
    
    public TimeoutException(long timeout) {
        super(40801, "执行超时: " + timeout + "ms");
    }
}

/**
 * 内存超限异常
 */
public class MemoryLimitException extends SandboxException {
    
    public MemoryLimitException(long used, long limit) {
        super(40802, String.format("内存超限: used=%dKB, limit=%dKB", used, limit));
    }
}
```

### 3.3 全局异常处理器

```java
package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.response.CompileResult;
import com.github.ezzziy.codesandbox.model.response.ExecutionResult;
import com.github.ezzziy.codesandbox.model.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

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
     * 不支持的语言异常
     */
    @ExceptionHandler(UnsupportedLanguageException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Map<String, Object>> handleUnsupportedLanguage(UnsupportedLanguageException e) {
        log.warn("不支持的语言: {}", e.getMessage());
        return Result.<Map<String, Object>>builder()
                .code(e.getCode())
                .message(e.getMessage())
                .data(Map.of("supportedLanguages", e.getSupportedLanguages()))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 危险代码异常
     */
    @ExceptionHandler(DangerousCodeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleDangerousCode(DangerousCodeException e) {
        log.warn("危险代码: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 编译异常 - 返回正常结果，status 为 COMPILE_ERROR
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
     * 容器异常
     */
    @ExceptionHandler(ContainerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleContainerException(ContainerException e) {
        log.error("容器异常: {}", e.getMessage());
        return Result.error(e.getCode(), "容器执行失败");
    }

    /**
     * OSS 异常
     */
    @ExceptionHandler(OSSException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOSSException(OSSException e) {
        log.error("OSS 异常: {}", e.getMessage());
        return Result.error(e.getCode(), "获取测试数据失败");
    }

    /**
     * 沙箱通用异常
     */
    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<Result<Void>> handleSandboxException(SandboxException e) {
        log.error("沙箱异常: code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus status = e.getCode() >= 50000
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 未知异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(50005, "系统内部错误，请稍后重试");
    }
}
```

---

## 4. 枚举定义

### 4.1 ExecutionStatus

```java
package com.github.ezzziy.codesandbox.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 执行状态枚举
 */
@Getter
@AllArgsConstructor
public enum ExecutionStatus {
    
    // 成功
    ACCEPTED("AC", "执行成功", "程序正常执行完毕"),
    
    // 编译相关
    COMPILE_ERROR("CE", "编译错误", "代码编译失败"),
    
    // 运行时错误
    RUNTIME_ERROR("RE", "运行时错误", "程序运行时发生错误"),
    TIME_LIMIT_EXCEEDED("TLE", "超时", "程序运行超过时间限制"),
    MEMORY_LIMIT_EXCEEDED("MLE", "内存超限", "程序使用内存超过限制"),
    OUTPUT_LIMIT_EXCEEDED("OLE", "输出超限", "程序输出超过限制"),
    
    // 安全相关
    SECURITY_VIOLATION("SV", "安全违规", "检测到危险代码或操作"),
    
    // 系统错误
    SYSTEM_ERROR("SE", "系统错误", "沙箱系统内部错误"),
    
    // 其他
    UNKNOWN_ERROR("UE", "未知错误", "未知的执行错误");

    /**
     * 状态代码
     */
    private final String code;
    
    /**
     * 简短描述
     */
    private final String description;
    
    /**
     * 详细描述
     */
    private final String detail;

    /**
     * 是否为成功状态
     */
    public boolean isSuccess() {
        return this == ACCEPTED;
    }

    /**
     * 是否为系统错误
     */
    public boolean isSystemError() {
        return this == SYSTEM_ERROR || this == UNKNOWN_ERROR;
    }

    /**
     * 根据退出码判断状态
     */
    public static ExecutionStatus fromExitCode(int exitCode, long timeUsed, long timeLimit,
                                               long memoryUsed, long memoryLimit) {
        // 超时
        if (timeUsed >= timeLimit) {
            return TIME_LIMIT_EXCEEDED;
        }
        
        // 内存超限
        if (memoryUsed > memoryLimit * 1024) {  // memoryLimit 是 MB
            return MEMORY_LIMIT_EXCEEDED;
        }
        
        // 正常退出
        if (exitCode == 0) {
            return ACCEPTED;
        }
        
        // 被信号终止
        if (exitCode > 128) {
            int signal = exitCode - 128;
            return switch (signal) {
                case 9 -> MEMORY_LIMIT_EXCEEDED;  // SIGKILL (可能是 OOM)
                case 11 -> RUNTIME_ERROR;          // SIGSEGV
                case 6 -> RUNTIME_ERROR;           // SIGABRT
                case 8 -> RUNTIME_ERROR;           // SIGFPE
                default -> RUNTIME_ERROR;
            };
        }
        
        return RUNTIME_ERROR;
    }
}
```

### 4.2 LanguageEnum

```java
package com.github.ezzziy.codesandbox.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 编程语言枚举
 */
@Getter
@AllArgsConstructor
public enum LanguageEnum {
    
    C("c", "C", "gcc:11-bullseye", true),
    CPP("cpp", "C++", "gcc:11-bullseye", true),
    JAVA8("java8", "Java 8", "openjdk:8-jdk-slim", true),
    JAVA11("java11", "Java 11", "openjdk:11-jdk-slim", true),
    PYTHON3("python3", "Python 3", "python:3.10-slim", false),
    GOLANG("golang", "Go", "golang:1.20-alpine", true);

    /**
     * 语言 ID
     */
    private final String id;
    
    /**
     * 显示名称
     */
    private final String displayName;
    
    /**
     * Docker 镜像
     */
    private final String dockerImage;
    
    /**
     * 是否需要编译
     */
    private final boolean compiled;

    /**
     * 根据 ID 获取枚举
     */
    public static LanguageEnum fromId(String id) {
        for (LanguageEnum lang : values()) {
            if (lang.id.equalsIgnoreCase(id)) {
                return lang;
            }
        }
        return null;
    }
}
```

---

## 5. 应用启动类

```java
package com.github.ezzziy.codesandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties
public class CodeSandBoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeSandBoxApplication.class, args);
    }
}
```

---

## 6. 配置文件完整示例

### 6.1 application.yml

```yaml
server:
  port: 8090
  servlet:
    context-path: /

spring:
  application:
    name: code-sandbox
  
  # Jackson 配置
  jackson:
    default-property-inclusion: non_null
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: Asia/Shanghai
  
  # 文件上传配置（如果需要）
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB

# 日志配置
logging:
  level:
    root: INFO
    com.github.ezzziy.codesandbox: DEBUG
    com.github.dockerjava: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"

# 沙箱配置
sandbox:
  # Docker 配置
  docker:
    # Linux: unix:///var/run/docker.sock
    # Windows: tcp://localhost:2375 或 npipe:////./pipe/docker_engine
    host: unix:///var/run/docker.sock
    api-version: "1.41"
    connection-timeout: 30000
    read-timeout: 60000
  
  # 执行限制配置
  execution:
    default-time-limit: 5000      # 默认时间限制 (ms)
    default-memory-limit: 256     # 默认内存限制 (MB)
    max-time-limit: 30000         # 最大时间限制 (ms)
    max-memory-limit: 512         # 最大内存限制 (MB)
    max-output-size: 65536        # 最大输出大小 (64KB)
    max-process-count: 10         # 最大进程数
    compile-timeout: 30000        # 编译超时 (ms)
    container-start-timeout: 10   # 容器启动超时 (s)
  
  # 缓存配置
  cache:
    input-data:
      enabled: true
      base-path: /var/sandbox/cache/input
      max-size: 1000              # 最大缓存条目
      expire-hours: 24            # 过期时间 (小时)
      max-file-size-mb: 10        # 单文件最大大小 (MB)
  
  # OSS 配置
  oss:
    type: minio                   # minio 或 aliyun
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket: oj-testcases
    connect-timeout: 10000
    read-timeout: 30000
    max-retries: 3
  
  # 安全配置
  security:
    code-scan-enabled: true       # 是否启用危险代码扫描
    audit-log-enabled: true       # 是否启用审计日志

# Actuator 健康检查
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
```

### 6.2 application-dev.yml (开发环境)

```yaml
server:
  port: 8090

sandbox:
  docker:
    # Windows Docker Desktop
    host: npipe:////./pipe/docker_engine
  
  cache:
    input-data:
      base-path: ./cache/input
  
  oss:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin

logging:
  level:
    com.github.ezzziy.codesandbox: DEBUG
```

### 6.3 application-prod.yml (生产环境)

```yaml
server:
  port: 8090

sandbox:
  docker:
    host: unix:///var/run/docker.sock
  
  cache:
    input-data:
      base-path: /var/sandbox/cache/input
      max-size: 5000
      expire-hours: 48
  
  oss:
    type: aliyun
    endpoint: https://oss-cn-hangzhou.aliyuncs.com
    access-key: ${OSS_ACCESS_KEY}
    secret-key: ${OSS_SECRET_KEY}
    bucket: oj-testcases-prod

logging:
  level:
    root: INFO
    com.github.ezzziy.codesandbox: INFO
```

---

## 7. Maven 依赖完整配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.1</version>
        <relativePath/>
    </parent>

    <groupId>com.github.ezzziy</groupId>
    <artifactId>code-sand-box</artifactId>
    <version>1.0.0</version>
    <name>code-sand-box</name>
    <description>OJ 代码执行沙箱服务</description>

    <properties>
        <java.version>17</java.version>
        <docker-java.version>3.3.4</docker-java.version>
        <minio.version>8.5.7</minio.version>
        <caffeine.version>3.1.8</caffeine.version>
        <hutool.version>5.8.24</hutool.version>
        <commons-io.version>2.15.1</commons-io.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Docker Java Client -->
        <dependency>
            <groupId>com.github.docker-java</groupId>
            <artifactId>docker-java-core</artifactId>
            <version>${docker-java.version}</version>
        </dependency>
        <dependency>
            <groupId>com.github.docker-java</groupId>
            <artifactId>docker-java-transport-httpclient5</artifactId>
            <version>${docker-java.version}</version>
        </dependency>

        <!-- MinIO Client -->
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>${minio.version}</version>
        </dependency>

        <!-- Aliyun OSS (可选) -->
        <dependency>
            <groupId>com.aliyun.oss</groupId>
            <artifactId>aliyun-sdk-oss</artifactId>
            <version>3.17.4</version>
        </dependency>

        <!-- Caffeine Cache -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
            <version>${caffeine.version}</version>
        </dependency>

        <!-- Apache Commons -->
        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
            <version>${commons-io.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-compress</artifactId>
            <version>1.25.0</version>
        </dependency>

        <!-- Hutool 工具库 -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
            <version>${hutool.version}</version>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- OpenAPI / Swagger -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.3.0</version>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
```

---

## 8. 单元测试示例

### 8.1 ExecutionServiceTest

```java
package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.request.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.response.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ExecutionServiceTest {

    @Autowired
    private ExecutionService executionService;

    @Test
    void testExecuteCpp_APlusB() {
        String code = """
                #include <iostream>
                using namespace std;
                int main() {
                    int a, b;
                    cin >> a >> b;
                    cout << a + b << endl;
                    return 0;
                }
                """;

        ExecuteRequest request = ExecuteRequest.builder()
                .language("cpp")
                .code(code)
                .inputDataKey("test/aplusb/1.in")
                .timeLimit(1000)
                .memoryLimit(256)
                .build();

        ExecutionResult result = executionService.execute(request);

        assertEquals(ExecutionStatus.ACCEPTED, result.getStatus());
        assertNotNull(result.getRunResult());
        assertTrue(result.getRunResult().getTimeUsed() < 1000);
    }

    @Test
    void testExecutePython_HelloWorld() {
        String code = """
                print("Hello, World!")
                """;

        ExecuteRequest request = ExecuteRequest.builder()
                .language("python3")
                .code(code)
                .inputDataKey("test/empty.in")
                .timeLimit(5000)
                .memoryLimit(128)
                .build();

        ExecutionResult result = executionService.execute(request);

        assertEquals(ExecutionStatus.ACCEPTED, result.getStatus());
        assertEquals("Hello, World!\n", result.getRunResult().getStdout());
    }

    @Test
    void testExecuteJava_CompileError() {
        String code = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello"  // 缺少括号
                    }
                }
                """;

        ExecuteRequest request = ExecuteRequest.builder()
                .language("java11")
                .code(code)
                .inputDataKey("test/empty.in")
                .timeLimit(5000)
                .memoryLimit(256)
                .build();

        ExecutionResult result = executionService.execute(request);

        assertEquals(ExecutionStatus.COMPILE_ERROR, result.getStatus());
        assertNotNull(result.getCompileResult());
        assertFalse(result.getCompileResult().getSuccess());
    }

    @Test
    void testExecuteCpp_TimeLimit() {
        String code = """
                #include <iostream>
                using namespace std;
                int main() {
                    while(true) {}  // 死循环
                    return 0;
                }
                """;

        ExecuteRequest request = ExecuteRequest.builder()
                .language("cpp")
                .code(code)
                .inputDataKey("test/empty.in")
                .timeLimit(1000)
                .memoryLimit(256)
                .build();

        ExecutionResult result = executionService.execute(request);

        assertEquals(ExecutionStatus.TIME_LIMIT_EXCEEDED, result.getStatus());
    }

    @Test
    void testExecuteCpp_RuntimeError() {
        String code = """
                #include <iostream>
                using namespace std;
                int main() {
                    int *p = nullptr;
                    *p = 1;  // 空指针访问
                    return 0;
                }
                """;

        ExecuteRequest request = ExecuteRequest.builder()
                .language("cpp")
                .code(code)
                .inputDataKey("test/empty.in")
                .timeLimit(1000)
                .memoryLimit(256)
                .build();

        ExecutionResult result = executionService.execute(request);

        assertEquals(ExecutionStatus.RUNTIME_ERROR, result.getStatus());
    }
}
```

---

## 9. 部署脚本

### 9.1 Docker Compose

```yaml
# docker-compose.yml
version: '3.8'

services:
  code-sandbox:
    build: .
    container_name: code-sandbox
    ports:
      - "8090:8090"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./cache:/var/sandbox/cache
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - OSS_ACCESS_KEY=${OSS_ACCESS_KEY}
      - OSS_SECRET_KEY=${OSS_SECRET_KEY}
    depends_on:
      - minio
    restart: unless-stopped

  minio:
    image: minio/minio
    container_name: oj-minio
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data
    environment:
      - MINIO_ROOT_USER=minioadmin
      - MINIO_ROOT_PASSWORD=minioadmin
    command: server /data --console-address ":9001"
    restart: unless-stopped

volumes:
  minio_data:
```

### 9.2 Dockerfile

```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 安装 Docker CLI（用于与宿主机 Docker 通信）
RUN apk add --no-cache docker-cli

COPY --from=builder /app/target/*.jar app.jar

# 创建缓存目录
RUN mkdir -p /var/sandbox/cache/input

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 10. 总结

本文档系列完整介绍了 OJ 代码执行沙箱服务的设计与实现：

1. **[01-ARCHITECTURE.md](01-ARCHITECTURE.md)** - 系统架构与模块划分
2. **[02-API-DESIGN.md](02-API-DESIGN.md)** - API 设计与数据模型
3. **[03-DOCKER-EXECUTOR.md](03-DOCKER-EXECUTOR.md)** - Docker 执行器核心实现
4. **[04-LANGUAGE-SUPPORT.md](04-LANGUAGE-SUPPORT.md)** - 多语言支持配置
5. **[05-SECURITY.md](05-SECURITY.md)** - 安全机制实现
6. **[06-CACHE-OSS.md](06-CACHE-OSS.md)** - 缓存与 OSS 集成
7. **[07-CORE-IMPLEMENTATION.md](07-CORE-IMPLEMENTATION.md)** - 核心代码实现（本文档）

### 关键特性

- ✅ 支持 6 种编程语言
- ✅ Docker 容器隔离
- ✅ 完善的资源限制
- ✅ 危险代码检测
- ✅ 本地缓存机制
- ✅ 统一的错误处理
- ✅ 审计日志记录
- ✅ 健康检查接口

### 后续优化方向

- 支持更多编程语言（Rust、Kotlin、TypeScript）
- 实现容器池化，提高响应速度
- 增加分布式部署支持
- 实现更精确的资源监控
- 支持交互式程序（Special Judge）
