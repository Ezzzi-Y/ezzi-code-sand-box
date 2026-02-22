package com.github.ezzziy.codesandbox.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量执行请求（多测试用例）
 * <p>
 * 仅支持通过一个 ZIP 预签名 URL 传入测试用例，不支持直接传入输入列表。
 * ZIP 结构要求：1.in,1.out,2.in,2.out...
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchExecuteRequest {

    private String requestId;

    @NotBlank(message = "代码不能为空")
    @Size(max = 65536, message = "代码长度不能超过64KB")
    private String code;

    @NotBlank(message = "编程语言不能为空")
    private String language;

    /**
        * 预签名 URL（ZIP，必须包含 1.in,1.out,2.in,2.out...）
     */
        @NotBlank(message = "批量执行必须提供 inputDataUrl")
    private String inputDataUrl;

    /**
     * 时间限制（毫秒）
     */
    private Integer timeLimit;

    /**
     * 内存限制（MB）
     */
    private Integer memoryLimit;
}
