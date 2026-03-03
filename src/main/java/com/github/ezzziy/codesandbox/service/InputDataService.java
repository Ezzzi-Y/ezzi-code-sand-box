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
 *   <li>若提供 inputDataVersion，直接做版本字符串比对，缓存命中时零网络请求</li>
 *   <li>若未提供 inputDataVersion，回退为 GET 统一获取（下载 ZIP 后从响应头读取 ETag 做版本比对）</li>
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
     * 1. 从 GET URL 中提取 ObjectKey，定位本地缓存目录
     * 2. 若提供 inputDataVersion：与本地版本字符串比对 → 一致则直接返回缓存，不一致则 GET 下载
     * 3. 若未提供 inputDataVersion：GET 统一获取元数据和 ZIP 内容（回退模式，从响应头读 ETag）
     * 4. 读取所有 *.in 格式的文件并按序号排序返回
     * </p>
     *
     * @param presignedGetUrl    签名绑定 GET 的预签名 URL，用于下载 ZIP
     * @param inputDataVersion   输入数据版本号（如 sha256），用于本地缓存比对。
     *                           为 null 时回退为 GET 统一获取（从响应头读 ETag）
     * @return 包含多个输入的数据集
     * @throws RuntimeException 下载、解压或读取失败时抛出
     */
    InputDataSet getInputDataSet(String presignedGetUrl, String inputDataVersion);
}
