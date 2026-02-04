package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.model.enums.ExecutionStatus;

/**
 * 运行时错误异常
 *
 * @author ezzziy
 */
public class RuntimeErrorException extends SandboxException {

    /**
     * 退出码
     */
    private final int exitCode;

    /**
     * 错误输出
     */
    private final String errorOutput;

    public RuntimeErrorException(int exitCode, String errorOutput) {
        super(ExecutionStatus.RUNTIME_ERROR,
              String.format("运行时错误，退出码: %d", exitCode));
        this.exitCode = exitCode;
        this.errorOutput = errorOutput;
    }

    public RuntimeErrorException(int exitCode, String errorOutput, String requestId) {
        super(ExecutionStatus.RUNTIME_ERROR,
              String.format("运行时错误，退出码: %d", exitCode), requestId);
        this.exitCode = exitCode;
        this.errorOutput = errorOutput;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getErrorOutput() {
        return errorOutput;
    }
}
