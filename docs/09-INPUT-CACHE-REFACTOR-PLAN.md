# 输入数据缓存体系重构方案

> 状态：**✅ 已实施**
> 日期：2026-02-28
> 完成说明：本方案中的所有改动均已在代码中落地。死代码已清理，4 个 BUG 已修复，并且在实施过程中同步落地了文档 10（签名 GET + ETag 方案），将 HEAD 请求全部改为 GET。

---

## 1. 重构目标

1. **清理技术债**：删除零调用的死代码，消除历史遗留对开发的干扰
2. **修复逻辑缺陷**：消除异常双重包装、下载失败残留目录、`loadFromLocalDisk` 静默吞异常等问题
3. **提高可读性和可维护性**：精简接口、统一 HTTP 客户端、改善代码结构

---

## 2. 现状审计结果

### 2.1 死代码（零外部调用）

以下 7 个方法在**整个代码库中没有任何调用者**（含测试目录）：

| # | 方法 | 所在文件 | 说明 |
|---|------|---------|------|
| 1 | `InputDataService.getObjectKey()` | 接口 + 实现 | 仅转发 `OssUrlParser.extractObjectKey()`，无调用者 |
| 2 | `InputDataService.wrapSingleInput()` | 接口 + 实现 | 单次执行走 `executeSingle()` 内联处理，从未经过此方法 |
| 3 | `InputDataService.evictByObjectKey()` | 接口 + 实现 | 预留的缓存清除入口，无控制器或服务调用 |
| 4 | `InputDataService.isCached()` | 接口 + 实现 | 预留的缓存查询入口，无调用者 |
| 5 | `OssUrlParser.extractBucket()` | 工具类 | 无调用者 |
| 6 | `OssUrlParser.extractObjectPath()` | 工具类 | 无调用者 |
| 7 | `OssUrlParser.toCacheKey()` | 工具类 | 直接 return objectKey 的空实现，无调用者 |

### 2.2 逻辑缺陷

#### BUG-1：`fetchRemoteMeta()` 异常双重包装

```java
private RemoteObjectMeta fetchRemoteMeta(String presignedUrl) {
    HttpURLConnection connection = null;
    try {
        // ...
        if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new RuntimeException("远端对象不存在: " + ...);  // ← 抛出 RuntimeException
        }
        throw new RuntimeException("查询远端对象元数据失败，HTTP 状态码: " + statusCode);  // ← 同上
    } catch (Exception e) {
        // ↑ 会捕获上面的 RuntimeException，再包一层
        throw new RuntimeException("获取远端对象元数据失败: " + e.getMessage(), e);
    }
}
```

**问题**：`catch (Exception e)` 会捕获方法内自己抛出的 `RuntimeException`，导致异常链变为：
`RuntimeException("获取远端对象元数据失败: 查询远端对象元数据失败，HTTP 状态码: 403")`
→ 异常消息嵌套冗余，增加排查困难。

**修复**：catch 中先判断 `if (e instanceof RuntimeException re) throw re;`，或改用 Hutool 后消除此类问题。

#### BUG-2：`downloadAndSaveToDisk()` 失败时残留目录

```java
private InputDataSet downloadAndSaveToDisk(...) {
    try {
        byte[] zipBytes = HttpUtil.downloadBytes(presignedUrl);
        // ...
        Path localDir = getLocalStoragePath(objectKey);
        Files.createDirectories(localDir);           // ← 已创建目录
        TreeMap<Integer, String> inputMap = extractAndSaveToDisk(zipBytes, localDir);
        if (inputMap.isEmpty()) {
            throw new RuntimeException("...");       // ← 抛出异常，但目录已存在
        }
        // ...
    } catch (Exception e) {
        throw new RuntimeException("...", e);        // ← 目录没有清理
    }
}
```

**问题**：如果 ZIP 解压成功但无合法 `.in` 文件，或解压过程中途失败，已创建的目录不会被清除。下次请求时 `Files.exists(localDir)` 为 true，会尝试 `readLocalMeta()` → 返回 null → `isSameVersion` 返回 false → 再次 `deleteDirectory` + 重新下载。虽然最终能恢复，但白白多了一次删除操作，且残留目录在磁盘上无意义地占用空间。

**修复**：在 catch 块中加入 `deleteDirectory(localDir)` 清理。

#### BUG-3：`loadFromLocalDisk()` 静默吞掉单文件读取异常

```java
Files.list(localDir)
    .filter(...)
    .forEach(path -> {
        // ...
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            inputMap.put(index, content);
        } catch (IOException e) {
            log.error("读取输入文件失败: {}", path, e);
            // ← 吞掉异常，该测试用例直接消失
        }
    });
```

**问题**：如果某个 `.in` 文件读取失败，该用例会从结果集中静默消失。调用方收到的 `inputs` 列表长度会少于预期，但无任何感知。

**修复**：改为直接抛出异常终止，让调用方知道数据不完整。

#### BUG-4：`getLocalStoragePath()` 的 `.zip` 移除逻辑脆弱

