package com.github.ezzziy.codesandbox.model.vo;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.dto.ExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次执行响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExecuteResponse {

    private ExecutionStatus status;

    private String compileOutput;

    private String errorMessage;

    private ExecutionResult result;

    private Long totalTime;
}
