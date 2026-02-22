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

    /**
     * 期望输出列表，按序号排列（1.out, 2.out, 3.out...）
     * 直接输入模式下可为空
     */
    private List<String> expectedOutputs;

    /**
     * 获取输入数量
     */
    public int size() {
        return inputs != null ? inputs.size() : 0;
    }

    public boolean hasExpectedOutputs() {
        return expectedOutputs != null && !expectedOutputs.isEmpty();
    }

    /**
     * 获取指定索引的输入（0-based）
     */
    public String getInput(int index) {
        if (inputs == null || index < 0 || index >= inputs.size()) {
            return null;
        }
        return inputs.get(index);
    }
}