```java
private Path getLocalStoragePath(String objectKey) {
    String sanitized = objectKey.replace(".zip", "").replace(".ZIP", "");
    return Path.of(storageDir, sanitized);
}
```

**问题**：
- `String.replace` 会替换**所有出现**，而非仅末尾。如 ObjectKey 为 `data.zip-backup/1.zip`，结果为 `data-backup/1`，路径被意外篡改。
- 不处理 `.Zip`、`.ZIP` 等混合大小写。

**修复**：改用正则只移除末尾的 `.zip` 后缀（不区分大小写）。

### 2.3 代码风格问题

| # | 问题 | 位置 |
|---|------|------|
| 1 | `setPermissions` 与 `writeLocalMeta` 之间存在**缩进错误**（多余空格） | `InputDataServiceImpl.java` L215–L216 |
| 2 | `RemoteObjectMeta` record 定义在私有方法区中间，位置不直观 | `InputDataServiceImpl.java` L339 |
| 3 | `fetchRemoteMeta` 用 JDK `HttpURLConnection`，下载用 Hutool `HttpUtil`，HTTP 客户端**不统一** | `InputDataServiceImpl.java` |
| 4 | 接口 Javadoc 描述的行为与实现不一致（缺少版本校验描述） | `InputDataService.java` |
| 5 | `InputDataSet.getInput()` / `size()` 未被任何代码调用，且 `getInput()` 返回 null 而非抛异常 | `InputDataSet.java` |

---

## 3. 改动清单

### 3.1 删除死代码

#### 3.1.1 `InputDataService` 接口

删除 4 个方法声明：`getObjectKey`、`wrapSingleInput`、`evictByObjectKey`、`isCached`。

接口仅保留：

```java
public interface InputDataService {
    InputDataSet getInputDataSet(String presignedUrl);
}
```

#### 3.1.2 `InputDataServiceImpl` 实现

删除 4 个方法实现体：`getObjectKey()`、`wrapSingleInput()`、`evictByObjectKey()`、`isCached()`。

#### 3.1.3 `OssUrlParser` 工具类

删除 3 个无调用的公有方法：`extractBucket()`、`extractObjectPath()`、`toCacheKey()`。

#### 3.1.4 `InputDataSet` DTO

删除 2 个无调用的辅助方法：`size()` 和 `getInput(int index)`。

调用方 `ExecutionServiceImpl.resolveBatchInputs()` 直接使用 `inputDataSet.getInputs()`，从未调用这两个方法。

---

### 3.2 修复 BUG-1：消除 `fetchRemoteMeta()` 异常双重包装

**改为 Hutool `HttpRequest` 统一网络客户端**，同时彻底消除双重包装问题：

```java
private RemoteObjectMeta fetchRemoteMeta(String presignedUrl) {
    try {
        HttpResponse response = HttpRequest.head(presignedUrl)
                .timeout(downloadTimeout)
                .execute();

        int statusCode = response.getStatus();
        if (statusCode == HttpStatus.HTTP_OK) {
            String etag = trimHeader(response.header("ETag"));
            String lastModified = trimHeader(response.header("Last-Modified"));
            return new RemoteObjectMeta(etag, lastModified);
        }
        if (statusCode == HttpStatus.HTTP_NOT_FOUND) {
            throw new RuntimeException("远端对象不存在: " + OssUrlParser.extractObjectKey(presignedUrl));
        }
        throw new RuntimeException("查询远端对象元数据失败，HTTP 状态码: " + statusCode);
    } catch (RuntimeException e) {
        throw e;       // ← 不再二次包装
    } catch (Exception e) {
        throw new RuntimeException("获取远端对象元数据失败: " + e.getMessage(), e);
    }
}
```

额外好处：移除 `java.net.HttpURLConnection` 和 `java.net.URL` 两个 import。

---

### 3.3 修复 BUG-2：下载失败时清理残留目录

在 `downloadAndSaveToDisk()` 的 catch 块中清理已创建的目录：

```java
private InputDataSet downloadAndSaveToDisk(String objectKey, String presignedUrl, RemoteObjectMeta remoteMeta) {
    Path localDir = getLocalStoragePath(objectKey);
    try {
        byte[] zipBytes = HttpUtil.downloadBytes(presignedUrl);
        // ... 解压、写入 ...
    } catch (Exception e) {
        deleteDirectory(localDir);   // ← 新增：失败时清理
        log.error("下载或解压输入数据失败: objectKey={}", objectKey, e);
        throw new RuntimeException("获取输入数据失败: " + e.getMessage(), e);
    }
}
```

---

### 3.4 修复 BUG-3：磁盘读取失败不再静默吞异常

重写 `loadFromLocalDisk()`，将 `forEach` + `try-catch` 改为显式循环，单文件读取失败即抛出异常：

