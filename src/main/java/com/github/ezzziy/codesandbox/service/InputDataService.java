package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.model.dto.InputDataSet;

/**
 * 输入数据服务接口
 * <p>
 * 从预签名 URL 获取 ZIP 格式的输入数据包，解压后提供有序的输入列表。
 * <p>
 * 缓存机制：
 * <ul>
 *   <li>以 ObjectKey 为标识在本地磁盘缓存解压后的 .in 文件</li>
 *   <li>每次请求通过 GET 获取远端响应头中的 ETag/Last-Modified 与本地元数据比对</li>
 *   <li>版本一致则复用本地缓存，不一致则重新下载覆盖</li>
 * </ul>
 *
 * @author ezzziy
 */
public interface InputDataService {

    /**
     * 从预签名 URL 获取输入数据集
     * <p>
     * 流程：
     * 1. 从 URL 中提取 ObjectKey，定位本地缓存目录
    * 2. 对预签名 URL 发起 GET 请求，获取远端 ETag/Last-Modified 和 ZIP 内容
    * 3. 与本地元数据比对：一致则从磁盘加载，不一致则使用本次 GET 的 ZIP 内容解压落盘
     * 4. 读取所有 *.in 格式的文件并按序号排序返回
     * </p>
     *
     * @param presignedUrl 预签名 URL（MinIO/AliyunOSS/S3 格式）
     * @return 包含多个输入的数据集
     * @throws RuntimeException 下载、解压或读取失败时抛出
     */
    InputDataSet getInputDataSet(String presignedUrl);
}
