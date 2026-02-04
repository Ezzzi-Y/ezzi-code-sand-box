package com.github.ezzziy.codesandbox.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 执行时间统计 DTO
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionTimeStats {

    /**
     * 编译时间（毫秒）
     */
    private Long compileTime;

    /**
     * 运行时间（毫秒）- 所有输入执行的总时间
     */
    private Long runTime;

    /**
     * 总时间（毫秒）- 从请求开始到结束
     */
    private Long totalTime;
}