```java
private InputDataSet loadFromLocalDisk(String objectKey, Path localDir) {
    try (var files = Files.list(localDir)) {
        TreeMap<Integer, String> inputMap = new TreeMap<>();

        for (Path path : files.filter(Files::isRegularFile).toList()) {
            String fileName = path.getFileName().toString();
            Matcher matcher = INPUT_FILE_PATTERN.matcher(fileName);
            if (matcher.matches()) {
                int index = Integer.parseInt(matcher.group(1));
                String content = Files.readString(path, StandardCharsets.UTF_8);
                inputMap.put(index, content);
            }
        }

        if (inputMap.isEmpty()) {
            throw new RuntimeException("本地缓存目录中没有有效的输入文件: " + localDir);
        }

        log.info("从本地磁盘加载输入数据: objectKey={}, count={}", objectKey, inputMap.size());
        return InputDataSet.builder()
                .dataId(objectKey)
                .inputs(new ArrayList<>(inputMap.values()))
                .build();
    } catch (IOException e) {
        throw new RuntimeException("加载输入数据失败: " + e.getMessage(), e);
    }
}
```

同时增加 `inputMap.isEmpty()` 检查——如果缓存目录存在但 `.in` 文件为空，直接失败而非返回空列表。

---

### 3.5 修复 BUG-4：`getLocalStoragePath()` 仅移除末尾后缀

```java
// 修复前
private Path getLocalStoragePath(String objectKey) {
    String sanitized = objectKey.replace(".zip", "").replace(".ZIP", "");
    return Path.of(storageDir, sanitized);
}

// 修复后
private static final Pattern ZIP_SUFFIX = Pattern.compile("(?i)\\.zip$");

private Path getLocalStoragePath(String objectKey) {
    String sanitized = ZIP_SUFFIX.matcher(objectKey).replaceFirst("");
    return Path.of(storageDir, sanitized);
}
```

---

### 3.6 修复缩进 + 结构调整

#### 缩进修复

```java
// 修复前
            setPermissions(localDir);
                writeLocalMeta(localDir, remoteMeta);

// 修复后
            setPermissions(localDir);
            writeLocalMeta(localDir, remoteMeta);
```

#### `RemoteObjectMeta` record 移至类底部

将 `private record RemoteObjectMeta(...)` 从私有方法区中间移到类最底部（所有方法之后），符合常量 → 字段 → 公有方法 → 私有方法 → 内部类型的惯例。

---

### 3.7 更新接口 Javadoc

```java
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
    InputDataSet getInputDataSet(String presignedUrl);
}
```

---

## 4. 不在本次范围内

| 项目 | 原因 |
|------|------|
| `BatchExecuteRequest.isUrlInput()` 始终返回 `true` | 属于请求模型，不在缓存体系范围 |
| `ExecutionServiceImpl` 格式/缩进问题 | 属于执行服务，可单独重构 |
| 文档 `07-CORE-IMPLEMENTATION.md` 中残留的 `CacheService` 代码片段 | 已有历史说明标注，后续独立清理 |
| 并发安全（同一 ObjectKey 并行请求可能重复下载） | 当前业务场景下并发度较低，暂不需要加锁 |

---

## 5. 变更文件总览

| # | 文件 | 操作 |
|---|------|------|
| 1 | `InputDataService.java` | 删除 4 个方法声明 + 重写类级 Javadoc |
| 2 | `InputDataServiceImpl.java` | 删除 4 个死方法 + 修复 4 个 BUG + 重写 fetchRemoteMeta + 修复缩进 + 移动 record |
| 3 | `OssUrlParser.java` | 删除 3 个无调用的公有方法 |
| 4 | `InputDataSet.java` | 删除 2 个无调用的辅助方法 |

共 **4 个文件**。

---

## 6. 改动摘要表

| # | 类型 | 改动 | 影响 |
|---|------|------|------|
| 1 | 🗑️ 删除死代码 | 接口 4 方法 + 实现 4 方法 + 工具 3 方法 + DTO 2 方法 | 净删约 90 行 |
| 2 | 🐛 修复 BUG-1 | fetchRemoteMeta 异常双重包装 | 错误日志更清晰 |
| 3 | 🐛 修复 BUG-2 | 下载失败清理残留目录 | 磁盘不残留无效目录 |
| 4 | 🐛 修复 BUG-3 | loadFromLocalDisk 不再静默吞异常 | 缓存损坏时快速失败而非静默丢用例 |
| 5 | 🐛 修复 BUG-4 | getLocalStoragePath 仅移除末尾 .zip | 防止路径被意外篡改 |
| 6 | 🔧 风格统一 | fetchRemoteMeta 改用 Hutool | 全部 HTTP 操作统一 Hutool |
| 7 | 🔧 缩进修复 | writeLocalMeta 缩进 | 可读性 |
| 8 | 🔧 结构调整 | record 移至类底部 | 符合 Java 惯例 |
| 9 | 📝 Javadoc | 接口注释与实现对齐 | 减少理解歧义 |

---

## 7. 验证方式

1. `mvn compile` 编译通过
2. 全局搜索被删方法名，确认无残留引用
3. 功能验证：发起 `/execute/batch` 请求，确认 GET + 缓存命中流程正常
