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
- 基于调用方提供的版本号（或 GET 响应头中的 ETag）判定缓存是否失效
- 保持缓存索引稳定：以 `ObjectKey` 作为本地目录标识

### 2.2 核心原则

- **版本号驱动模式**：若调用方在请求中提供 `inputDataVersion`，沙箱直接做字符串精确比对，缓存命中时**零网络请求**
- **GET 回退模式**：若未提供 `inputDataVersion`，回退为 GET 统一获取（下载 ZIP 后从响应头读 ETag 做版本比对）
- **ObjectKey 仅用于本地缓存定位**，不用于拼接远端无签名 URL
- 沙箱不发起任何 HEAD 请求，不依赖对象存储的 HEAD 方法支持

---

## 3. 执行链路

### 3.1 调用路径

1. `POST /execute/batch`
2. `ExecutionServiceImpl.resolveBatchInputs(...)`
3. `InputDataServiceImpl.getInputDataSet(presignedGetUrl, inputDataVersion)`

### 3.2 处理流程（版本号驱动模式，提供 `inputDataVersion`）

1. 解析预签名 GET URL，提取 `ObjectKey`（用于缓存目录）
2. 读取本地 `_meta.properties` 中的 `version` 字段
3. 与请求中的 `inputDataVersion` 做字符串精确比较
4. 若一致：直接读取本地 `*.in` 文件（**零网络请求、零下载开销**）
5. 若不一致或无缓存：使用预签名 GET URL 发起 `GET` 请求下载 ZIP，解压并写入版本号

### 3.3 处理流程（回退模式，未提供 `inputDataVersion`）

1. 解析预签名 GET URL，提取 `ObjectKey`（用于缓存目录）
2. 使用预签名 GET URL 发起 `GET` 请求，同时获取远端元数据（ETag）和 ZIP 内容
3. 从 ETag（或 Last-Modified）推导版本字符串
4. 读取本地 `_meta.properties` 中的 `version` 字段
5. 若一致：丢弃本次 GET 的 ZIP 内容，直接读取本地 `*.in` 文件
6. 若不一致或无缓存：使用本次 GET 的 ZIP 内容解压并写入版本号

---

## 4. 版本判定机制

### 4.1 版本号来源

#### 路径 A：调用方携带版本号（版本号驱动模式）

- 版本号由调用方（判题服务）在请求字段 `inputDataVersion` 中提供
- 推荐使用 ZIP 文件的 sha256 摘要，也可使用递增整数或时间戳
- 沙箱仅做字符串精确比较，不关心版本号含义

#### 路径 B：从 GET 响应头推导（回退模式）

`fetchRemoteObject(String presignedUrl)` 使用：

- `cn.hutool.http.HttpRequest.get()`
- `GET presignedUrl`（一次请求同时获取元数据和 ZIP 内容）
- 超时配置：`sandbox.input-data.download-timeout`
- 从响应头提取 `ETag`（优先）或 `Last-Modified` 作为版本号

#### 状态码处理

- `200`：正常处理
- `404`：抛出"远端对象不存在"异常
- 其他状态码：抛出对应失败异常

### 4.2 本地元数据文件

缓存目录下维护 `_meta.properties`，字段包括：

- `version`：版本标识（由调用方提供的版本号，或从 ETag 推导）
- `updatedAt`：最后更新时间戳

> 兼容说明：读取时若 `version` 字段不存在，回退读取旧格式的 `etag` 字段，确保平滑迁移。

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
- HEAD 请求探测远端元数据（已废弃，详见文档 13）
- `inputDataHeadUrl` 字段（已删除）

当前版本采用 **磁盘缓存 + 版本号字符串比对** 的简化方案。

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
- `download-timeout`：GET 网络超时（毫秒）
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

1. 使用私有 bucket 生成 `inputDataUrl`，并传入 `inputDataVersion`，请求 `/execute/batch`
2. 首次请求应触发 GET 下载 ZIP，并写入本地缓存目录（含版本号）
3. 再次请求同一版本号，应零网络请求直接复用本地缓存
4. 更新远端 ZIP 后传入新版本号，应重新下载并覆盖缓存
5. 不传 `inputDataVersion` 时，应回退为 GET 统一获取模式

---

## 10. 相关代码位置

- `src/main/java/com/github/ezzziy/codesandbox/service/impl/InputDataServiceImpl.java`
- `src/main/java/com/github/ezzziy/codesandbox/service/impl/ExecutionServiceImpl.java`
- `src/main/java/com/github/ezzziy/codesandbox/util/OssUrlParser.java`
- `src/main/resources/application.yml`
