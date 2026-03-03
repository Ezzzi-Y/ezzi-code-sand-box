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
 * 支持版本号驱动模式（由调用方携带版本号，零网络探测）；也兼容仅 GET 的回退模式。
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
     * 路径 A（有 inputDataVersion）：纯本地版本字符串比对，缓存命中时零网络请求<br/>
     * 路径 B（无 inputDataVersion）：GET 统一获取元数据和 ZIP 内容（回退模式，从响应头读 ETag）
     * </p>
     */
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

        // ─── 路径 B：无版本号，回退到 GET 统一获取（从响应头读 ETag） ───
        RemoteFetchResult remoteFetch = fetchRemoteObject(presignedGetUrl);
        String effectiveVersion = deriveVersionFromMeta(remoteFetch.meta());

        if (Files.exists(localDir)) {
            String localVersion = readLocalVersion(localDir);
            if (effectiveVersion != null && effectiveVersion.equals(localVersion)) {
                log.info("GET 响应头版本一致，复用本地缓存（回退模式）: objectKey={}, version={}",
                        objectKey, effectiveVersion);
                return loadFromLocalDisk(objectKey, localDir);
            }
            log.info("GET 响应头版本不一致，准备重新落盘: objectKey={}, localVersion={}, remoteVersion={}",
                    objectKey, localVersion, effectiveVersion);
            deleteDirectory(localDir);
        }

        log.info("开始使用已获取的 ZIP 内容落盘: objectKey={}", objectKey);
        return saveToDiskAndReturn(objectKey, remoteFetch.zipBytes(), effectiveVersion);
    }

    // ==================== 远端数据获取 ====================

    /**
     * 通过 GET 请求仅下载 ZIP 内容
     * <p>
     * 在版本号驱动模式下，缓存未命中时调用，单独获取 ZIP body。
     * </p>
     */
    private byte[] downloadZipOnly(String presignedGetUrl) {
        try {
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
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("下载远端对象失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对预签名 URL 发起 GET 请求，同时获取 ZIP 内容及 ETag/Last-Modified（回退模式）
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
     * 从远端元数据中推导版本字符串（回退模式用）
     * <p>
     * 优先使用 ETag，其次 Last-Modified。
     * </p>
     */
    private String deriveVersionFromMeta(RemoteObjectMeta meta) {
        if (meta == null) return null;
        if (meta.etag() != null) return meta.etag();
        return meta.lastModified();
    }

    // ==================== 下载与解压 ====================

    /**
     * 将 ZIP 内容解压保存到本地磁盘
     * <p>
     * 如果解压过程中失败，会清理已创建的目录，避免残留无效缓存。
     * </p>
     */
    private InputDataSet saveToDiskAndReturn(String objectKey, byte[] zipBytes, String version) {
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
            writeLocalVersion(localDir, version);

            List<String> inputs = new ArrayList<>(inputMap.values());
            log.info("输入数据已下载到本地: objectKey={}, path={}, count={}, version={}",
                    objectKey, localDir, inputs.size(), version);

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

    /**
     * 写入版本信息到本地元数据文件
     */
    private void writeLocalVersion(Path localDir, String version) {
        if (version == null) {
            return;
        }
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

    /**
     * 读取本地缓存的版本号
     * <p>
     * 优先读取 {@code version} 字段，读不到时兼容旧格式回退读 {@code etag}。
     * </p>
     */
    private String readLocalVersion(Path localDir) {
        Path metaFile = localDir.resolve(META_FILE_NAME);
        if (!Files.exists(metaFile)) {
            return null;
        }
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
            // 优先读 version，兼容旧格式回退读 etag
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

    /** GET 回退模式内部使用：携带 ZIP 内容和响应头元数据 */
    private record RemoteFetchResult(byte[] zipBytes, RemoteObjectMeta meta) {
    }

    /** GET 回退模式内部使用：从响应头提取的 ETag / Last-Modified */
    private record RemoteObjectMeta(String etag, String lastModified) {
    }
}
