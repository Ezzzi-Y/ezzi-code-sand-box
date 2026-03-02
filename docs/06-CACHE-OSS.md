# 代码执行沙箱服务 - 输入数据缓存体系（当前实现）

## 1. 文档范围

本文档描述当前代码实现中的输入数据缓存机制，基于以下组件：

- `InputDataService` / `InputDataServiceImpl`
- `ExecutionServiceImpl.resolveBatchInputs`
- `OssUrlParser`

> 说明：本项目当前没有独立的 `CacheController` 或 `CacheService`。输入数据缓存能力已内聚到 `InputDataServiceImpl`。

---

## 2. 总体设计

### 2.1 目标

- 通过本地磁盘缓存减少重复下载 ZIP 输入包
- 基于远端对象元数据（`ETag` / `Last-Modified`）自动判定缓存是否失效
- 保持缓存索引稳定：以 `ObjectKey` 作为本地目录标识

### 2.2 核心原则

- **双 URL 模式**：若提供 `inputDataHeadUrl`，使用 HEAD 探测元数据 + GET 按需下载；否则回退为 GET 统一获取
- **ObjectKey 仅用于本地缓存定位**，不用于拼接远端无签名 URL
- 批量执行接口支持 `inputDataUrl`（GET 预签名 URL）+ 可选的 `inputDataHeadUrl`（HEAD 预签名 URL）

---

## 3. 执行链路

### 3.1 调用路径

1. `POST /execute/batch`
2. `ExecutionServiceImpl.resolveBatchInputs(...)`
3. `InputDataServiceImpl.getInputDataSet(presignedGetUrl, presignedHeadUrl)`

### 3.2 处理流程（双 URL 模式，提供 `inputDataHeadUrl`）

1. 解析预签名 GET URL，提取 `ObjectKey`（用于缓存目录）
2. 使用预签名 HEAD URL 发起 `HEAD` 请求，获取远端元数据（`ETag`/`Last-Modified`）
3. 读取本地 `_meta.properties` 比对版本
4. 若一致：直接读取本地 `*.in` 文件（**零下载开销**）
5. 若不一致或无缓存：使用预签名 GET URL 发起 `GET` 请求下载 ZIP，解压并写入元数据

### 3.3 处理流程（回退模式，未提供 `inputDataHeadUrl`）

1. 解析预签名 GET URL，提取 `ObjectKey`（用于缓存目录）
2. 使用预签名 GET URL 发起 `GET` 请求，同时获取远端元数据和 ZIP 内容
3. 读取本地 `_meta.properties` 比对版本
4. 若一致：丢弃本次 GET 的 ZIP 内容，直接读取本地 `*.in` 文件
5. 若不一致或无缓存：使用本次 GET 的 ZIP 内容解压并写入元数据

---

## 4. 版本判定机制

### 4.1 远端数据获取

#### 路径 A：HEAD 探测（双 URL 模式）

`fetchRemoteMeta(String presignedHeadUrl)` 使用：

- `cn.hutool.http.HttpRequest.head()`
- `HEAD presignedHeadUrl`（仅获取响应头，不下载内容）
- 超时配置：`sandbox.input-data.download-timeout`
- 返回 `RemoteObjectMeta(etag, lastModified)`

缓存未命中时，`downloadZip(String presignedGetUrl)` 使用 `GET` 单独下载 ZIP body。

#### 路径 B：GET 统一获取（回退模式）

`fetchRemoteObject(String presignedUrl)` 使用：

- `cn.hutool.http.HttpRequest.get()`
- `GET presignedUrl`（一次请求同时获取元数据和 ZIP 内容）
- 超时配置：`sandbox.input-data.download-timeout`
- 返回 `RemoteFetchResult(byte[] zipBytes, RemoteObjectMeta meta)`

#### 状态码处理（两种路径通用）

- `200`：读取响应头 `ETag` 和 `Last-Modified`
- `404`：抛出"远端对象不存在"异常
- 其他状态码：抛出对应失败异常

### 4.2 本地元数据文件

缓存目录下维护 `_meta.properties`，字段包括：

- `etag`
- `lastModified`
- `updatedAt`

版本比较策略：

1. 优先比较 `etag`
2. `etag` 缺失时比较 `lastModified`
3. 两者都不可用则视为版本不一致

---

## 5. 本地缓存结构

### 5.1 路径规则

- 根目录：`sandbox.input-data.storage-dir`（默认 `/var/lib/sandbox-inputs`）
- 子目录：`ObjectKey` 去掉 `.zip/.ZIP` 后作为目录名

示例：

- ObjectKey：`judgedata/1-input.zip`
- 本地目录：`/var/lib/sandbox-inputs/judgedata/1-input`

### 5.2 目录内容

- `1.in`, `2.in`, ...（按输入序号）
- `_meta.properties`（版本信息）

ZIP 解压规则：

- 仅接收匹配 `^(\\d+)\\.in$` 的文件
- 按数字序号排序后组成输入列表

---

## 6. 与旧方案差异

以下设计 **不属于当前实现**：

- Caffeine/LRU 双层缓存
- TTL 自动过期淘汰
- `CacheController` 的 `/cache/refresh`、`/cache/clear`、`/cache/status`
- 独立 `CacheService` / `CacheServiceImpl`

当前版本采用 **磁盘缓存 + 远端元数据校验** 的简化方案。

---

## 7. 配置项

当前输入缓存相关配置：

```yaml
sandbox:
  input-data:
    storage-dir: /var/lib/sandbox-inputs
    download-timeout: 30000
    max-file-size: 10485760
```

含义：

- `storage-dir`：本地缓存根目录
- `download-timeout`：HEAD/GET 网络超时（毫秒）
- `max-file-size`：ZIP 最大大小（字节）

---

## 8. 错误与边界

- 预签名 URL 为空：参数校验失败
- GET 非 200：远端对象查询失败
- GET 响应体为空：下载失败
- ZIP 超过 `max-file-size`：拒绝处理
- ZIP 内无合法 `*.in`：抛出输入数据格式错误

---

## 9. 建议验证步骤

1. 使用私有 bucket 生成 `inputDataUrl` 并请求 `/execute/batch`
2. 首次请求应触发 GET 下载 ZIP，并写入本地缓存目录
3. 再次请求同一 URL，GET 获取元数据后发现版本一致，复用本地缓存
4. 更新远端 ZIP（ETag 变化）后再请求，GET 获取后发现版本不一致，使用新数据覆盖缓存

---

## 10. 相关代码位置

- `src/main/java/com/github/ezzziy/codesandbox/service/impl/InputDataServiceImpl.java`
- `src/main/java/com/github/ezzziy/codesandbox/service/impl/ExecutionServiceImpl.java`
- `src/main/java/com/github/ezzziy/codesandbox/util/OssUrlParser.java`
- `src/main/resources/application.yml`
