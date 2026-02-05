package com.github.ezzziy.codesandbox.model.vo;

import com.github.ezzziy.codesandbox.model.dto.ExecutionResult;
import com.github.ezzziy.codesandbox.model.dto.ExecutionTimeStats;
import com.github.ezzziy.codesandbox.model.enums.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码执行响应 DTO
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResponse {

    /**
     * 请求 ID
     */
    private String requestId;

    /**
     * 整体执行状态
     */
    private ExecutionStatus status;

    /**
     * 编译信息（编译器输出，包括警告、错误等）
     */
    private String compileOutput;

    /**
     * 系统错误信息（沙箱内部错误）
     */
    private String errorMessage;

    /**
     * 各输入的执行结果列表
     */
    private List<ExecutionResult> results;

    /**
     * 执行时间统计
     */
    private ExecutionTimeStats timeStats;

    /**
     * 构建成功响应
     */
    public static ExecuteResponse success(String requestId, String compileOutput, List<ExecutionResult> results) {
        return ExecuteResponse.builder()
                .requestId(requestId)
                .status(ExecutionStatus.SUCCESS)
                .compileOutput(compileOutput)
                .results(results)
                .build();
    }

    /**
     * 构建编译错误响应
     */
    public static ExecuteResponse compileError(String requestId, String compileOutput) {
        return ExecuteResponse.builder()
                .requestId(requestId)
                .status(ExecutionStatus.COMPILE_ERROR)
                .compileOutput(compileOutput)
                .build();
    }

    /**
     * 构建系统错误响应
     */
    public static ExecuteResponse systemError(String requestId, String error) {
        return ExecuteResponse.builder()
                .requestId(requestId)
                .status(ExecutionStatus.SYSTEM_ERROR)
                .errorMessage(error)
                .build();
    }

    /**
     * 构建危险代码响应
     */
    public static ExecuteResponse dangerousCode(String requestId, String reason) {
        return ExecuteResponse.builder()
                .requestId(requestId)
                .status(ExecutionStatus.DANGEROUS_CODE)
                .errorMessage(reason)
                .build();
    }
}
