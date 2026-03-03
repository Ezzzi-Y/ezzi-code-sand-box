# 版本号驱动缓存方案：去除 HEAD 探测，由判题服务携带数据版本号

> 状态：**✅ 已实施**
> 日期：2026-03-02
> 完成日期：2026-03-03
> 前置文档：[10-SIGNED-GET-ETAG-SOLUTION.md](10-SIGNED-GET-ETAG-SOLUTION.md)、[12-DUAL-URL-REFACTOR-PLAN.md](12-DUAL-URL-REFACTOR-PLAN.md)
> 完成说明：所有代码和文档已按方案落地。已删除 `inputDataHeadUrl` 字段和 HEAD 探测逻辑，新增 `inputDataVersion` 版本号驱动缓存，文档 12 已标记为废弃。

---

## 1. 问题背景

### 1.1 历史演进

| 阶段 | 方案 | 文档 | 问题 |
|------|------|------|------|
| 最初 | HEAD 探测 ETag → 按需 GET 下载 | — | 预签名 URL 绑定 GET，HEAD 返回 403 |
| 文档 10 | 统一 GET：下载 ZIP 后从响应头读 ETag | [10](10-SIGNED-GET-ETAG-SOLUTION.md) | 缓存命中时白白下载整个 ZIP |
| 文档 12 | 双 URL：`inputDataHeadUrl`（HEAD）+ `inputDataUrl`（GET） | [12](12-DUAL-URL-REFACTOR-PLAN.md) | **阿里云 OSS 不支持 HEAD 方法**，即使对 HEAD 生成预签名 URL 也无效 |

### 1.2 当前痛点

- **阿里云 OSS 不支持对预签名 URL 发起 HEAD 请求**，文档 12 的双 URL 方案无法在阿里云 OSS 上落地。
- 退回到文档 10 的 GET 统一获取模式，则每次请求都下载完整 ZIP，缓存命中时带宽全部浪费。
- 沙箱服务不应承担「探测远端数据是否变更」的职责——这属于上游判题服务的领域知识。

### 1.3 根因分析

**根因是架构层面的职责错配**：让沙箱自己去探测存储服务（OSS）的数据版本，导致沙箱需要理解对象存储的协议细节（HEAD、ETag、Last-Modified）。这些协议细节在不同云厂商间存在差异（如阿里云 OSS vs MinIO vs AWS S3），最终导致兼容性问题反复出现。

**正确的做法**：判题服务（数据的管理者）在下发任务时告知沙箱数据的版本号，沙箱只做版本字符串比对，**完全不关心版本号是怎么来的**。

---

## 2. 方案设计：版本号驱动缓存

### 2.1 核心思路

1. **判题服务**在任务请求中携带 `inputDataVersion` 字段（建议为 ZIP 文件的 sha256 摘要，也可以是任何对判题服务有意义的版本标识符）
2. **沙箱服务**收到请求后：
   - 以 `objectKey` 定位本地缓存目录
   - 读取本地 `_meta.properties` 中的 `version` 字段
   - 与请求中的 `inputDataVersion` 做**字符串精确比较**
   - 一致 → 直接读取本地缓存（零下载）
   - 不一致或无缓存 → GET 下载 ZIP → 解压落盘 → 写入新 version
3. **沙箱不再发起任何 HEAD 请求，不再解析 ETag / Last-Modified**

### 2.2 目标流程

```
getInputDataSet(getUrl, version)
  │
  ├─ 1. 从 getUrl 提取 ObjectKey → 定位本地缓存目录
  │
  ├─ 2. 读取本地 _meta.properties 中的 version
  │       │
  │       ├─ version 一致 → 直接返回本地数据 ✅ (零下载、零网络请求)
  │       └─ version 不一致或无缓存 ↓
  │
  ├─ 3. GET getUrl ──► 200 ──► zipBytes
  │
  └─ 4. 解压落盘 + 写入 _meta.properties (version=inputDataVersion)
```

### 2.3 与旧方案的关键差异

| 维度 | 旧方案（HEAD/GET 探测） | 新方案（版本号驱动） |
|------|------------------------|---------------------|
| 版本来源 | 由沙箱从 OSS 响应头获取（ETag/Last-Modified） | 由判题服务在请求中携带 |
| 缓存命中时网络开销 | 至少 1 次 HEAD 或 GET 请求 | **零网络请求** |
| 云厂商兼容性 | 依赖 HEAD 支持、ETag 格式等 | **完全无关** |
| 沙箱职责 | 需理解对象存储协议 | **仅做字符串比对** |
| 判题服务职责 | 无需关心版本 | 需计算并传递版本号 |

