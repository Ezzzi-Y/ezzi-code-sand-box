# OJ 代码执行沙箱服务 - 缓存与 OSS 集成

## 1. 概述

沙箱服务需要从 OSS 获取测试用例的输入数据。为了提高性能和减少 OSS 访问次数，实现本地缓存机制。

### 1.1 设计目标

- **减少延迟**：避免每次执行都从 OSS 下载
- **节省带宽**：减少网络传输
- **支持更新**：当测试数据更新时能及时刷新缓存
- **容量控制**：限制缓存大小，自动淘汰

### 1.2 缓存策略

- 使用 LRU（最近最少使用）淘汰策略
- 支持基于时间的过期（TTL）
- 支持手动刷新和清空
- 双层缓存：内存索引 + 文件存储

---

## 2. 缓存配置

### 2.1 CacheConfig

```java
package com.github.ezzziy.codesandbox.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.cache.input-data")
public class CacheConfig {

    /**
     * 是否启用缓存
     */
    private Boolean enabled = true;

    /**
     * 缓存基础路径
     */
    private String basePath = "/var/sandbox/cache/input";

    /**
     * 最大缓存数量
     */
    private Integer maxSize = 1000;

    /**
     * 过期时间（小时）
     */
    private Integer expireHours = 24;

    /**
     * 单个文件最大大小（MB）
     */
    private Integer maxFileSizeMB = 10;

    /**
     * 内存索引缓存
     */
    @Bean
    public Cache<String, CacheEntry> cacheIndex() {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofHours(expireHours))
                .recordStats()
                .build();
    }
}
```

### 2.2 缓存条目

```java
package com.github.ezzziy.codesandbox.cache;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 缓存条目元数据
 */
@Data
@Builder
public class CacheEntry {
    
    /**
     * OSS Object Key
     */
    private String objectKey;
    
    /**
     * 本地文件路径
     */
    private String localPath;
    
    /**
     * 文件大小（字节）
     */
    private Long fileSize;
    
    /**
     * OSS ETag（用于验证是否更新）
     */
    private String etag;
    
    /**
     * 缓存时间
     */
    private Instant cachedAt;
    
    /**
     * 最后访问时间
     */
    private Instant lastAccessedAt;
    
    /**
     * 访问次数
     */
    private Long accessCount;
}
```

---

## 3. 缓存服务实现

### 3.1 CacheService 接口

```java
package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.model.response.CacheRefreshResult;
import com.github.ezzziy.codesandbox.model.response.CacheStatus;

import java.util.List;

public interface CacheService {

    /**
     * 获取输入数据
     * @param objectKey OSS Object Key
     * @return 输入数据内容
     */
    String getInputData(String objectKey);

    /**
     * 刷新缓存
     * @param objectKeys 要刷新的 Key 列表
     * @param forceDownload 是否强制下载（即使未过期）
     * @return 刷新结果
     */
    CacheRefreshResult refresh(List<String> objectKeys, Boolean forceDownload);

    /**
     * 清空所有缓存
     */
    void clearAll();

    /**
     * 获取缓存状态
     */
    CacheStatus getStatus();

    /**
     * 预热缓存
     * @param objectKeys 要预热的 Key 列表
     */
    void warmUp(List<String> objectKeys);
}
```

### 3.2 CacheService 实现

