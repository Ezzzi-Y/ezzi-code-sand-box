package com.github.ezzziy.codesandbox.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码执行请求 DTO
 * <p>
 * 支持两种输入方式：
 * 1. 直接传入单个输入内容（input）- 无需缓存
 * 2. 传入预签名URL下载输入数据包（inputDataUrl）- 需要下载解压并缓存
 * </p>
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteRequest {

    /**
     * 请求 ID（用于追踪和日志，可选）
     */
    private String requestId;

    /**
     * 用户代码
     */
    @NotBlank(message = "代码不能为空")
    @Size(max = 65536, message = "代码长度不能超过64KB")
    private String code;

    /**
     * 编程语言
     */
    @NotBlank(message = "编程语言不能为空")
    private String language;

    // ==================== 输入方式一：直接传入（单个输入，无需缓存） ====================

    /**
     * 直接传入的输入数据（单个）
     * <p>
     * 与 inputDataId + inputDataUrl 二选一
     * </p>
     */
    private String input;

    // ==================== 输入方式二：URL下载（多个输入，自动缓存） ====================

    /**
     * 输入数据包的预签名 URL（MinIO/AliyunOSS）
     * <p>
     * ZIP 压缩包格式，内含 1.in, 2.in, 3.in... 文件
     * 系统会自动从 URL 中提取 ObjectKey 作为缓存标识
     * </p>
     */
    private String inputDataUrl;

    // ==================== 资源限制 ====================

    /**
     * 时间限制（毫秒）- 可选，覆盖默认配置
     */
    private Integer timeLimit;

    /**
     * 内存限制（MB）- 可选，覆盖默认配置
     */
    private Integer memoryLimit;

    /**
     * 判断是否为直接输入模式
     */
    public boolean isDirectInput() {
        return input != null;
    }

    /**
     * 判断是否为 URL 下载模式
     */
    public boolean isUrlInput() {
        return inputDataUrl != null && !inputDataUrl.isBlank();
    }
}
