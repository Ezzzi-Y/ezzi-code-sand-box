package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.model.dto.BatchExecuteRequest;
import com.github.ezzziy.codesandbox.model.dto.SingleExecuteRequest;
import com.github.ezzziy.codesandbox.model.vo.BatchExecuteResponse;
import com.github.ezzziy.codesandbox.model.vo.SingleExecuteResponse;

import java.util.List;

/**
 * 代码执行服务接口
 * <p>
 * 提供代码执行的核心功能，负责：
 * - 代码编译
 * - 代码运行
 * - 测试用例执行
 * - 执行结果收集
 * </p>
 *
 * @author ezzziy
 */
public interface ExecutionService {

    /**
     * 执行代码
     * <p>
     * 完整执行流程：
     * 1. 验证语言支持
     * 2. 获取输入数据集
     * 3. 编译代码（如果需要）
     * 4. 运行所有测试用例
     * 5. 收集结果（输出、执行时间、内存使用）
     * </p>
     *
     * @param request 执行请求，包含代码、语言、输入等
     * @return 执行结果，包含编译输出和所有测试用例的执行结果
     */
    SingleExecuteResponse executeSingle(SingleExecuteRequest request);

    /**
     * 批量执行代码（多测试用例）
     *
     * @param request 批量执行请求
     * @return 批量执行结果
     */
    BatchExecuteResponse executeBatch(BatchExecuteRequest request);

    /**
     * 获取系统支持的所有编程语言
     * <p>
     * 返回的信息包括：
     * - code: 语言代码（java8, java11, python3等）
     * - name: 语言显示名称
     * - dockerImage: 使用的 Docker 镜像名称
     * - extension: 源文件扩展名
     * </p>
     *
     * @return 支持的语言列表
     */
    List<LanguageInfo> getSupportedLanguages();

    /**
     * 编程语言信息
     * <p>
     * 包含语言的基本配置信息
     * </p>
     *
     * @param code 语言标识（java8, python3等）
     * @param name 语言显示名称（Java 8, Python 3等）
     * @param dockerImage Docker 镜像标签
     * @param extension 源文件扩展名
     */
    record LanguageInfo(String code, String name, String dockerImage, String extension) {}
}