```java
package com.github.ezzziy.codesandbox.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.ezzziy.codesandbox.cache.CacheEntry;
import com.github.ezzziy.codesandbox.config.CacheConfig;
import com.github.ezzziy.codesandbox.exception.CacheException;
import com.github.ezzziy.codesandbox.model.response.CacheRefreshResult;
import com.github.ezzziy.codesandbox.model.response.CacheStatus;
import com.github.ezzziy.codesandbox.oss.OSSClient;
import com.github.ezzziy.codesandbox.service.CacheService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    private final Cache<String, CacheEntry> cacheIndex;
    private final CacheConfig cacheConfig;
    private final OSSClient ossClient;
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Path cacheBasePath;

    @PostConstruct
    public void init() throws IOException {
        cacheBasePath = Path.of(cacheConfig.getBasePath());
        Files.createDirectories(cacheBasePath);
        log.info("缓存目录初始化: {}", cacheBasePath);
        
        // 启动时清理过期文件
        cleanupExpiredFiles();
    }

    @Override
    public String getInputData(String objectKey) {
        if (!cacheConfig.getEnabled()) {
            return downloadFromOSS(objectKey);
        }
        
        lock.readLock().lock();
        try {
            // 1. 检查内存索引
            CacheEntry entry = cacheIndex.getIfPresent(objectKey);
            
            if (entry != null) {
                // 2. 验证本地文件存在
                Path localPath = Path.of(entry.getLocalPath());
                if (Files.exists(localPath)) {
                    // 更新访问信息
                    entry.setLastAccessedAt(Instant.now());
                    entry.setAccessCount(entry.getAccessCount() + 1);
                    
                    log.debug("缓存命中: {}", objectKey);
                    return readLocalFile(localPath);
                }
                
                // 文件不存在，移除索引
                cacheIndex.invalidate(objectKey);
            }
        } finally {
            lock.readLock().unlock();
        }
        
        // 3. 缓存未命中，从 OSS 下载
        log.debug("缓存未命中，从 OSS 下载: {}", objectKey);
        return downloadAndCache(objectKey);
    }

    @Override
    public CacheRefreshResult refresh(List<String> objectKeys, Boolean forceDownload) {
        List<CacheRefreshResult.RefreshDetail> details = new ArrayList<>();
        int refreshed = 0;
        int failed = 0;
        
        for (String objectKey : objectKeys) {
            try {
                lock.writeLock().lock();
                try {
                    // 删除现有缓存
                    CacheEntry existing = cacheIndex.getIfPresent(objectKey);
                    if (existing != null) {
                        deleteLocalFile(Path.of(existing.getLocalPath()));
                        cacheIndex.invalidate(objectKey);
                    }
                    
                    // 重新下载（如果需要）
                    if (Boolean.TRUE.equals(forceDownload)) {
                        downloadAndCache(objectKey);
                    }
                } finally {
                    lock.writeLock().unlock();
                }
                
                details.add(CacheRefreshResult.RefreshDetail.builder()
                        .objectKey(objectKey)
                        .success(true)
                        .message("refreshed")
                        .build());
                refreshed++;
                
            } catch (Exception e) {
                log.error("刷新缓存失败: {}", objectKey, e);
                details.add(CacheRefreshResult.RefreshDetail.builder()
                        .objectKey(objectKey)
                        .success(false)
                        .message(e.getMessage())
                        .build());
                failed++;
            }
        }
        
        return CacheRefreshResult.builder()
                .refreshed(refreshed)
                .failed(failed)
                .details(details)
                .build();
    }

    @Override
    public void clearAll() {
        lock.writeLock().lock();
        try {
            // 清空内存索引
            cacheIndex.invalidateAll();
            
            // 删除所有缓存文件
            if (Files.exists(cacheBasePath)) {
                Files.walk(cacheBasePath)
                        .filter(Files::isRegularFile)
                        .forEach(this::deleteLocalFile);
            }
            
            log.info("缓存已清空");
        } catch (IOException e) {
            log.error("清空缓存失败", e);
            throw new CacheException("清空缓存失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CacheStatus getStatus() {
        return CacheStatus.builder()
                .enabled(cacheConfig.getEnabled())
                .size((int) cacheIndex.estimatedSize())
                .maxSize(cacheConfig.getMaxSize())
                .basePath(cacheConfig.getBasePath())
                .hitRate(cacheIndex.stats().hitRate())
                .hitCount(cacheIndex.stats().hitCount())
                .missCount(cacheIndex.stats().missCount())
                .build();
    }

    @Override
    public void warmUp(List<String> objectKeys) {
        log.info("开始预热缓存，数量: {}", objectKeys.size());
        
        for (String objectKey : objectKeys) {
            try {
                getInputData(objectKey);
            } catch (Exception e) {
                log.warn("预热缓存失败: {}", objectKey, e);
            }
        }
        
        log.info("缓存预热完成");
    }

    /**
     * 从 OSS 下载并缓存
     */
    private String downloadAndCache(String objectKey) {
        lock.writeLock().lock();
        try {
            // 再次检查（double-check）
            CacheEntry existing = cacheIndex.getIfPresent(objectKey);
            if (existing != null && Files.exists(Path.of(existing.getLocalPath()))) {
                return readLocalFile(Path.of(existing.getLocalPath()));
            }
            
            // 下载数据
            OSSClient.OSSObject ossObject = ossClient.getObject(objectKey);
            String content = ossObject.getContent();
            
            // 检查文件大小
            if (content.length() > cacheConfig.getMaxFileSizeMB() * 1024 * 1024) {
                log.warn("文件过大，不缓存: {} ({}MB)", objectKey, 
                        content.length() / 1024 / 1024);
                return content;
            }
            
            // 保存到本地
            Path localPath = generateLocalPath(objectKey);
            Files.createDirectories(localPath.getParent());
            Files.writeString(localPath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            // 更新索引
            CacheEntry entry = CacheEntry.builder()
                    .objectKey(objectKey)
                    .localPath(localPath.toString())
                    .fileSize((long) content.length())
                    .etag(ossObject.getEtag())
                    .cachedAt(Instant.now())
                    .lastAccessedAt(Instant.now())
                    .accessCount(1L)
                    .build();
            
            cacheIndex.put(objectKey, entry);
            
            log.debug("已缓存: {} -> {}", objectKey, localPath);
            return content;
            
        } catch (IOException e) {
            log.error("缓存文件失败: {}", objectKey, e);
            throw new CacheException("缓存文件失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 直接从 OSS 下载（不缓存）
     */
    private String downloadFromOSS(String objectKey) {
        return ossClient.getObject(objectKey).getContent();
    }

    /**
     * 读取本地文件
     */
    private String readLocalFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CacheException("读取缓存文件失败: " + path, e);
        }
    }

    /**
     * 删除本地文件
     */
    private void deleteLocalFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", path, e);
        }
    }

    /**
     * 生成本地缓存路径
     */
    private Path generateLocalPath(String objectKey) {
        // 使用 objectKey 的 hash 作为文件名，避免路径过长
        String hash = hashString(objectKey);
        String prefix = hash.substring(0, 2);  // 前两位作为子目录
        String fileName = hash + ".cache";
        
        return cacheBasePath.resolve(prefix).resolve(fileName);
    }

    /**
     * 计算字符串 hash
     */
    private String hashString(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            throw new RuntimeException("Hash 计算失败", e);
        }
    }

    /**
     * 清理过期文件
     */
    private void cleanupExpiredFiles() {
        try {
            if (!Files.exists(cacheBasePath)) return;
            
            Instant expireTime = Instant.now().minusSeconds(
                    cacheConfig.getExpireHours() * 3600L);
            
            Files.walk(cacheBasePath)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant()
                                    .isBefore(expireTime);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(this::deleteLocalFile);
                    
            log.info("过期缓存清理完成");
        } catch (IOException e) {
            log.warn("清理过期缓存失败", e);
        }
    }
}
```

