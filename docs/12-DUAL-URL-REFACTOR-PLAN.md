# 双 URL 方案：HEAD 探测 + GET 下载分离

> 状态：**❌ 已废弃**
> 日期：2026-03-02
> 前置文档：[10-SIGNED-GET-ETAG-SOLUTION.md](10-SIGNED-GET-ETAG-SOLUTION.md)
> 废弃说明：阿里云 OSS 不支持 HEAD 方法，本方案无法落地。已被 [13-VERSION-STRING-CACHE-REFACTOR-PLAN.md](13-VERSION-STRING-CACHE-REFACTOR-PLAN.md)（版本号驱动缓存方案）取代。

---

## 1. 问题背景

### 1.1 现状

当前实现（文档 10 方案一）为适配「预签名 URL 仅绑定 GET」的约束，将原有的 HEAD+GET 两步走改为了 **统一 GET**：

```
fetchRemoteObject(presignedUrl)
  └─ GET presignedUrl ──► 200 ──► (ETag, Last-Modified, zipBytes)
                                     │
                          本地缓存比对 ─┤
                          ├─ 命中 → 丢弃 zipBytes，读本地磁盘
                          └─ 未命中 → 用 zipBytes 解压落盘
```

关键代码位于 `InputDataServiceImpl.getInputDataSet()`：

```java
RemoteFetchResult remoteFetch = fetchRemoteObject(presignedUrl);   // 全量 GET
RemoteObjectMeta remoteMeta = remoteFetch.meta();
// ...
if (isSameVersion(localMeta, remoteMeta)) {
    // 缓存命中 → zipBytes 被丢弃（白下载了）
    return loadFromLocalDisk(objectKey, localDir);
}
```

### 1.2 痛点

| 场景 | 当前行为 | 浪费 |
|------|---------|------|
| 缓存命中（数据未变更） | 发起 GET 下载完整 ZIP → 读响应头比对 → 丢弃 body | 全部带宽 + 下载耗时 |
| 缓存未命中（首次/数据变更） | 发起 GET 下载完整 ZIP → 使用 body 解压落盘 | 无浪费 |

在在线判题场景中，输入数据通常在题目发布后趋于稳定，**绝大多数请求都是缓存命中**。
这意味着几乎每次请求都在白白下载一个可能数 MB 的 ZIP 包，极其浪费。

### 1.3 根因

签名服务只生成了一个绑定 GET 方法的预签名 URL，沙箱侧无法用它发 HEAD 请求。
但签名服务完全有能力同时生成两个签名不同、方法不同的 URL——只是当前 API 没有「传两个 URL」的通道。

---

## 2. 方案设计：接受双 URL

### 2.1 核心思路

API 层同时接收两个预签名 URL：

| 字段 | HTTP 方法 | 用途 |
|------|-----------|------|
| `inputDataUrl` | GET | 下载 ZIP 内容（仅在缓存未命中时使用） |
| `inputDataHeadUrl` | HEAD | 探测元数据 ETag/Last-Modified（每次请求都用） |

服务端恢复为经典的 「HEAD 探测 → 按需 GET 下载」两步走流程，**缓存命中时零下载开销**。

### 2.2 目标流程

```
getInputDataSet(getUrl, headUrl)
  │
  ├─ 1. HEAD headUrl ──► 200 ──► (ETag, Last-Modified)
  │                                  │
  │                       本地缓存比对 ─┤
  │                       ├─ 命中 → 直接返回本地数据 ✅ (零下载)
  │                       └─ 未命中 ↓
  │
  ├─ 2. GET getUrl ──► 200 ──► zipBytes
  │
  └─ 3. 解压落盘 + 更新 _meta.properties
```

### 2.3 兼容性策略

`inputDataHeadUrl` 为**可选字段**：

| `inputDataHeadUrl` | `inputDataUrl` | 行为 |
|---------------------|----------------|------|
| ✅ 提供 | ✅ 提供 | HEAD 探测 + 按需 GET 下载（最优） |
| ❌ 未提供 | ✅ 提供 | 回退到当前逻辑：GET 统一获取（向后兼容） |

