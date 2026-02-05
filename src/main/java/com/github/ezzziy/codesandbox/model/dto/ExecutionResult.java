package com.github.ezzziy.codesandbox.model.dto;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次代码执行结果
 * <p>
 * 代表单个输入的执行结果
 * </p>
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {

    /**
     * 输入序号（从 1 开始）
     */
    private Integer index;

    /**
     * 执行状态
     */
    private ExecutionStatus status;

    /**
     * 代码执行输出（stdout，已去除末尾空白字符）
     */
    private String output;

    /**
     * 运行时错误输出（stderr）
     */
    private String errorOutput;

    /**
     * 执行时间（毫秒，不含编译时间）
     */
    private Long time;

    /**
     * 内存使用（KB，不含编译时内存）
     */
    private Long memory;

    /**
     * 退出码
     */
    private Integer exitCode;

    /**
     * 构建成功执行结果
     */
    public static ExecutionResult success(int index, String output, String errorOutput, long time, long memory) {
        return ExecutionResult.builder()
                .index(index)
                .status(ExecutionStatus.SUCCESS)
                .output(trimTrailingWhitespace(output))
                .errorOutput(errorOutput)
                .time(time)
                .memory(memory)
                .exitCode(0)
                .build();
    }

    /**
     * 构建超时结果
     */
    public static ExecutionResult timeout(int index, long timeLimit) {
        return ExecutionResult.builder()
                .index(index)
                .status(ExecutionStatus.TIME_LIMIT_EXCEEDED)
                .time(timeLimit)
                .memory(0L)
                .build();
    }

    /**
     * 构建内存超限结果
     */
    public static ExecutionResult memoryExceeded(int index, long time, long memory) {
        return ExecutionResult.builder()
                .index(index)
                .status(ExecutionStatus.MEMORY_LIMIT_EXCEEDED)
                .time(time)
                .memory(memory)
                .build();
    }

    /**
     * 构建运行时错误结果
     */
    public static ExecutionResult runtimeError(int index, String output, String errorOutput, long time, long memory, int exitCode) {
        return ExecutionResult.builder()
                .index(index)
                .status(ExecutionStatus.RUNTIME_ERROR)
                .output(trimTrailingWhitespace(output))
                .errorOutput(errorOutput)
                .time(time)
                .memory(memory)
                .exitCode(exitCode)
                .build();
    }

    /**
     * 去除字符串末尾的空白字符（空格和换行符）
     * <p>
     * 规则：去除最后一个有内容字符后面的所有空白字符
     * </p>
     */
    private static String trimTrailingWhitespace(String str) {
        if (str == null) {
            return null;
        }
        // 使用正则去除末尾的空白字符（包括空格、制表符、换行符等）
        return str.replaceAll("\\s+$", "");
    }
}
