# 输入数据 URL（签名绑定 GET）下的 ETAG 获取方案与实施计划

> 状态：**✅ 已实施**  
> 日期：2026-02-28  
> 决策：**采用方案一（统一使用 GET 获取 ETAG）**  
> 完成说明：`InputDataServiceImpl.fetchRemoteObject()` 已使用 `HttpRequest.get()` 统一获取元数据和 ZIP 内容，不再发起 HEAD 请求。

---

## 1. 问题背景

代码沙箱批量执行场景中，`inputDataUrl` 为预签名 URL。当前实现在拉取远端元数据时使用 `HEAD` 请求获取 `ETag/Last-Modified`。

但业务侧提供的签名绑定了 `GET` 方法，导致：

- 使用 `GET` 下载 ZIP：签名校验通过；
- 使用 `HEAD` 获取 `ETag`：签名方法不匹配，返回 `403 Forbidden`。

因此，现有“先 HEAD 再决定是否下载”的流程在该签名策略下不可用。

---

## 2. 四种可选方案整理

## 方案一：统一使用 GET 获取 ETAG（已选）

### 思路

不再发起 `HEAD`。改为使用 `GET` 请求获取资源，并从响应头读取 `ETag/Last-Modified`。

### 关键点

- 请求方法与签名方法一致（GET），避免 403。
- 如果可行，优先尝试带 `Range: bytes=0-0` 的 GET，降低流量；若存储服务不支持，则退化为普通 GET。
- 同一条 GET 响应可同时完成“元数据获取 + 内容下载”，减少一次往返。

### 优点

- 不依赖上游签名规则变更，可在沙箱侧快速落地。
- 与当前业务约束（签名绑定 GET）完全兼容。
- 实施复杂度低，改动集中在输入数据服务层。

### 缺点

- 当缓存命中时，仍需发起 GET（可能带来少量额外带宽开销）。

---

## 方案二：签名链路同时支持 HEAD

### 思路

由签名服务或对象存储网关改造，使同一资源 URL 对 `HEAD` 也合法，或单独下发 HEAD 可用签名。

### 优点

- 保留“HEAD 探测元数据”的语义，命中缓存时无需下载体。

### 缺点

- 需要跨系统协调（签名服务/存储网关/安全策略），推进成本高。
- 变更周期和风险不可控，不适合当前快速修复。

---

## 方案三：新增元数据查询接口

### 思路

由数据提供方提供独立元数据 API（如 `/meta`），返回 `etag`、`lastModified`、`size` 等；沙箱先查元数据再决定是否拉取 ZIP。

### 优点

- 语义清晰，缓存判断无需访问对象正文。
- 可扩展更多字段（版本号、业务哈希、生成时间等）。

### 缺点

- 需要新增服务接口、鉴权、协议与维护成本。
- 沙箱与上游耦合增强。

---

## 方案四：以历史 GET 响应头作为版本来源

### 思路

仅在首次或强制刷新时发起 GET 下载，并将当次响应头中的 `ETag/Last-Modified` 持久化；后续短期内直接信任本地元数据，不每次探测远端。

### 优点

- 减少探测请求，简单高效。

### 缺点

- 无法及时感知远端变更，存在缓存陈旧窗口。
- 需要额外定义 TTL 或主动失效策略。

---

## 3. 方案选型结论

当前约束下（签名绑定 GET、HEAD 会 403），**采用方案一**：

- 由沙箱侧独立完成改造；
- 不阻塞于外部系统；
- 能快速恢复“版本判断 + 下载缓存”能力。

---

## 4. 基于现有代码的方案一实施规划

## 4.1 当前代码现状（已核对）

- `src/main/java/com/github/ezzziy/codesandbox/service/impl/InputDataServiceImpl.java`
  - `getInputDataSet(String presignedUrl)`：先调用 `fetchRemoteMeta`，后决定是否 `downloadAndSaveToDisk`。
  - `fetchRemoteMeta(String presignedUrl)`：当前使用 `HttpRequest.head(...)`，是 403 的直接触发点。
  - `downloadAndSaveToDisk(...)`：使用 `HttpUtil.downloadBytes(...)` 下载 ZIP。
