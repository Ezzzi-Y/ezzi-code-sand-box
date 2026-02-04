package com.github.ezzziy.codesandbox.pool;

import com.github.ezzziy.codesandbox.strategy.LanguageStrategy;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 容器池中的容器包装类
 *
 * @author ezzziy
 */
@Data
@AllArgsConstructor
public class PooledContainer {
    /**
     * 容器 ID
     */
    private String containerId;

    /**
     * 语言策略
     */
    private LanguageStrategy strategy;

    /**
     * 是否正在使用
     */
    private boolean inUse;

    /**
     * 上次使用时间
     */
    private LocalDateTime lastUsedTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 使用次数
     */
    private int useCount;
}