### 2.4 兼容性策略

`inputDataVersion` 为**可选字段**：

| `inputDataVersion` | `inputDataUrl` | 行为 |
|---------------------|----------------|------|
| ✅ 提供 | ✅ 提供 | 版本比对 + 按需 GET 下载（**最优，零探测开销**） |
| ❌ 未提供 | ✅ 提供 | 回退：每次 GET 下载 ZIP → 从响应头读 ETag 做版本比对（等同文档 10 行为） |

这保证了：
- **已有调用方**无需任何改动即可继续工作（回退到 GET 统一获取）
- **新调用方**传入 `inputDataVersion` 后自动获得最优缓存性能

---

## 3. 改动清单

### 3.1 DTO 改动

#### 3.1.1 `BatchExecuteRequest.java`

- **删除** `inputDataHeadUrl` 字段（HEAD 探测已废弃）
- **新增** `inputDataVersion` 字段

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchExecuteRequest {

    // ... 现有字段不变 ...

    @NotBlank(message = "inputDataUrl 不能为空，且必须是 zip 文件 URL")
    private String inputDataUrl;

    // ❌ 删除：private String inputDataHeadUrl;

    /**
     * 输入数据版本号（如 sha256 摘要），用于本地缓存比对。
     * <p>
     * 可选字段。提供后沙箱直接做版本字符串比对，缓存命中时零下载开销。
     * 缺省时回退为 GET 统一获取（从响应头读取 ETag 做版本比对）。
     * </p>
     */
    private String inputDataVersion;

    // ...
}
```

---

### 3.2 接口层改动

#### 3.2.1 `InputDataService.java`

方法签名变更，第二个参数从 `presignedHeadUrl` 改为 `inputDataVersion`：

```java
public interface InputDataService {

    /**
     * 从预签名 URL 获取输入数据集
     *
     * @param presignedGetUrl    签名绑定 GET 的预签名 URL，用于下载 ZIP
     * @param inputDataVersion   输入数据版本号（如 sha256），用于本地缓存比对。
     *                           为 null 时回退为 GET 统一获取（从响应头读 ETag）
     * @return 输入数据集
     */
    InputDataSet getInputDataSet(String presignedGetUrl, String inputDataVersion);
}
```

---

### 3.3 实现层改动

#### 3.3.1 `InputDataServiceImpl.java` — 核心变更

##### A. 删除的方法

| 方法 | 原因 |
|------|------|
| `fetchRemoteMeta(String presignedHeadUrl)` | HEAD 探测逻辑完全废弃 |
| `downloadZip(String presignedGetUrl)` | 不再需要独立的 ZIP 下载方法，合入主流程或复用 `fetchRemoteObject` |

##### B. 保留的方法（改造）

| 方法 | 改造内容 |
|------|---------|
| `fetchRemoteObject(String presignedUrl)` | 保留，回退模式使用（GET 获取 ZIP + 从响应头读 ETag） |
| `isSameVersion(...)` | **重写**：新增对 `version` 字符串的精确比较逻辑 |
| `downloadAndSaveToDisk(...)` | 签名调整，接受 version 字符串而非 `RemoteObjectMeta` |
| `writeLocalMeta(...)` | 改为写入 `version` 字段（而非 etag + lastModified） |
| `readLocalMeta(...)` | 改为读取 `version` 字段 |

##### C. 内部类型变更

| 类型 | 改造 |
|------|------|
| `RemoteObjectMeta(etag, lastModified)` | 改为 `CacheVersionInfo(String version)`，或直接用 `String version` |
| `RemoteFetchResult(byte[] zipBytes, RemoteObjectMeta meta)` | 保留，回退模式使用。meta 中 etag 作为回退模式的 version |

##### D. 重写主流程 `getInputDataSet`

```java
@Override
public InputDataSet getInputDataSet(String presignedGetUrl, String inputDataVersion) {
    String objectKey = OssUrlParser.extractObjectKey(presignedGetUrl);
    log.debug("从 URL 提取 ObjectKey: {}", objectKey);

    boolean hasVersion = inputDataVersion != null && !inputDataVersion.isBlank();
    Path localDir = getLocalStoragePath(objectKey);

    // ─── 路径 A：有版本号，走高效版本比对（零网络请求） ───
    if (hasVersion) {
        if (Files.exists(localDir)) {
            String localVersion = readLocalVersion(localDir);
            if (inputDataVersion.equals(localVersion)) {
                log.info("版本号一致，复用本地缓存: objectKey={}, version={}",
                        objectKey, inputDataVersion);
                return loadFromLocalDisk(objectKey, localDir);
            }
            log.info("版本号不一致，准备重新下载: objectKey={}, localVersion={}, remoteVersion={}",
                    objectKey, localVersion, inputDataVersion);
            deleteDirectory(localDir);
        }

        // 缓存未命中，发起 GET 下载
        log.info("开始下载输入数据: objectKey={}", objectKey);
        byte[] zipBytes = downloadZipOnly(presignedGetUrl);
        return saveToDiskAndReturn(objectKey, zipBytes, inputDataVersion);
    }

    // ─── 路径 B：无版本号，回退到 GET 统一获取（从响应头读 ETag 做版本比对） ───
    RemoteFetchResult remoteFetch = fetchRemoteObject(presignedGetUrl);
    String effectiveVersion = deriveVersionFromMeta(remoteFetch.meta());

    if (Files.exists(localDir)) {
        String localVersion = readLocalVersion(localDir);
        if (effectiveVersion != null && effectiveVersion.equals(localVersion)) {
            log.info("GET 响应头版本一致，复用本地缓存（回退模式）: objectKey={}, version={}",
                    objectKey, effectiveVersion);
            return loadFromLocalDisk(objectKey, localDir);
        }
        log.info("GET 响应头版本不一致，准备重新落盘: objectKey={}", objectKey);
        deleteDirectory(localDir);
    }

    log.info("开始使用已获取的 ZIP 内容落盘: objectKey={}", objectKey);
    return saveToDiskAndReturn(objectKey, remoteFetch.zipBytes(), effectiveVersion);
}

