package com.github.ezzziy.codesandbox.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import com.github.ezzziy.codesandbox.model.dto.InputDataSet;
import com.github.ezzziy.codesandbox.service.InputDataService;
import com.github.ezzziy.codesandbox.util.OssUrlParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入数据服务
 * <p>
 * 从预签名 URL 下载 ZIP 输入数据包并解压，按 ObjectKey 在本地磁盘缓存。
 * 每次请求通过 GET 获取远端响应头中的 ETag/Last-Modified，比对后决定是否复用缓存。
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
public class InputDataServiceImpl implements InputDataService {

    private static final String META_FILE_NAME = "_meta.properties";

    /** 匹配输入文件名：数字.in（如 1.in, 2.in, 10.in） */
    private static final Pattern INPUT_FILE_PATTERN = Pattern.compile("^(\\d+)\\.in$");

    /** 仅移除末尾的 .zip 后缀（不区分大小写） */
    private static final Pattern ZIP_SUFFIX = Pattern.compile("(?i)\\.zip$");

    @Value("${sandbox.input-data.storage-dir:/var/lib/sandbox-inputs}")
    private String storageDir;

    @Value("${sandbox.input-data.download-timeout:30000}")
    private int downloadTimeout;

    @Value("${sandbox.input-data.max-file-size:10485760}")
    private int maxFileSize; // 10MB

    // ==================== 公有方法 ====================

    /**
     * 从预签名 URL 获取输入数据集
     * <p>
     * 1. 提取 ObjectKey，定位本地缓存目录
     * 2. GET 请求获取远端元数据（ETag/Last-Modified）与 ZIP 内容
     * 3. 版本一致 → 从磁盘加载；不一致 → 重新下载
     * </p>
     */
    @Override
    public InputDataSet getInputDataSet(String presignedUrl) {
        String objectKey = OssUrlParser.extractObjectKey(presignedUrl);
        log.debug("从 URL 提取 ObjectKey: {}", objectKey);

        RemoteFetchResult remoteFetch = fetchRemoteObject(presignedUrl);
        RemoteObjectMeta remoteMeta = remoteFetch.meta();

        Path localDir = getLocalStoragePath(objectKey);
        if (Files.exists(localDir)) {
            RemoteObjectMeta localMeta = readLocalMeta(localDir);
            if (isSameVersion(localMeta, remoteMeta)) {
                log.info("远端版本未变化，复用本地缓存: objectKey={}, method=GET, etag={}, lastModified={}",
                        objectKey, remoteMeta.etag(), remoteMeta.lastModified());
                return loadFromLocalDisk(objectKey, localDir);
            }

            log.info("远端版本已变化，准备重新拉取: objectKey={}, method=GET, localEtag={}, remoteEtag={}",
                    objectKey,
                    localMeta != null ? localMeta.etag() : null,
                    remoteMeta.etag());
            deleteDirectory(localDir);
        }

        log.info("开始下载输入数据: objectKey={}", objectKey);
        return downloadAndSaveToDisk(objectKey, remoteFetch.zipBytes(), remoteMeta);
    }

    // ==================== 远端元数据 ====================