这保证了：
- 已有调用方无需任何改动即可继续工作
- 新调用方传入 `inputDataHeadUrl` 后自动获得性能提升

---

## 3. 改动清单

### 3.1 DTO 改动

#### `BatchExecuteRequest.java`

新增可选字段 `inputDataHeadUrl`：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchExecuteRequest {

    // ... 现有字段不变 ...

    @NotBlank(message = "inputDataUrl 不能为空，且必须是 zip 文件 URL")
    private String inputDataUrl;

    /** 预签名 HEAD URL，用于高效探测远端元数据。可选，缺省时回退为 GET 统一获取。 */
    private String inputDataHeadUrl;

    // ...
}
```

> `ExecuteRequest.java`（旧 DTO，当前未被 Controller 使用）：同步新增字段以保持一致，或等待彻底清理时移除。

---

### 3.2 接口层改动

#### `InputDataService.java`

方法签名扩展，同时接受两个 URL：

```java
public interface InputDataService {

    /**
     * 从预签名 URL 获取输入数据集（双 URL 模式）
     *
     * @param presignedGetUrl  签名绑定 GET 的预签名 URL，用于下载 ZIP
     * @param presignedHeadUrl 签名绑定 HEAD 的预签名 URL，用于探测 ETag（可选，为 null 时回退为 GET 统一获取）
     * @return 输入数据集
     */
    InputDataSet getInputDataSet(String presignedGetUrl, String presignedHeadUrl);
}
```

---

### 3.3 实现层改动

#### `InputDataServiceImpl.java`

**核心改动：恢复 HEAD 探测能力，并保留 GET 回退路径。**

##### 3.3.1 新增 `fetchRemoteMeta` 方法（HEAD 探测）

```java
/**
 * 通过 HEAD 请求获取远端对象元数据（ETag / Last-Modified）
 * 不下载内容，仅获取响应头。
 */
private RemoteObjectMeta fetchRemoteMeta(String presignedHeadUrl) {
    try (HttpResponse response = HttpRequest.head(presignedHeadUrl)
            .timeout(downloadTimeout)
            .execute()) {
        int statusCode = response.getStatus();
        if (statusCode == HttpStatus.HTTP_OK) {
            String etag = trimHeader(response.header("ETag"));
            String lastModified = trimHeader(response.header("Last-Modified"));
            return new RemoteObjectMeta(etag, lastModified);
        }
        if (statusCode == HttpStatus.HTTP_NOT_FOUND) {
            throw new RuntimeException("远端对象不存在: HEAD " + presignedHeadUrl);
        }
        throw new RuntimeException("HEAD 探测远端对象失败，HTTP 状态码: " + statusCode);
    } catch (RuntimeException e) {
        throw e;
    } catch (Exception e) {
        throw new RuntimeException("HEAD 获取远端元数据失败: " + e.getMessage(), e);
    }
}
```

##### 3.3.2 新增 `downloadZip` 方法（独立 GET 下载）

```java
/**
 * 通过 GET 请求下载 ZIP 内容（不关心元数据）
 */
