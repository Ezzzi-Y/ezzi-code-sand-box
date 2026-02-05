package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.model.vo.ExecuteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 *
 * @author ezzziy
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理沙箱异常
     */
    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<ExecuteResponse> handleSandboxException(SandboxException e) {
        log.warn("沙箱异常: status={}, message={}, requestId={}", 
                e.getStatus(), e.getMessage(), e.getRequestId());

        ExecuteResponse response;
        switch (e.getStatus()) {
            case COMPILE_ERROR -> response = ExecuteResponse.compileError(e.getRequestId(), e.getMessage());
            case DANGEROUS_CODE -> response = ExecuteResponse.dangerousCode(e.getRequestId(), e.getMessage());
            default -> response = ExecuteResponse.systemError(e.getRequestId(), e.getMessage());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        Map<String, Object> response = new HashMap<>();
        response.put("code", HttpStatus.BAD_REQUEST.value());
        response.put("message", "参数校验失败");
        response.put("errors", errors);

        log.warn("参数校验失败: {}", errors);
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理非法参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", HttpStatus.BAD_REQUEST.value());
        response.put("message", e.getMessage());

        log.warn("非法参数: {}", e.getMessage());
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("系统异常", e);

        Map<String, Object> response = new HashMap<>();
        response.put("code", HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("message", "系统内部错误: " + e.getMessage());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