---

## 4. OSS 客户端

### 4.1 OSSClient 接口

```java
package com.github.ezzziy.codesandbox.oss;

import lombok.Builder;
import lombok.Data;

/**
 * OSS 客户端接口
 */
public interface OSSClient {

    /**
     * 获取对象
     */
    OSSObject getObject(String objectKey);

    /**
     * 检查对象是否存在
     */
    boolean objectExists(String objectKey);

    /**
     * 获取对象元数据
     */
    OSSObjectMetadata getObjectMetadata(String objectKey);

    /**
     * 健康检查
     */
    boolean healthCheck();

    /**
     * OSS 对象
     */
    @Data
    @Builder
    class OSSObject {
        private String objectKey;
        private String content;
        private String etag;
        private Long contentLength;
        private String contentType;
    }

    /**
     * OSS 对象元数据
     */
    @Data
    @Builder
    class OSSObjectMetadata {
        private String objectKey;
        private String etag;
        private Long contentLength;
        private String contentType;
        private java.time.Instant lastModified;
    }
}
```

### 4.2 MinIO 实现

```java
package com.github.ezzziy.codesandbox.oss;

import com.github.ezzziy.codesandbox.config.OSSConfig;
import com.github.ezzziy.codesandbox.exception.OSSException;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sandbox.oss.type", havingValue = "minio")
public class MinIOClientImpl implements OSSClient {

    private final OSSConfig ossConfig;
    private MinioClient minioClient;

    @PostConstruct
    public void init() {
        minioClient = MinioClient.builder()
                .endpoint(ossConfig.getEndpoint())
                .credentials(ossConfig.getAccessKey(), ossConfig.getSecretKey())
                .build();
        
        log.info("MinIO 客户端初始化完成: endpoint={}, bucket={}", 
                ossConfig.getEndpoint(), ossConfig.getBucket());
        
        // 确保 bucket 存在
        ensureBucketExists();
    }

    @Override
    public OSSObject getObject(String objectKey) {
        try {
            GetObjectResponse response = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .object(objectKey)
                            .build()
            );
            
            String content = readInputStream(response);
            
            return OSSObject.builder()
                    .objectKey(objectKey)
                    .content(content)
                    .etag(response.headers().get("ETag"))
                    .contentLength(Long.parseLong(
                            response.headers().get("Content-Length")))
                    .contentType(response.headers().get("Content-Type"))
                    .build();
                    
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new OSSException("对象不存在: " + objectKey);
            }
            throw new OSSException("获取对象失败: " + objectKey, e);
        } catch (Exception e) {
            throw new OSSException("获取对象失败: " + objectKey, e);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new OSSException("检查对象存在失败: " + objectKey, e);
        } catch (Exception e) {
            throw new OSSException("检查对象存在失败: " + objectKey, e);
        }
    }

    @Override
    public OSSObjectMetadata getObjectMetadata(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .object(objectKey)
                            .build()
            );
            
            return OSSObjectMetadata.builder()
                    .objectKey(objectKey)
                    .etag(stat.etag())
                    .contentLength(stat.size())
                    .contentType(stat.contentType())
                    .lastModified(stat.lastModified().toInstant())
                    .build();
                    
        } catch (Exception e) {
            throw new OSSException("获取对象元数据失败: " + objectKey, e);
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .build()
            );
            return true;
        } catch (Exception e) {
            log.error("MinIO 健康检查失败", e);
            return false;
        }
    }

    /**
     * 确保 bucket 存在
     */
    private void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(ossConfig.getBucket())
                            .build()
            );
            
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(ossConfig.getBucket())
                                .build()
                );
                log.info("创建 bucket: {}", ossConfig.getBucket());
            }
        } catch (Exception e) {
            log.error("检查/创建 bucket 失败", e);
        }
    }

    /**
     * 读取输入流
     */
    private String readInputStream(InputStream is) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new OSSException("读取数据失败", e);
        }
    }
}
```

