package com.github.ezzziy.codesandbox.model.vo;

import com.github.ezzziy.codesandbox.model.dto.ExecutionResult;
import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码执行响应
 * <p>
 * 简洁的响应结构，包含执行状态和结果列表
 * </p>
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResponse {

    /**
     * 整体执行状态
     */
    private ExecutionStatus status;

    /**
     * 编译信息（编译器输出，包括警告、错误等）
     */
    private String compileOutput;

    /**
     * 系统错误信息（沙箱内部错误，如编译失败、超时等）
     */
    private String errorMessage;

    /**
     * 各输入的执行结果列表
     */
    private List<ExecutionResult> results;

    /**
     * 编译时间（毫秒）
     */
    private Long compileTime;

    /**
     * 运行时间（毫秒）- 所有输入执行的总时间
     */
    private Long runTime;

    /**
     * 总执行时间（毫秒）- 从请求开始到结束
     */
    private Long totalTime;
}
