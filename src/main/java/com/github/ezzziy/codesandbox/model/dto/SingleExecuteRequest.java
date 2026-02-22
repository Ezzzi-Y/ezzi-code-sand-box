package com.github.ezzziy.codesandbox.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单次执行请求（仅支持直接输入）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SingleExecuteRequest {

    private String requestId;

    @NotBlank(message = "代码不能为空")
    @Size(max = 65536, message = "代码长度不能超过64KB")
    private String code;

    @NotBlank(message = "编程语言不能为空")
    private String language;

    /**
     * 直接输入内容
     */
    private String input;

    /**
     * 时间限制（毫秒）
     */
    private Integer timeLimit;

    /**
     * 内存限制（MB）
     */
    private Integer memoryLimit;
}
