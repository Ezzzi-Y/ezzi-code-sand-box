package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.model.dto.InputDataSet;

/**
 * 输入数据服务接口
 * <p>
 * 管理输入数据的获取和缓存，支持两种输入模式：
 * 1. 直接输入：传入单个字符串，直接使用，不缓存
 * 2. URL 输入：从预签名 URL 下载 ZIP，解压并按 ObjectKey 本地磁盘缓存
 * 
 * 缓存机制：
 * - 输入数据第一次下载到本地磁盘，避免重复下载
 * - 除非通过 evictByObjectKey 接口显式要求，否则一直保留在本地
 * - 唯一标识符：ObjectKey（从 URL 中提取）
 * </p>
 *
 * @author ezzziy
 */
public interface InputDataService {

    /**
     * 从预签名 URL 获取输入数据集
     * <p>
     * 流程：
     * 1. 从 URL 中提取 ObjectKey
     * 2. 检查本地磁盘是否已存在（不使用内存缓存）
     * 3. 若存在，直接从本地磁盘加载
     * 4. 若不存在，下载 ZIP 包并解压到本地磁盘
     * 5. 读取所有 *.in 格式的文件并按序号排序
     * </p>
     *
     * @param presignedUrl 预签名 URL（MinIO/AliyunOSS/S3 格式）
     * @return 包含多个输入的数据集
     * @throws RuntimeException 下载、解压或读取失败时抛出
     */
    InputDataSet getInputDataSet(String presignedUrl);

    /**
     * 从预签名 URL 中提取 ObjectKey
     * <p>
     * 用于外部查询缓存状态或显式清除缓存
     * </p>
     *
     * @param presignedUrl 预签名 URL
     * @return ObjectKey（格式：bucket/path/to/object）
     */
    String getObjectKey(String presignedUrl);

    /**
     * 将单个输入包装为数据集（不缓存）
     * <p>
     * 用于直接输入模式，返回只包含一个元素的数据集
     * </p>
     *
     * @param input 单个输入字符串
     * @return 包含该输入的数据集
     */
    InputDataSet wrapSingleInput(String input);

    /**
     * 显式清除指定 ObjectKey 的本地数据
     * <p>
     * 在后端更新了输入数据后调用此方法，强制清除本地缓存，
     * 下次访问该 ObjectKey 的数据时会重新下载
     * </p>
     *
     * @param objectKey 对象键（格式：bucket/path/to/object）
     */
    void evictByObjectKey(String objectKey);

    /**
     * 检查指定 ObjectKey 的数据是否已在本地磁盘缓存
     * <p>
     * 用于检查缓存状态
     * </p>
     *
     * @param objectKey 对象键
     * @return true 表示已缓存，false 表示未缓存
     */
    boolean isCached(String objectKey);
}
