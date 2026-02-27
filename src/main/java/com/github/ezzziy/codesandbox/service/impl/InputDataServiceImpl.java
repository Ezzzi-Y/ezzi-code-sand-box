package com.github.ezzziy.codesandbox.service.impl;

import cn.hutool.http.HttpUtil;
import com.github.ezzziy.codesandbox.model.dto.InputDataSet;
import com.github.ezzziy.codesandbox.service.InputDataService;
import com.github.ezzziy.codesandbox.util.OssUrlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入数据服务
 * <p>
 * 负责获取输入数据：
 * 1. 直接返回传入的单个输入（不缓存）
 * 2. 从预签名 URL 下载 ZIP 并解压，按 ObjectKey 在本地磁盘缓存
 *    （输入数据第一次下载到本地磁盘，除非通过接口显式要求更新）
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputDataServiceImpl implements InputDataService {

    private static final String META_FILE_NAME = "_meta.properties";

    @Value("${sandbox.input-data.storage-dir:/var/lib/sandbox-inputs}")
    private String storageDir;

    @Value("${sandbox.input-data.download-timeout:30000}")
    private int downloadTimeout;

    @Value("${sandbox.input-data.max-file-size:10485760}")
    private int maxFileSize; // 10MB

    /**
     * 匹配输入文件名的正则：数字.in
     */
    private static final Pattern INPUT_FILE_PATTERN = Pattern.compile("^(\\d+)\\.in$");

    /**
     * 从预签名 URL 获取输入数据集
     * <p>
     * 1. 检查本地磁盘是否已存在（基于 ObjectKey）
     * 2. 不存在则下载 ZIP 并解压到本地磁盘
     * 3. 读取所有输入文件内容返回
     * </p>
     *
     * @param presignedUrl 预签名 URL（MinIO/AliyunOSS/S3）
     * @return 输入数据集
     */
    public InputDataSet getInputDataSet(String presignedUrl) {
        String objectKey = OssUrlParser.extractObjectKey(presignedUrl);
        log.debug("从 URL 提取 ObjectKey: {}", objectKey);

        RemoteObjectMeta remoteMeta = fetchRemoteMeta(presignedUrl);

        Path localDir = getLocalStoragePath(objectKey);
        if (Files.exists(localDir)) {
            RemoteObjectMeta localMeta = readLocalMeta(localDir);
            if (isSameVersion(localMeta, remoteMeta)) {
                log.info("远端版本未变化，复用本地缓存: objectKey={}, etag={}, lastModified={}",
                        objectKey, remoteMeta.etag(), remoteMeta.lastModified());
                return loadFromLocalDisk(objectKey, localDir);
            }

            log.info("远端版本已变化，准备重新拉取: objectKey={}, localEtag={}, remoteEtag={}",
                    objectKey,
                    localMeta != null ? localMeta.etag() : null,
                    remoteMeta.etag());
            deleteDirectory(localDir);
        }

        log.info("开始下载输入数据: objectKey={}", objectKey);
        return downloadAndSaveToDisk(objectKey, presignedUrl, remoteMeta);
    }

    /**
     * 获取 ObjectKey（用于外部查询缓存状态）
     *
     * @param presignedUrl 预签名 URL
     * @return ObjectKey
     */
    public String getObjectKey(String presignedUrl) {
        return OssUrlParser.extractObjectKey(presignedUrl);
    }

    /**
     * 将单个输入包装为数据集（不缓存）
     *
     * @param input 单个输入内容
     * @return 只包含一个输入的数据集
     */
    public InputDataSet wrapSingleInput(String input) {
        return InputDataSet.builder()
                .dataId(null)
                .inputs(Collections.singletonList(input != null ? input : ""))
                .build();
    }

    /**
     * 删除指定 ObjectKey 的本地数据
     * <p>
     * 当后端更新了输入数据后调用此方法清除，保证数据一致性
     * </p>
     *
     * @param objectKey 对象键（格式：bucket/path/to/object）
     */
    public void evictByObjectKey(String objectKey) {
        Path localDir = getLocalStoragePath(objectKey);
        deleteDirectory(localDir);
    }

    /**
     * 检查指定 ObjectKey 是否已在本地磁盘
     *
     * @param objectKey 对象键
     * @return 是否存在
     */
    public boolean isCached(String objectKey) {
        return Files.exists(getLocalStoragePath(objectKey));
    }

    /**
     * 获取本地存储路径
     */
    private Path getLocalStoragePath(String objectKey) {
        String sanitized = objectKey.replace(".zip", "").replace(".ZIP", "");
        return Path.of(storageDir, sanitized);
    }

    /**
     * 从本地磁盘加载输入数据
     */
    private InputDataSet loadFromLocalDisk(String objectKey, Path localDir) {
        try {
            TreeMap<Integer, String> inputMap = new TreeMap<>();
            
            Files.list(localDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".in"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        Matcher matcher = INPUT_FILE_PATTERN.matcher(fileName);
                        if (matcher.matches()) {
                            int index = Integer.parseInt(matcher.group(1));
                            try {
                                String content = Files.readString(path, StandardCharsets.UTF_8);
                                inputMap.put(index, content);
                            } catch (IOException e) {
                                log.error("读取输入文件失败: {}", path, e);
                            }
                        }
                    });

            List<String> inputs = new ArrayList<>(inputMap.values());
            log.info("从本地磁盘加载输入数据: objectKey={}, count={}", objectKey, inputs.size());

            return InputDataSet.builder()
                    .dataId(objectKey)
                    .inputs(inputs)
                    .build();

        } catch (IOException e) {
            log.error("从本地磁盘加载失败: objectKey={}", objectKey, e);
            throw new RuntimeException("加载输入数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载并保存到本地磁盘
     */
    private InputDataSet downloadAndSaveToDisk(String objectKey, String presignedUrl, RemoteObjectMeta remoteMeta) {
        try {
            byte[] zipBytes = HttpUtil.downloadBytes(presignedUrl);

            if (zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("下载输入数据失败: 响应为空");
            }

            if (zipBytes.length > maxFileSize) {
                throw new RuntimeException("输入数据包过大: " + zipBytes.length + " bytes, 限制: " + maxFileSize);
            }

            log.debug("下载完成: objectKey={}, size={} bytes", objectKey, zipBytes.length);

            Path localDir = getLocalStoragePath(objectKey);
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
            log.error("下载或解压输入数据失败: objectKey={}", objectKey, e);
            throw new RuntimeException("获取输入数据失败: " + e.getMessage(), e);
        }
    }

    private RemoteObjectMeta fetchRemoteMeta(String presignedUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(presignedUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(downloadTimeout);
            connection.setReadTimeout(downloadTimeout);
            connection.connect();

            int statusCode = connection.getResponseCode();
            if (statusCode == HttpURLConnection.HTTP_OK) {
                String etag = trimHeader(connection.getHeaderField("ETag"));
                String lastModified = trimHeader(connection.getHeaderField("Last-Modified"));
                return new RemoteObjectMeta(etag, lastModified);
            }
            if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new RuntimeException("远端对象不存在: " + OssUrlParser.extractObjectKey(presignedUrl));
            }
            throw new RuntimeException("查询远端对象元数据失败，HTTP 状态码: " + statusCode);
        } catch (Exception e) {
            throw new RuntimeException("获取远端对象元数据失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

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

    private record RemoteObjectMeta(String etag, String lastModified) {
    }

    /**
     * 从 ZIP 压缩包中提取输入文件并保存到磁盘
     * <p>
     * 按文件名序号排序（1.in, 2.in, 3.in...）
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

}
