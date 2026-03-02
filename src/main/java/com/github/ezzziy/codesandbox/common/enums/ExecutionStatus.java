package com.github.ezzziy.codesandbox.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 代码执行状态枚举
 * <p>
 * 仅表示执行状态，不涉及结果评判逻辑
 * </p>
 *
 * @author ezzziy
 */
@Getter
@AllArgsConstructor
public enum ExecutionStatus {

    /**
     * 执行成功（程序正常退出，exit code = 0）
     */
    SUCCESS(0, "Success", "执行成功"),

    /**
     * 编译错误
     */
    COMPILE_ERROR(1, "Compile Error", "编译错误"),

    /**
     * 运行时错误（程序非正常退出，exit code != 0）
     */
    RUNTIME_ERROR(2, "Runtime Error", "运行时错误"),

    /**
     * 时间超限
     */
    TIME_LIMIT_EXCEEDED(3, "Time Limit Exceeded", "时间超限"),

    /**
     * 内存超限
     */
    MEMORY_LIMIT_EXCEEDED(4, "Memory Limit Exceeded", "内存超限"),

    /**
     * 输出超限
     */
    OUTPUT_LIMIT_EXCEEDED(5, "Output Limit Exceeded", "输出超限"),

    /**
     * 系统错误
     */
    SYSTEM_ERROR(6, "System Error", "系统错误"),

    /**
     * 危险代码（包含被禁止的系统调用等）
     */
    DANGEROUS_CODE(7, "Dangerous Code", "危险代码");

    /**
     * 状态码
     */
    private final int code;

    /**
     * 英文名称
     */
    private final String name;

    /**
     * 中文描述
     */
    private final String description;

    /**
     * 根据状态码获取枚举
     */
    public static ExecutionStatus fromCode(int code) {
        for (ExecutionStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return SYSTEM_ERROR;
    }

    /**
     * 判断是否为成功状态
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 判断是否为错误状态
     */
    public boolean isError() {
        return this != SUCCESS;
    }
}
