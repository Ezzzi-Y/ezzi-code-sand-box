package com.github.ezzziy.codesandbox.model.vo;

import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.dto.ExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量执行响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchExecuteResponse {

    private ExecutionStatus status;

    private String compileOutput;

    private String errorMessage;

    private List<ExecutionResult> results;

    private Summary summary;

    private Long totalTime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private Integer total;
        private Integer success;
        private Integer failed;
    }
}