- `src/main/java/com/github/ezzziy/codesandbox/service/InputDataService.java`
  - 接口注释与流程说明中写明“通过 HEAD 获取远端元数据”。

结论：问题集中在 `InputDataService` 这一层，`ExecutionServiceImpl` 调用链无需接口层级改动。

---

## 4.2 目标行为（方案一）

将流程从“HEAD 探测 + GET 下载”调整为“GET 响应驱动的版本判断与缓存更新”：

1. 发起 GET 请求（方法与签名一致）。
2. 从响应头提取 `ETag/Last-Modified`。
3. 与本地 `_meta.properties` 比对：
   - 一致：优先复用本地缓存；
   - 不一致或本地缺失：使用当前 GET 返回体解压写盘并更新本地元数据。
4. 失败时按既有策略清理残留目录并抛错。

---

## 4.3 建议改造步骤

### 步骤 A：重构远端获取方法（核心）

在 `InputDataServiceImpl` 中，用一个新方法替代 `fetchRemoteMeta`：

- 建议新增内部结果类型（示意）：
  - `RemoteFetchResult(byte[] body, RemoteObjectMeta meta)`
- 使用 `HttpRequest.get(presignedUrl)` 执行请求；
- 读取：
  - `statusCode`
  - 响应头 `ETag`、`Last-Modified`
  - 响应体 bytes
- 状态处理：
  - `200`：正常返回
  - `404`：抛“远端对象不存在”
  - 其他：抛“远端查询失败，状态码 xxx”

> 说明：若后续验证对象存储支持 `Range: bytes=0-0` 且仍返回完整头信息，可再做流量优化；首版先保证兼容与正确性。

### 步骤 B：调整主流程 `getInputDataSet`

将 `getInputDataSet` 中的调用顺序改为：

- 先执行 `fetchRemoteObjectByGet`（拿到 `meta + body`）；
- 若本地缓存存在且版本一致：
  - 直接返回 `loadFromLocalDisk`（忽略本次 body，不落盘）；
- 否则：
  - 删除旧目录（如存在）；
  - 用已拿到的 body 做解压落盘；
  - 写入新 meta。

### 步骤 C：下载落盘逻辑去重

当前 `downloadAndSaveToDisk` 内部会再次 `HttpUtil.downloadBytes`。方案一下应避免重复下载：

- 将 `downloadAndSaveToDisk` 改造成“接收 zipBytes 参数”的纯落盘方法；
- 或新增 `saveZipToDisk(objectKey, zipBytes, remoteMeta)`，由主流程调用。

### 步骤 D：更新接口文档与注释

同步修改：

- `InputDataService` 的 Javadoc（将 HEAD 描述改为 GET）；
- `InputDataServiceImpl` 类注释与方法注释中的 HEAD 表述。

### 步骤 E：日志与可观测性

新增/调整关键日志字段，便于线上排查：

- `method=GET`
- `statusCode`
- `etag`
- `cacheHit=true/false`
- `objectKey`

---

## 4.4 验收标准

满足以下条件即可验收方案一：

1. 输入 URL 为“仅 GET 有效签名”时，不再出现因 HEAD 导致的 403。
2. 首次请求成功下载并缓存，后续同版本请求可复用本地缓存。
3. 远端对象变更后，能检测到版本变化并重新拉取。
4. 失败场景（404、下载中断、解压异常）能稳定报错且无无效目录残留。
5. 相关注释/文档已从 HEAD 语义统一更新为 GET 语义。

---

## 4.5 风险与回滚

### 风险

- 缓存命中时仍会有一次 GET 请求开销。
- 若对象存储在特定场景不返回 `ETag/Last-Modified`，版本比较将退化（当前代码已支持字段缺失时的兜底逻辑）。

### 回滚策略

- 保留旧版实现分支；
- 若线上出现异常，可临时回滚到“无版本探测、每次全量下载”的保守模式，确保功能可用性优先。

---

## 5. 后续可选优化（不纳入本次实施）

1. 验证并启用 `Range: bytes=0-0`，减少缓存命中场景流量。
2. 增加可配置探测模式：`GET_FULL / GET_RANGE / ALWAYS_DOWNLOAD`。
3. 若上游未来支持，可切回 `HEAD` 探测或引入独立元数据接口。
