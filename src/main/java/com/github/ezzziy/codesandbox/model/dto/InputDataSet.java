package com.github.ezzziy.codesandbox.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 输入数据集
 * <p>
 * 封装从 ZIP 压缩包解压出的输入数据列表
 * </p>
 *
 * @author ezzziy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InputDataSet {

    /**
     * 输入数据集 ID（缓存 key）
     */
    private String dataId;

    /**
     * 输入数据列表，按序号排列（1.in, 2.in, 3.in...）
     */
    private List<String> inputs;
}
