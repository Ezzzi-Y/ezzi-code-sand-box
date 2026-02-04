package com.github.ezzziy.codesandbox.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * OSS 预签名 URL 解析工具
 * <p>
 * 支持从 MinIO、阿里云 OSS、AWS S3 等预签名 URL 中提取 ObjectKey
 * </p>
 *
 * @author ezzziy
 */
public class OssUrlParser {

    private OssUrlParser() {
    }

    /**
     * 从预签名 URL 中提取 ObjectKey
     * <p>
     * 支持的 URL 格式：
     * <ul>
     *   <li>MinIO: http://host:port/bucket/object-key?X-Amz-...</li>
     *   <li>阿里云 OSS: https://bucket.oss-region.aliyuncs.com/object-key?OSSAccessKeyId=...</li>
     *   <li>AWS S3: https://bucket.s3.region.amazonaws.com/object-key?X-Amz-...</li>
     *   <li>AWS S3 Path Style: https://s3.region.amazonaws.com/bucket/object-key?X-Amz-...</li>
     * </ul>
     * </p>
     *
     * @param presignedUrl 预签名 URL
     * @return ObjectKey（格式：bucket/object-key 或 object-key）
     * @throws IllegalArgumentException 如果 URL 格式无效
     */
    public static String extractObjectKey(String presignedUrl) {
        if (presignedUrl == null || presignedUrl.isBlank()) {
            throw new IllegalArgumentException("预签名 URL 不能为空");
        }

        try {
            URI uri = URI.create(presignedUrl);
            String host = uri.getHost();
            String path = uri.getPath();

            if (path == null || path.isEmpty() || "/".equals(path)) {
                throw new IllegalArgumentException("无法从 URL 中提取 ObjectKey: " + presignedUrl);
            }

            // URL 解码路径
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);

            // 移除开头的斜杠
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            // 判断 URL 类型并提取 ObjectKey
            if (isAliyunOss(host)) {
                // 阿里云 OSS: bucket 在域名中，path 就是 object-key
                // https://bucket.oss-cn-hangzhou.aliyuncs.com/path/to/object
                String bucket = extractBucketFromAliyunHost(host);
                return bucket + "/" + path;

            } else if (isAwsS3VirtualHost(host)) {
                // AWS S3 Virtual Host Style: bucket 在域名中
                // https://bucket.s3.region.amazonaws.com/path/to/object
                String bucket = extractBucketFromS3VirtualHost(host);
                return bucket + "/" + path;

            } else {
                // MinIO 或 AWS S3 Path Style: bucket 在路径中
                // http://host:port/bucket/path/to/object
                // path 已经包含 bucket/object-key
                return path;
            }

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析预签名 URL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 ObjectKey 中提取 Bucket 名称
     */
    public static String extractBucket(String objectKey) {
        if (objectKey == null || !objectKey.contains("/")) {
            return null;
        }
        return objectKey.substring(0, objectKey.indexOf('/'));
    }

    /**
     * 从 ObjectKey 中提取纯对象路径（不含 bucket）
     */
    public static String extractObjectPath(String objectKey) {
        if (objectKey == null || !objectKey.contains("/")) {
            return objectKey;
        }
        return objectKey.substring(objectKey.indexOf('/') + 1);
    }

    /**
     * 判断是否为阿里云 OSS 域名
     */
    private static boolean isAliyunOss(String host) {
        return host != null && host.contains(".aliyuncs.com");
    }

    /**
     * 判断是否为 AWS S3 Virtual Host Style
     */
    private static boolean isAwsS3VirtualHost(String host) {
        return host != null && host.contains(".s3.") && host.contains(".amazonaws.com");
    }

    /**
     * 从阿里云 OSS 域名中提取 bucket
     * bucket.oss-cn-hangzhou.aliyuncs.com -> bucket
     */
    private static String extractBucketFromAliyunHost(String host) {
        int dotIndex = host.indexOf('.');
        if (dotIndex > 0) {
            return host.substring(0, dotIndex);
        }
        throw new IllegalArgumentException("无法从阿里云 OSS 域名中提取 bucket: " + host);
    }

    /**
     * 从 AWS S3 Virtual Host 域名中提取 bucket
     * bucket.s3.region.amazonaws.com -> bucket
     */
    private static String extractBucketFromS3VirtualHost(String host) {
        int dotIndex = host.indexOf('.');
        if (dotIndex > 0) {
            return host.substring(0, dotIndex);
        }
        throw new IllegalArgumentException("无法从 S3 域名中提取 bucket: " + host);
    }

    /**
     * 生成用于缓存的 Key（基于 ObjectKey 的哈希）
     * <p>
     * 避免 ObjectKey 过长或包含特殊字符
     * </p>
     */
    public static String toCacheKey(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        // 直接使用 objectKey，因为它已经是唯一标识
        // 如果需要更短的 key，可以使用 hash
        return objectKey;
    }
}