### 4.3 阿里云 OSS 实现

```java
package com.github.ezzziy.codesandbox.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.github.ezzziy.codesandbox.config.OSSConfig;
import com.github.ezzziy.codesandbox.exception.OSSException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sandbox.oss.type", havingValue = "aliyun")
public class AliyunOSSClientImpl implements OSSClient {

    private final OSSConfig ossConfig;
    private OSS ossClient;

    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder().build(
                ossConfig.getEndpoint(),
                ossConfig.getAccessKey(),
                ossConfig.getSecretKey()
        );
        log.info("阿里云 OSS 客户端初始化完成: endpoint={}, bucket={}",
                ossConfig.getEndpoint(), ossConfig.getBucket());
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    @Override
    public OSSClient.OSSObject getObject(String objectKey) {
        try {
            OSSObject ossObject = ossClient.getObject(ossConfig.getBucket(), objectKey);
            
            String content;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(ossObject.getObjectContent(), StandardCharsets.UTF_8))) {
                content = reader.lines().collect(Collectors.joining("\n"));
            }
            
            return OSSClient.OSSObject.builder()
                    .objectKey(objectKey)
                    .content(content)
                    .etag(ossObject.getObjectMetadata().getETag())
                    .contentLength(ossObject.getObjectMetadata().getContentLength())
                    .contentType(ossObject.getObjectMetadata().getContentType())
                    .build();
                    
        } catch (Exception e) {
            throw new OSSException("获取对象失败: " + objectKey, e);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        return ossClient.doesObjectExist(ossConfig.getBucket(), objectKey);
    }

    @Override
    public OSSObjectMetadata getObjectMetadata(String objectKey) {
        try {
            var metadata = ossClient.getObjectMetadata(ossConfig.getBucket(), objectKey);
            
            return OSSObjectMetadata.builder()
                    .objectKey(objectKey)
                    .etag(metadata.getETag())
                    .contentLength(metadata.getContentLength())
                    .contentType(metadata.getContentType())
                    .lastModified(metadata.getLastModified().toInstant())
                    .build();
                    
        } catch (Exception e) {
            throw new OSSException("获取对象元数据失败: " + objectKey, e);
        }
    }

    @Override
    public boolean healthCheck() {
        try {
            return ossClient.doesBucketExist(ossConfig.getBucket());
        } catch (Exception e) {
            log.error("阿里云 OSS 健康检查失败", e);
            return false;
        }
    }
}
```

