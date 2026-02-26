package com.github.ezzziy.codesandbox.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量执行请求（多测试用例）
 * <p>
 * 仅支持通过预签名 URL 提供输入数据包。
 * URL 必须指向 zip 文件，且 zip 内仅包含输入文件（如 1.in、2.in）。
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
     * 预签名 URL（ZIP，按 1.in / 2.in ...）
     */
    @NotBlank(message = "inputDataUrl 不能为空，且必须是 zip 文件 URL")
    private String inputDataUrl;

    /**
     * 时间限制（毫秒）
     */
    private Integer timeLimit;

    /**
     * 内存限制（MB）
     */
    private Integer memoryLimit;

    public boolean isUrlInput() {
        return true;
    }
}
