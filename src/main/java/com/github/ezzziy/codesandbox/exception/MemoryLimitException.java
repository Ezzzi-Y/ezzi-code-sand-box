package com.github.ezzziy.codesandbox.exception;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;

/**
 * 内存超限异常
 *
 * @author ezzziy
 */
public class MemoryLimitException extends SandboxException {

    /**
     * 内存限制（MB）
     */
    private final long memoryLimit;

    /**
     * 实际使用内存（KB）
     */
    private final long actualMemory;

    public MemoryLimitException(long memoryLimit, long actualMemory) {
        super(ExecutionStatus.MEMORY_LIMIT_EXCEEDED,
              String.format("内存超限，限制: %d MB, 实际: %d KB", memoryLimit, actualMemory));
        this.memoryLimit = memoryLimit;
        this.actualMemory = actualMemory;
    }

    public MemoryLimitException(long memoryLimit, long actualMemory, String requestId) {
        super(ExecutionStatus.MEMORY_LIMIT_EXCEEDED,
              String.format("内存超限，限制: %d MB, 实际: %d KB", memoryLimit, actualMemory), requestId);
        this.memoryLimit = memoryLimit;
        this.actualMemory = actualMemory;
    }

    public long getMemoryLimit() {
        return memoryLimit;
    }

    public long getActualMemory() {
        return actualMemory;
    }
}