---

## 5. OSS 配置

### 5.1 OSSConfig

```java
package com.github.ezzziy.codesandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.oss")
public class OSSConfig {

    /**
     * OSS 类型：minio / aliyun
     */
    private String type = "minio";

    /**
     * 端点地址
     */
    private String endpoint = "http://localhost:9000";

    /**
     * Access Key
     */
    private String accessKey;

    /**
     * Secret Key
     */
    private String secretKey;

    /**
     * Bucket 名称
     */
    private String bucket = "oj-testcases";

    /**
     * 连接超时（毫秒）
     */
    private Integer connectTimeout = 10000;

    /**
     * 读取超时（毫秒）
     */
    private Integer readTimeout = 30000;

    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;
}
```

### 5.2 配置文件示例

```yaml
sandbox:
  oss:
    type: minio
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket: oj-testcases
    connect-timeout: 10000
    read-timeout: 30000
    max-retries: 3

  cache:
    input-data:
      enabled: true
      base-path: /var/sandbox/cache/input
      max-size: 1000
      expire-hours: 24
      max-file-size-mb: 10
```

---

## 6. 缓存响应模型

### 6.1 CacheRefreshResult

```java
package com.github.ezzziy.codesandbox.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CacheRefreshResult {
    
    /**
     * 成功刷新数量
     */
    private Integer refreshed;
    
    /**
     * 失败数量
     */
    private Integer failed;
    
    /**
     * 详细信息
     */
    private List<RefreshDetail> details;
    
    @Data
    @Builder
    public static class RefreshDetail {
        private String objectKey;
        private Boolean success;
        private String message;
    }
}
```

### 6.2 CacheStatus

```java
package com.github.ezzziy.codesandbox.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CacheStatus {
    
    /**
     * 是否启用
     */
    private Boolean enabled;
    
    /**
     * 当前缓存数量
     */
    private Integer size;
    
    /**
     * 最大缓存数量
     */
    private Integer maxSize;
    
    /**
     * 缓存基础路径
     */
    private String basePath;
    
    /**
     * 命中率
     */
    private Double hitRate;
    
    /**
     * 命中次数
     */
    private Long hitCount;
    
    /**
     * 未命中次数
     */
    private Long missCount;
}
```

---

## 7. 健康检查集成

### 7.1 HealthService