private byte[] downloadZip(String presignedGetUrl) {
    try (HttpResponse response = HttpRequest.get(presignedGetUrl)
            .timeout(downloadTimeout)
            .execute()) {
        int statusCode = response.getStatus();
        if (statusCode == HttpStatus.HTTP_OK) {
            return response.bodyBytes();
        }
        if (statusCode == HttpStatus.HTTP_NOT_FOUND) {
            throw new RuntimeException("远端对象不存在: GET " + presignedGetUrl);
        }
        throw new RuntimeException("下载远端对象失败，HTTP 状态码: " + statusCode);
    } catch (RuntimeException e) {
        throw e;
    } catch (Exception e) {
        throw new RuntimeException("下载远端对象失败: " + e.getMessage(), e);
    }
}
```

##### 3.3.3 重写主流程 `getInputDataSet`

```java
@Override
public InputDataSet getInputDataSet(String presignedGetUrl, String presignedHeadUrl) {
    String objectKey = OssUrlParser.extractObjectKey(presignedGetUrl);
    log.debug("从 URL 提取 ObjectKey: {}", objectKey);

    boolean hasHeadUrl = presignedHeadUrl != null && !presignedHeadUrl.isBlank();
    Path localDir = getLocalStoragePath(objectKey);

    // ─── 路径 A：有 HEAD URL，走高效探测 ───
    if (hasHeadUrl) {
        RemoteObjectMeta remoteMeta = fetchRemoteMeta(presignedHeadUrl);

        if (Files.exists(localDir)) {
            RemoteObjectMeta localMeta = readLocalMeta(localDir);
            if (isSameVersion(localMeta, remoteMeta)) {
                log.info("HEAD 探测版本未变化，复用本地缓存: objectKey={}, etag={}",
                        objectKey, remoteMeta.etag());
                return loadFromLocalDisk(objectKey, localDir);
            }
            log.info("HEAD 探测版本已变化，准备重新拉取: objectKey={}", objectKey);
            deleteDirectory(localDir);
        }

        // 缓存未命中，发起 GET 下载
        log.info("开始下载输入数据: objectKey={}", objectKey);
        byte[] zipBytes = downloadZip(presignedGetUrl);
        return downloadAndSaveToDisk(objectKey, zipBytes, remoteMeta);
    }

    // ─── 路径 B：无 HEAD URL，回退到现有 GET 统一获取 ───
    RemoteFetchResult remoteFetch = fetchRemoteObject(presignedGetUrl);
    RemoteObjectMeta remoteMeta = remoteFetch.meta();

    if (Files.exists(localDir)) {
        RemoteObjectMeta localMeta = readLocalMeta(localDir);
        if (isSameVersion(localMeta, remoteMeta)) {
            log.info("GET 探测版本未变化，复用本地缓存（回退模式）: objectKey={}, etag={}",
                    objectKey, remoteMeta.etag());
            return loadFromLocalDisk(objectKey, localDir);
        }
        log.info("GET 探测版本已变化，准备重新拉取: objectKey={}", objectKey);
        deleteDirectory(localDir);
    }

    log.info("开始使用已获取的 ZIP 内容落盘: objectKey={}", objectKey);
    return downloadAndSaveToDisk(objectKey, remoteFetch.zipBytes(), remoteMeta);
}
```

> **原有的 `fetchRemoteObject` 方法保留不动**，它继续作为路径 B（回退模式）的实现。

---

### 3.4 调用链改动

#### `ExecutionServiceImpl.resolveBatchInputs`

将 `inputDataHeadUrl` 传递到 `InputDataService`：

```java
private List<String> resolveBatchInputs(BatchExecuteRequest request) {
    if (request.getInputDataUrl() == null || request.getInputDataUrl().isBlank()) {
        throw new IllegalArgumentException("批量执行必须提供 inputDataUrl（zip 文件 URL）");
    }
    InputDataSet inputDataSet = inputDataService.getInputDataSet(
            request.getInputDataUrl(),
            request.getInputDataHeadUrl()    // 新增参数，可为 null
    );
    return inputDataSet.getInputs() == null ? new ArrayList<>() : inputDataSet.getInputs();
}
```

---

### 3.5 文件改动汇总

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `BatchExecuteRequest.java` | 新增字段 | 添加可选字段 `inputDataHeadUrl` |
| `InputDataService.java` | 接口变更 | 方法签名增加 `presignedHeadUrl` 参数 |
| `InputDataServiceImpl.java` | 实现变更 | 新增 `fetchRemoteMeta()`、`downloadZip()`；重写 `getInputDataSet()` 主流程 |
| `ExecutionServiceImpl.java` | 调用适配 | `resolveBatchInputs()` 传递新参数 |
| `ExecuteRequest.java` | 新增字段（可选） | 同步添加 `inputDataHeadUrl`，保持一致性 |

---

## 4. API 变更

### 4.1 批量执行请求（新增可选字段）

```http
POST /execute/batch
Content-Type: application/json
```

```json
{
  "requestId": "batch-001",
  "language": "java17",
  "code": "...",
  "inputDataUrl": "https://oss.example.com/data/1001.zip?sign=GET_SIGN",
  "inputDataHeadUrl": "https://oss.example.com/data/1001.zip?sign=HEAD_SIGN",
  "timeLimit": 2000,
  "memoryLimit": 256
}
```

#### 字段说明

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `inputDataUrl` | String | ✅ | 预签名 GET URL，用于下载 ZIP 输入数据包 |
| `inputDataHeadUrl` | String | ❌ | 预签名 HEAD URL，用于高效探测元数据。缺省时回退为 GET 统一获取 |

### 4.2 向后兼容性

- 不传 `inputDataHeadUrl`：行为与当前版本完全一致
- 传入 `inputDataHeadUrl`：自动启用 HEAD 探测 + 按需 GET 下载

---

## 5. 性能对比

### 缓存命中场景（占绝大多数请求）

| 指标 | 当前（GET 统一） | 改进后（HEAD + GET） |
|------|-----------------|---------------------|
| HTTP 请求数 | 1 (GET) | 1 (HEAD) |
| 网络传输量 | 响应头 + 完整 ZIP body | 响应头（~几百字节） |
| 下载耗时 | 取决于 ZIP 大小 | ≈ 0（仅 RTT） |
| ZIP body 处理 | 下载后丢弃 | 不下载 |

### 缓存未命中场景

| 指标 | 当前（GET 统一） | 改进后（HEAD + GET） |
|------|-----------------|---------------------|
| HTTP 请求数 | 1 (GET) | 2 (HEAD + GET) |
| 网络传输量 | 响应头 + ZIP body | HEAD 响应头 + GET 响应头 + ZIP body |
| 额外开销 | — | 多一次 HEAD RTT（可忽略） |

**结论**：缓存命中时性能大幅提升（从下载完整 ZIP 降为仅一次 HEAD），缓存未命中时仅多一次轻量 HEAD 往返。

---

## 6. 签名服务侧配合

调用方需为同一对象生成两个预签名 URL：

```
// 伪代码示例
String getUrl  = ossClient.presign(bucket, objectKey, Method.GET, expiry);
String headUrl = ossClient.presign(bucket, objectKey, Method.HEAD, expiry);
```

主流对象存储均支持对 HEAD 方法生成预签名：
- **MinIO**: `presignedGetObject` / `getPresignedObjectUrl(Method.HEAD, ...)`
- **AWS S3**: `GeneratePresignedUrlRequest` 支持 `HttpMethod.HEAD`
- **阿里云 OSS**: `generatePresignedUrl` 支持 `HttpMethod.HEAD`

---

## 7. 验收标准

1. **传入双 URL 时**：缓存命中场景只发 HEAD 请求，不发 GET，不下载 ZIP body
2. **传入双 URL 时**：缓存未命中场景先 HEAD 后 GET，下载并落盘成功
3. **不传 `inputDataHeadUrl` 时**：行为与当前版本完全一致（回退路径）
4. **HEAD 返回 403/异常时**：应抛出明确错误，不静默回退到 GET（避免掩盖签名配置问题）
5. **日志区分**：日志中应能区分 HEAD 探测模式 vs GET 回退模式
6. **文档同步**：API 文档、接口注释、06-CACHE-OSS.md 同步更新

---

## 8. 风险与回滚

### 风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 调用方未及时适配，不传 `inputDataHeadUrl` | 无影响，自动回退 | 字段设计为可选 |
| HEAD 签名过期时间与 GET 不一致 | HEAD 通过但 GET 下载时签名过期 | 建议调用方对齐两个 URL 的过期时间 |
| 某些存储不返回 ETag/Last-Modified | 版本比对失败，每次都重新下载 | 已有兜底逻辑：两者都无则视为不一致 |

### 回滚

- 仅当 `inputDataHeadUrl` 为 null 时才走回退逻辑，与当前版本行为一致
- 回滚方案：调用方停止传 `inputDataHeadUrl` 即可，无需服务端发版
