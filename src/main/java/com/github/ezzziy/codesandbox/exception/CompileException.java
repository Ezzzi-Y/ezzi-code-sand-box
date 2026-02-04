package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.model.enums.ExecutionStatus;

/**
 * 编译异常
 *
 * @author ezzziy
 */
public class CompileException extends SandboxException {

    public CompileException(String message) {
        super(ExecutionStatus.COMPILE_ERROR, message);
    }

    public CompileException(String message, String requestId) {
        super(ExecutionStatus.COMPILE_ERROR, message, requestId);
    }

    public CompileException(String message, Throwable cause) {
        super(ExecutionStatus.COMPILE_ERROR, message, cause);
    }
}
