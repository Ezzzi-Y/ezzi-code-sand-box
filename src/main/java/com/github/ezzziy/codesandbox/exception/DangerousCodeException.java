package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.model.enums.ExecutionStatus;

/**
 * 危险代码异常
 *
 * @author ezzziy
 */
public class DangerousCodeException extends SandboxException {

    /**
     * 检测到的危险模式
     */
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

    public String getPattern() {
        return pattern;
    }
}
