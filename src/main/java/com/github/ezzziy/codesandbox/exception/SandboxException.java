package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import lombok.Getter;

/**
 * 沙箱异常基类
 *
 * @author ezzziy
 */
@Getter
public class SandboxException extends RuntimeException {

    /**
     * 对应的执行状态
     */
    private final ExecutionStatus status;

    /**
     * 请求 ID
     */
    private final String requestId;

    public SandboxException(String message) {
        super(message);
        this.status = ExecutionStatus.SYSTEM_ERROR;
        this.requestId = null;
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
        this.status = ExecutionStatus.SYSTEM_ERROR;
        this.requestId = null;
    }

    public SandboxException(ExecutionStatus status, String message) {
        super(message);
        this.status = status;
        this.requestId = null;
    }

    public SandboxException(ExecutionStatus status, String message, String requestId) {
        super(message);
        this.status = status;
        this.requestId = requestId;
    }

    public SandboxException(ExecutionStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.requestId = null;
    }
}
