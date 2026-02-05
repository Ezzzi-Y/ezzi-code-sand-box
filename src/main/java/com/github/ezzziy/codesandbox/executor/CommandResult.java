package com.github.ezzziy.codesandbox.executor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 容器内命令执行结果（内部使用）
 * <p>
 * 记录单次命令执行的完整结果信息，包括：
 * - 执行状态（成功/失败/超时）
 * - 标准输出和错误输出
 * - 执行时间（毫秒级精度，从容器内 /proc 获取）
 * - 内存使用（KB，从 /proc/self/status 的 VmRSS 获取）
 * </p>
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommandResult {

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 退出码
     */
    private int exitCode;

    /**
     * 标准输出
     */
    private String stdout;

    /**
     * 标准错误输出
     */
    private String stderr;

    /**
     * 执行时间（毫秒）
     * <p>
     * 精确到毫秒级，从容器内 date +%s%N 获取的纳秒级时间戳计算得出
     * </p>
     */
    private long executionTime;

    /**
     * 内存使用（KB）
     * <p>
     * 从容器内 /proc/self/status 的 VmRSS（Resident Set Size）字段获取，
     * 表示实际占用的物理内存大小（KB）
     * </p>
     */
    private long memoryUsage;

    /**
     * 是否超时
     */
    private boolean timeout;

    /**
     * 是否内存超限
     */
    private boolean memoryExceeded;

    /**
     * 是否输出超限
     */
    private boolean outputExceeded;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建成功结果
     */
    public static CommandResult success(String stdout, String stderr, 
                                         long executionTime, long memoryUsage) {
        return CommandResult.builder()
                .success(true)
                .exitCode(0)
                .stdout(stdout)
                .stderr(stderr)
                .executionTime(executionTime)
                .memoryUsage(memoryUsage)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static CommandResult failure(int exitCode, String stdout, String stderr,
                                         long executionTime, long memoryUsage) {
        return CommandResult.builder()
                .success(false)
                .exitCode(exitCode)
                .stdout(stdout)
                .stderr(stderr)
                .executionTime(executionTime)
                .memoryUsage(memoryUsage)
                .build();
    }

    /**
     * 创建超时结果
     */
    public static CommandResult timeout(long timeLimit) {
        return CommandResult.builder()
                .success(false)
                .timeout(true)
                .executionTime(timeLimit)
                .errorMessage("执行超时")
                .build();
    }

    /**
     * 创建内存超限结果
     */
    public static CommandResult memoryExceeded(long memoryUsage) {
        return CommandResult.builder()
                .success(false)
                .memoryExceeded(true)
                .memoryUsage(memoryUsage)
                .errorMessage("内存超限")
                .build();
    }

    /**
     * 创建错误结果
     */
    public static CommandResult error(String message) {
        return CommandResult.builder()
                .success(false)
                .exitCode(-1)
                .errorMessage(message)
                .build();
    }
}
