package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;

/**
 * 执行超时异常
 *
 * @author ezzziy
 */
public class TimeLimitException extends SandboxException {

    /**
     * 超时限制（毫秒）
     */
    private final long timeLimit;

    public TimeLimitException(long timeLimit) {
        super(ExecutionStatus.TIME_LIMIT_EXCEEDED, 
              String.format("执行超时，限制: %d ms", timeLimit));
        this.timeLimit = timeLimit;
    }

    public TimeLimitException(long timeLimit, String requestId) {
        super(ExecutionStatus.TIME_LIMIT_EXCEEDED, 
              String.format("执行超时，限制: %d ms", timeLimit), requestId);
        this.timeLimit = timeLimit;
    }

    public long getTimeLimit() {
        return timeLimit;
    }
}