/**
 * 从远端元数据中推导版本字符串（回退模式用）
 * 优先使用 ETag，其次 Last-Modified
 */
private String deriveVersionFromMeta(RemoteObjectMeta meta) {
    if (meta == null) return null;
    if (meta.etag() != null) return meta.etag();
    return meta.lastModified();
}
```

##### E. 本地元数据格式变更

`_meta.properties` 从：

```properties
# 旧格式
etag=d41d8cd98f00b204e9800998ecf8427e
lastModified=Mon, 02 Mar 2026 10:00:00 GMT
updatedAt=1709370000000
```

变为：

```properties
# 新格式
version=a1b2c3d4e5f6...（sha256 或其他版本标识）
updatedAt=1709370000000
```

对应方法改造：

```java
private void writeLocalVersion(Path localDir, String version) {
    if (version == null) return;
    Properties properties = new Properties();
    properties.setProperty("version", version);
    properties.setProperty("updatedAt", String.valueOf(System.currentTimeMillis()));

    Path metaFile = localDir.resolve(META_FILE_NAME);
    try (var writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
        properties.store(writer, "sandbox input cache metadata");
    } catch (IOException e) {
        log.warn("写入本地元数据失败: {}", metaFile, e);
    }
}

private String readLocalVersion(Path localDir) {
    Path metaFile = localDir.resolve(META_FILE_NAME);
    if (!Files.exists(metaFile)) return null;
    Properties properties = new Properties();
    try (var reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
        properties.load(reader);
        // 兼容旧格式：如果没有 version 字段，尝试读 etag
        String version = properties.getProperty("version");
        if (version == null) {
            version = properties.getProperty("etag");
        }
        return trimHeader(version);
    } catch (IOException e) {
        log.warn("读取本地元数据失败: {}", metaFile, e);
        return null;
    }
}
```

> **兼容性说明**：`readLocalVersion` 在读不到 `version` 时回退读 `etag`，确保旧格式的缓存在回退模式下不会失效。首次以版本号模式写入后，后续一律使用新格式。

---

### 3.4 调用链改动

#### `ExecutionServiceImpl.resolveBatchInputs`

将 `inputDataVersion` 传递到 `InputDataService`：

```java
private List<String> resolveBatchInputs(BatchExecuteRequest request) {
    if (request.getInputDataUrl() == null || request.getInputDataUrl().isBlank()) {
        throw new IllegalArgumentException("批量执行必须提供 inputDataUrl（zip 文件 URL）");
    }
    InputDataSet inputDataSet = inputDataService.getInputDataSet(
            request.getInputDataUrl(),
            request.getInputDataVersion()    // 版本号，可为 null
    );
    return inputDataSet.getInputs() == null ? new ArrayList<>() : inputDataSet.getInputs();
}
```

---

### 3.5 删除的代码

| 项目 | 位置 | 说明 |
|------|------|------|
| `fetchRemoteMeta()` 方法 | `InputDataServiceImpl` | HEAD 探测逻辑，完全废弃 |
| `downloadZip()` 方法 | `InputDataServiceImpl` | 独立 GET 下载（路径 A 专用），可合并为更简单的 `downloadZipOnly()` |
| `isSameVersion(RemoteObjectMeta, RemoteObjectMeta)` 方法 | `InputDataServiceImpl` | 旧版本比较逻辑（双 meta 比对），改为字符串比较 |
| `readLocalMeta()` / `writeLocalMeta()` 方法 | `InputDataServiceImpl` | 替换为 `readLocalVersion()` / `writeLocalVersion()` |
| `RemoteObjectMeta` record | `InputDataServiceImpl` | **保留但仅在回退模式内部使用**，不再暴露到主流程 |
| `inputDataHeadUrl` 字段 | `BatchExecuteRequest` | 删除 |

---

### 3.6 文件改动汇总

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `BatchExecuteRequest.java` | 字段变更 | 删除 `inputDataHeadUrl`，新增 `inputDataVersion` |
| `InputDataService.java` | 接口变更 | 方法签名第二个参数从 `presignedHeadUrl` 改为 `inputDataVersion` |
| `InputDataServiceImpl.java` | 实现重构 | 删除 HEAD 探测、重写主流程与元数据读写 |
| `ExecutionServiceImpl.java` | 调用适配 | `resolveBatchInputs()` 传递 `inputDataVersion` |

---

## 4. API 变更

### 4.1 批量执行请求

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
  "inputDataVersion": "a1b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef1234567890",
  "timeLimit": 2000,
  "memoryLimit": 256
}
```

