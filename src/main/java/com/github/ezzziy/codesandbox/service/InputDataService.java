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
 *   <li>若提供 HEAD URL，通过 HEAD 探测 ETag/Last-Modified，缓存命中时零下载开销</li>
 *   <li>若未提供 HEAD URL，回退为 GET 统一获取（下载 ZIP 后从响应头读取元数据）</li>
 *   <li>版本一致则复用本地缓存，不一致则重新下载覆盖</li>
 * </ul>
 *
 * @author ezzziy
 */
public interface InputDataService {

    /**
     * 从预签名 URL 获取输入数据集（双 URL 模式）
     * <p>
     * 流程：
     * 1. 从 GET URL 中提取 ObjectKey，定位本地缓存目录
     * 2. 若提供 HEAD URL：HEAD 探测元数据 → 缓存命中则直接返回，未命中则 GET 下载
     * 3. 若未提供 HEAD URL：GET 统一获取元数据和 ZIP 内容（回退模式）
     * 4. 读取所有 *.in 格式的文件并按序号排序返回
     * </p>
     *
     * @param presignedGetUrl  签名绑定 GET 的预签名 URL，用于下载 ZIP
     * @param presignedHeadUrl 签名绑定 HEAD 的预签名 URL，用于探测 ETag（可选，为 null 时回退为 GET 统一获取）
     * @return 包含多个输入的数据集
     * @throws RuntimeException 下载、解压或读取失败时抛出
     */
    InputDataSet getInputDataSet(String presignedGetUrl, String presignedHeadUrl);
}
