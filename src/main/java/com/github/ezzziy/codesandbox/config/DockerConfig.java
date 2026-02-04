package com.github.ezzziy.codesandbox.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Docker 客户端配置
 * <p>
 * 提供 Docker 客户端的 Bean 配置，支持连接本地或远程 Docker Daemon
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Configuration
public class DockerConfig {

    @Value("${sandbox.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${sandbox.docker.connect-timeout:30}")
    private int connectTimeout;

    @Value("${sandbox.docker.response-timeout:60}")
    private int responseTimeout;

    @Value("${sandbox.docker.max-connections:100}")
    private int maxConnections;

    /**
     * 创建 Docker 客户端配置
     */
    @Bean
    public DockerClientConfig dockerClientConfig() {
        // 强制指定路径，不给驱动回退到 TCP 2375 的机会
        String finalHost = dockerHost;

        // 兼容性处理：如果环境传的是 unix:///var/run/docker.sock
        // docker-java 库内部有时只需要 unix:///var/run/docker.sock
        log.info("初始化 Docker 客户端配置, 原始 host: {}", dockerHost);

        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(finalHost) // 确保这里传入的是完整的 unix:///var/run/docker.sock
                .withDockerTlsVerify(false) // 禁用 TLS 校验，避免干扰
                .build();
    }

    /**
     * 创建 Docker HTTP 客户端
     * 使用 ZerodepDockerHttpClient，它内置了对 Unix socket 的原生支持
     */
    @Bean
    public DockerHttpClient dockerHttpClient(DockerClientConfig config) {
        log.info("创建 Docker HTTP 客户端 (Zerodep), Docker Host URI: {}", config.getDockerHost());
        
        return new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(connectTimeout))
                .responseTimeout(Duration.ofSeconds(responseTimeout))
                .maxConnections(maxConnections)
                .build();
    }

    /**
     * 创建 Docker 客户端
     */
    @Bean
    public DockerClient dockerClient(DockerClientConfig config, DockerHttpClient httpClient) {
        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        
        // 验证连接
        try {
            var info = client.infoCmd().exec();
            log.info("Docker 连接成功, Server Version: {}, Containers: {}, Images: {}",
                    info.getServerVersion(),
                    info.getContainers(),
                    info.getImages());
        } catch (Exception e) {
            log.error("Docker 连接失败: {}", e.getMessage());
            throw new RuntimeException("无法连接到 Docker Daemon", e);
        }
        
        return client;
    }
}