#### 字段说明

| 字段 | 类型 | 必填 | 描述 |
|------|------|------|------|
| `inputDataUrl` | String | ✅ | 预签名 GET URL，用于下载 ZIP 输入数据包 |
| `inputDataVersion` | String | ❌ | 输入数据版本号（推荐 sha256），用于本地缓存比对。缺省时回退为 GET 统一获取 |

#### ❌ 已删除字段

| 字段 | 说明 |
|------|------|
| `inputDataHeadUrl` | 不再需要 HEAD 预签名 URL，已移除 |

### 4.2 向后兼容性

- **不传 `inputDataVersion`**：行为与文档 10 完全一致（GET 统一获取，从响应头读 ETag 比对）
- **传入 `inputDataVersion`**：自动启用版本号比对 + 按需 GET 下载
- **传入旧的 `inputDataHeadUrl`**：字段被忽略（JSON 反序列化时无对应字段，不会报错）

---

## 5. 性能对比

### 缓存命中场景（占绝大多数请求）

| 指标 | GET 统一（文档 10） | HEAD + GET（文档 12） | **版本号驱动（本方案）** |
|------|---------------------|----------------------|------------------------|
| HTTP 请求数 | 1 (GET) | 1 (HEAD) | **0** |
| 网络传输量 | 完整 ZIP body | ~几百字节头部 | **0** |
| 耗时 | 下载 ZIP | 1 RTT | **纯本地读取（μs 级）** |
| 云厂商依赖 | ETag 格式需一致 | 需支持 HEAD | **无** |

### 缓存未命中场景

| 指标 | GET 统一（文档 10） | HEAD + GET（文档 12） | **版本号驱动（本方案）** |
|------|---------------------|----------------------|------------------------|
| HTTP 请求数 | 1 (GET) | 2 (HEAD + GET) | **1 (GET)** |
| 网络传输量 | ZIP body | HEAD 头 + ZIP body | **ZIP body** |
| 额外开销 | — | 多一次 HEAD RTT | **无** |

**结论**：
- 缓存命中时，本方案是**零网络请求**，显著优于前两个方案。
- 缓存未命中时，本方案与 GET 统一方案一致（1 次 GET），优于文档 12 的 2 次请求。

---

## 6. 判题服务侧配合

判题服务在构造批量执行请求时，需要额外提供 `inputDataVersion`：