```java
package com.github.ezzziy.codesandbox.service;

import com.github.dockerjava.api.DockerClient;
import com.github.ezzziy.codesandbox.model.response.CacheStatus;
import com.github.ezzziy.codesandbox.model.response.HealthStatus;
import com.github.ezzziy.codesandbox.oss.OSSClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    private final DockerClient dockerClient;
    private final OSSClient ossClient;
    private final CacheService cacheService;
    
    @Value("${sandbox.oss.type}")
    private String ossType;
    
    @Value("${sandbox.oss.bucket}")
    private String ossBucket;

    public HealthStatus check() {
        return HealthStatus.builder()
                .status(determineOverallStatus())
                .components(HealthStatus.Components.builder()
                        .docker(checkDocker())
                        .oss(checkOSS())
                        .cache(checkCache())
                        .build())
                .uptime(getUptime())
                .build();
    }

    private String determineOverallStatus() {
        try {
            boolean dockerOk = checkDocker().getStatus().equals("UP");
            boolean ossOk = checkOSS().getStatus().equals("UP");
            return (dockerOk && ossOk) ? "UP" : "DEGRADED";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private HealthStatus.ComponentStatus checkDocker() {
        try {
            var info = dockerClient.infoCmd().exec();
            return HealthStatus.ComponentStatus.builder()
                    .status("UP")
                    .version(info.getServerVersion())
                    .apiVersion(info.getApiVersion())
                    .build();
        } catch (Exception e) {
            log.error("Docker 健康检查失败", e);
            return HealthStatus.ComponentStatus.builder()
                    .status("DOWN")
                    .error(e.getMessage())
                    .build();
        }
    }

    private HealthStatus.ComponentStatus checkOSS() {
        try {
            boolean healthy = ossClient.healthCheck();
            return HealthStatus.ComponentStatus.builder()
                    .status(healthy ? "UP" : "DOWN")
                    .type(ossType)
                    .bucket(ossBucket)
                    .build();
        } catch (Exception e) {
            log.error("OSS 健康检查失败", e);
            return HealthStatus.ComponentStatus.builder()
                    .status("DOWN")
                    .type(ossType)
                    .error(e.getMessage())
                    .build();
        }
    }

    private HealthStatus.ComponentStatus checkCache() {
        CacheStatus status = cacheService.getStatus();
        return HealthStatus.ComponentStatus.builder()
                .status("UP")
                .size(status.getSize())
                .maxSize(status.getMaxSize())
                .hitRate(String.format("%.2f%%", status.getHitRate() * 100))
                .build();
    }

    private Long getUptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
}
```

### 7.2 HealthStatus 模型

```java
package com.github.ezzziy.codesandbox.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HealthStatus {
    
    private String status;
    private Components components;
    private Long uptime;
    
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Components {
        private ComponentStatus docker;
        private ComponentStatus oss;
        private ComponentStatus cache;
    }
    
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ComponentStatus {
        private String status;
        private String version;
        private String apiVersion;
        private String type;
        private String bucket;
        private Integer size;
        private Integer maxSize;
        private String hitRate;
        private String error;
    }
}
```

---

## 8. 测试用例数据结构

### 8.1 OSS 目录结构

```
oj-testcases/
├── questions/
│   ├── 1001/
│   │   ├── testcases/
│   │   │   ├── 1.in       # 第 1 个测试用例输入
│   │   │   ├── 1.out      # 第 1 个测试用例输出
│   │   │   ├── 2.in
│   │   │   ├── 2.out
│   │   │   └── ...
│   │   └── metadata.json  # 题目元数据
│   ├── 1002/
│   │   └── ...
│   └── ...
└── contests/
    ├── 2024-spring/
    │   └── ...
    └── ...
```

### 8.2 metadata.json 示例

```json
{
  "questionId": 1001,
  "title": "A+B Problem",
  "testcaseCount": 10,
  "timeLimit": 1000,
  "memoryLimit": 256,
  "testcases": [
    {"id": 1, "inputKey": "questions/1001/testcases/1.in", "outputKey": "questions/1001/testcases/1.out"},
    {"id": 2, "inputKey": "questions/1001/testcases/2.in", "outputKey": "questions/1001/testcases/2.out"}
  ]
}
```

---

## 9. 下一步

下一篇文档将提供 **核心代码实现汇总**，包括：
- 完整的执行流程代码
- 服务层实现
- 异常处理
- 单元测试示例

详见 [07-CORE-IMPLEMENTATION.md](07-CORE-IMPLEMENTATION.md)