    /**
     * 对预签名 URL 发起 GET 请求，获取 ZIP 内容及 ETag/Last-Modified
     */
    private RemoteFetchResult fetchRemoteObject(String presignedUrl) {
        try {
            try (HttpResponse response = HttpRequest.get(presignedUrl)
                    .timeout(downloadTimeout)
                    .execute()) {
                int statusCode = response.getStatus();
                if (statusCode == HttpStatus.HTTP_OK) {
                    String etag = trimHeader(response.header("ETag"));
                    String lastModified = trimHeader(response.header("Last-Modified"));
                    byte[] zipBytes = response.bodyBytes();
                    return new RemoteFetchResult(zipBytes, new RemoteObjectMeta(etag, lastModified));
                }
                if (statusCode == HttpStatus.HTTP_NOT_FOUND) {
                    throw new RuntimeException("远端对象不存在: " + OssUrlParser.extractObjectKey(presignedUrl));
                }
                throw new RuntimeException("查询远端对象失败，HTTP 状态码: " + statusCode);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("获取远端对象失败: " + e.getMessage(), e);
        }
    }

    /**
     * 比较本地与远端元数据版本是否一致
     * <p>
     * 优先比较 ETag，ETag 缺失时比较 Last-Modified，两者都不可用则视为不一致。
     * </p>
     */
    private boolean isSameVersion(RemoteObjectMeta localMeta, RemoteObjectMeta remoteMeta) {
        if (localMeta == null || remoteMeta == null) {
            return false;
        }
        if (localMeta.etag() != null && remoteMeta.etag() != null) {
            return Objects.equals(localMeta.etag(), remoteMeta.etag());
        }
        if (localMeta.lastModified() != null && remoteMeta.lastModified() != null) {
            return Objects.equals(localMeta.lastModified(), remoteMeta.lastModified());
        }
        return false;
    }

    // ==================== 下载与解压 ====================

    /**
     * 将 ZIP 内容解压保存到本地磁盘
     * <p>
     * 如果解压过程中失败，会清理已创建的目录，避免残留无效缓存。
     * </p>
     */
    private InputDataSet downloadAndSaveToDisk(String objectKey, byte[] zipBytes, RemoteObjectMeta remoteMeta) {
        Path localDir = getLocalStoragePath(objectKey);
        try {
            if (zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("下载输入数据失败: 响应为空");
            }

            if (zipBytes.length > maxFileSize) {
                throw new RuntimeException("输入数据包过大: " + zipBytes.length + " bytes, 限制: " + maxFileSize);
            }

            log.debug("下载完成: objectKey={}, size={} bytes", objectKey, zipBytes.length);

            Files.createDirectories(localDir);

            TreeMap<Integer, String> inputMap = extractAndSaveToDisk(zipBytes, localDir);

            if (inputMap.isEmpty()) {
                throw new RuntimeException("输入数据包中没有有效的输入文件（需要 1.in, 2.in... 格式）");
            }

            setPermissions(localDir);
            writeLocalMeta(localDir, remoteMeta);

            List<String> inputs = new ArrayList<>(inputMap.values());
            log.info("输入数据已下载到本地: objectKey={}, path={}, count={}",
                    objectKey, localDir, inputs.size());

            return InputDataSet.builder()
                    .dataId(objectKey)
                    .inputs(inputs)
                    .build();

        } catch (Exception e) {
            // 失败时清理已创建的目录，避免残留无效缓存
            deleteDirectory(localDir);
            log.error("解压输入数据失败: objectKey={}", objectKey, e);
            throw new RuntimeException("获取输入数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 ZIP 压缩包中提取输入文件并保存到磁盘
     * <p>
     * 仅提取匹配 {@code N.in} 格式的文件，按序号排序。
     * </p>
     */
    private TreeMap<Integer, String> extractAndSaveToDisk(byte[] zipBytes, Path targetDir) throws IOException {
        TreeMap<Integer, String> inputMap = new TreeMap<>();

        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8.name())) {

            ZipArchiveEntry entry;
            while ((entry = zis.getNextZipEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                // 提取纯文件名（忽略 ZIP 内的目录结构）
                String fileName = entry.getName();
                int lastSlash = fileName.lastIndexOf('/');
                if (lastSlash >= 0) {
                    fileName = fileName.substring(lastSlash + 1);
                }

                Matcher matcher = INPUT_FILE_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    int index = Integer.parseInt(matcher.group(1));

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }

                    String content = baos.toString(StandardCharsets.UTF_8);
                    inputMap.put(index, content);

                    Path targetFile = targetDir.resolve(fileName);
                    Files.writeString(targetFile, content, StandardCharsets.UTF_8);

                    log.debug("保存输入文件: {} -> {}", fileName, targetFile);
                }
            }
        }

        return inputMap;
    }

    // ==================== 本地磁盘读取 ====================

    /**
     * 从本地缓存目录加载输入数据
     * <p>
     * 如果任何 .in 文件读取失败，立即抛出异常（不静默跳过），
     * 确保调用方不会收到不完整的数据。
     * </p>
     */
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

    // ==================== 本地元数据（_meta.properties） ====================

    private void writeLocalMeta(Path localDir, RemoteObjectMeta remoteMeta) {
        if (remoteMeta == null) {
            return;
        }
        Properties properties = new Properties();
        if (remoteMeta.etag() != null) {
            properties.setProperty("etag", remoteMeta.etag());
        }
        if (remoteMeta.lastModified() != null) {
            properties.setProperty("lastModified", remoteMeta.lastModified());
        }
        properties.setProperty("updatedAt", String.valueOf(System.currentTimeMillis()));

        Path metaFile = localDir.resolve(META_FILE_NAME);
        try (var writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
            properties.store(writer, "sandbox input cache metadata");
        } catch (IOException e) {
            log.warn("写入本地元数据失败: {}", metaFile, e);
        }
    }

    private RemoteObjectMeta readLocalMeta(Path localDir) {
        Path metaFile = localDir.resolve(META_FILE_NAME);
        if (!Files.exists(metaFile)) {
            return null;
        }
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return new RemoteObjectMeta(
                    trimHeader(properties.getProperty("etag")),
                    trimHeader(properties.getProperty("lastModified"))
            );
        } catch (IOException e) {
            log.warn("读取本地元数据失败: {}", metaFile, e);
            return null;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 根据 ObjectKey 计算本地存储路径
     * <p>
     * 仅移除末尾的 .zip 后缀（不区分大小写），避免误替换路径中间出现的 ".zip" 字样。
     * </p>
     */
    private Path getLocalStoragePath(String objectKey) {
        String sanitized = ZIP_SUFFIX.matcher(objectKey).replaceFirst("");
        return Path.of(storageDir, sanitized);
    }

    private String trimHeader(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void deleteDirectory(Path localDir) {
        try {
            if (!Files.exists(localDir)) {
                return;
            }
            Files.walk(localDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {}", path, e);
                        }
                    });
            log.info("已删除本地数据: {}", localDir);
        } catch (IOException e) {
            log.error("删除本地数据失败: path={}", localDir, e);
        }
    }

    /**
     * 设置目录和文件权限，确保容器内 nobody 用户可读
     */
    private void setPermissions(Path dir) {
        try {
            dir.toFile().setReadable(true, false);
            dir.toFile().setExecutable(true, false);
            dir.toFile().setWritable(true, true);

            Files.list(dir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            file.toFile().setReadable(true, false);
                            file.toFile().setWritable(true, true);
                        } catch (Exception e) {
                            log.warn("设置文件权限失败: {}", file, e);
                        }
                    });

        } catch (Exception e) {
            log.warn("设置目录权限失败: {}", dir, e);
        }
    }

    // ==================== 内部类型 ====================

    private record RemoteFetchResult(byte[] zipBytes, RemoteObjectMeta meta) {
    }

    private record RemoteObjectMeta(String etag, String lastModified) {
    }
}
