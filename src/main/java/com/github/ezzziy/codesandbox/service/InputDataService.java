package com.github.ezzziy.codesandbox.service;

import cn.hutool.http.HttpUtil;
import com.github.ezzziy.codesandbox.model.dto.InputDataSet;
import com.github.ezzziy.codesandbox.util.OssUrlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * 负责获取输入数据：
 * 1. 直接返回传入的单个输入（不缓存）
 * 2. 从预签名 URL 下载 ZIP 并解压，按 ObjectKey 缓存避免重复下载
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InputDataService {

    private final CacheService cacheService;

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
        // 1. 从 URL 提取 ObjectKey 作为缓存 key
        String objectKey = OssUrlParser.extractObjectKey(presignedUrl);
        log.debug("从 URL 提取 ObjectKey: {}", objectKey);

        // 2. 检查本地磁盘是否存在
        Path localDir = getLocalStoragePath(objectKey);
        if (Files.exists(localDir)) {
            log.info("本地磁盘已存在，跳过下载: {}", localDir);
            return loadFromLocalDisk(objectKey, localDir);
        }

        // 3. 本地不存在，下载并解压到磁盘
        log.info("本地磁盘不存在，开始下载: objectKey={}", objectKey);
        return downloadAndSaveToDisk(objectKey, presignedUrl);
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
     * 删除指定 ObjectKey 的本地数据和缓存
     * <p>
     * 当后端更新了输入数据后调用此方法清除，保证数据一致性
     * </p>
     *
     * @param objectKey 对象键（格式：bucket/path/to/object）
     */
    public void evictByObjectKey(String objectKey) {
        // 删除本地磁盘文件
        Path localDir = getLocalStoragePath(objectKey);
        try {
            if (Files.exists(localDir)) {
                Files.walk(localDir)
                        .sorted((a, b) -> -a.compareTo(b)) // 先删除文件，再删除目录
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("删除文件失败: {}", path, e);
                            }
                        });
                log.info("已删除本地数据: {}", localDir);
            }
        } catch (IOException e) {
            log.error("删除本地数据失败: objectKey={}", objectKey, e);
        }

        // 删除内存缓存
        cacheService.evictInputData(objectKey);
        log.info("已删除缓存: objectKey={}", objectKey);
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
        // objectKey 格式：bucket/path/to/object.zip
        // 本地路径：/var/sandbox-inputs/bucket/path/to/object/
        String sanitized = objectKey.replace(".zip", "").replace(".ZIP", "");
        return Path.of(storageDir, sanitized);
    }

    /**
     * 从本地磁盘加载输入数据
     */
    private InputDataSet loadFromLocalDisk(String objectKey, Path localDir) {
        try {
            // 按序号读取所有 .in 文件
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
    private InputDataSet downloadAndSaveToDisk(String objectKey, String presignedUrl) {
        try {
            // 1. 下载 ZIP 文件
            byte[] zipBytes = HttpUtil.downloadBytes(presignedUrl);

            if (zipBytes == null || zipBytes.length == 0) {
                throw new RuntimeException("下载输入数据失败: 响应为空");
            }

            if (zipBytes.length > maxFileSize) {
                throw new RuntimeException("输入数据包过大: " + zipBytes.length + " bytes, 限制: " + maxFileSize);
            }

            log.debug("下载完成: objectKey={}, size={} bytes", objectKey, zipBytes.length);

            // 2. 创建本地目录
            Path localDir = getLocalStoragePath(objectKey);
            Files.createDirectories(localDir);

            // 3. 解压 ZIP 到本地目录
            TreeMap<Integer, String> inputMap = extractAndSaveToDisk(zipBytes, localDir);

            if (inputMap.isEmpty()) {
                throw new RuntimeException("输入数据包中没有有效的输入文件（需要 1.in, 2.in... 格式）");
            }

            // 4. 设置目录和文件权限（确保容器内 nobody 用户可读）
            setPermissions(localDir);

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

                // 获取文件名（去除路径）
                String fileName = entry.getName();
                int lastSlash = fileName.lastIndexOf('/');
                if (lastSlash >= 0) {
                    fileName = fileName.substring(lastSlash + 1);
                }

                // 匹配输入文件名
                Matcher matcher = INPUT_FILE_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    int index = Integer.parseInt(matcher.group(1));

                    // 读取文件内容
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }

                    String content = baos.toString(StandardCharsets.UTF_8);
                    inputMap.put(index, content);

                    // 保存到磁盘
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
            // 设置目录权限 755
            dir.toFile().setReadable(true, false);
            dir.toFile().setExecutable(true, false);
            dir.toFile().setWritable(true, true);

            // 设置所有文件权限 644
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

    /**
     * 隐藏 URL 中的敏感信息（用于日志）
     */
    private String maskUrl(String url) {
        if (url == null || url.length() < 50) {
            return url;
        }
        return url.substring(0, 50) + "...";
    }
}