```python
# 伪代码示例（判题服务侧）
import hashlib

# 方式一：文件上传时计算 sha256 并持久化
with open("1001.zip", "rb") as f:
    sha256 = hashlib.sha256(f.read()).hexdigest()

# 方式二：从数据库/缓存读取已知版本号
sha256 = db.get_input_data_version(problem_id)

request = {
    "language": "java17",
    "code": user_code,
    "inputDataUrl": oss_client.presign(bucket, "1001.zip", method="GET"),
    "inputDataVersion": sha256,   # 新增
    "timeLimit": 2000,
    "memoryLimit": 256
}
sandbox_client.execute_batch(request)
```

**版本号来源建议**：
- **推荐**：ZIP 文件的 sha256 摘要（上传时计算，存入数据库）
- **可选**：递增的整数版本号（每次更新 +1）
- **可选**：数据更新时间戳字符串（精度需足够）

沙箱不关心版本号的含义，只做字符串精确比较。

---

## 7. 缓存格式迁移兼容

### 7.1 旧 `_meta.properties` 格式

```properties
etag="d41d8cd98f00b204e9800998ecf8427e"
lastModified=Mon, 02 Mar 2026 10:00:00 GMT
updatedAt=1709370000000
```

### 7.2 新 `_meta.properties` 格式

```properties
version=a1b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef1234567890
updatedAt=1709370000000
```

### 7.3 迁移策略

- `readLocalVersion()` 优先读取 `version` 字段，读不到时回退读 `etag`
- 回退模式（无 `inputDataVersion`）下从 ETag 推导版本号，写入时统一使用 `version` 字段
- 首次以新格式写入后，旧字段自然被覆盖
- **无需手动迁移**，系统自动平滑过渡

---

## 8. 验收标准

1. **传入 `inputDataVersion` 时**：
   - 首次请求：下载 ZIP、解压落盘、写入 `_meta.properties`（含 `version` 字段）
   - 后续同版本请求：**零网络请求**，直接读取本地缓存
   - 版本变更后：重新下载 ZIP 并更新缓存

2. **不传 `inputDataVersion` 时**：
   - 行为与当前版本完全一致（GET 统一获取，从响应头读 ETag 比对）

3. **`inputDataHeadUrl` 字段已删除**：
   - 传入该字段不会导致报错（JSON 反序列化忽略未知字段）

4. **日志区分**：
   - 日志中能区分「版本号驱动模式」vs「GET 回退模式」

5. **`_meta.properties` 兼容**：
   - 旧格式缓存在回退模式下仍可正常读取
   - 新写入一律使用新格式

---

## 9. 需同步更新的文档

| 文档 | 更新内容 |
|------|---------|
| [02-API-DESIGN.md](02-API-DESIGN.md) | 批量执行请求字段说明：删除 `inputDataHeadUrl`，新增 `inputDataVersion` |
| [06-CACHE-OSS.md](06-CACHE-OSS.md) | 整体重写缓存机制描述（版本号驱动 + GET 回退） |
| [12-DUAL-URL-REFACTOR-PLAN.md](12-DUAL-URL-REFACTOR-PLAN.md) | 标记为 **❌ 已废弃**，注明被本文档取代 |

---

## 10. 风险与回滚

### 风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 判题服务未传 `inputDataVersion` | 无影响，自动回退 GET 统一获取 | 字段设计为可选 |
| 判题服务传入错误/过期版本号 | 缓存永远不命中（每次都下载） | 不影响正确性，仅影响性能；日志可排查 |
| 版本号变了但 ZIP 内容未变 | 无害的额外下载 | 对正确性无影响 |
| 版本号没变但 ZIP 内容变了 | 使用了旧数据（**严重**） | 需要判题服务保证版本号与数据内容的一致性 |

### 回滚

- 调用方停止传 `inputDataVersion` 即可回退到 GET 统一获取模式，无需沙箱服务端发版
- 如需完全回滚代码，恢复 `inputDataHeadUrl` 字段和 HEAD 探测逻辑即可

---

## 11. 与文档 12 的决策对比

| 维度 | 文档 12（双 URL） | 本方案（版本号驱动） |
|------|-------------------|---------------------|
| 阿里云 OSS 兼容性 | ❌ 不兼容 | ✅ 完全兼容 |
| 缓存命中网络开销 | 1 次 HEAD | **0 次** |
| 缓存未命中网络开销 | 2 次（HEAD + GET） | **1 次（GET）** |
| 沙箱复杂度 | 高（HEAD + GET 双路径） | **低（版本比对 + GET）** |
| 判题服务改动 | 需生成双签名 URL | 需传版本号 |
| 版本可靠性 | 依赖 OSS ETag 实现 | 由判题服务保证 |

**文档 12 标记为 ❌ 已废弃。**
