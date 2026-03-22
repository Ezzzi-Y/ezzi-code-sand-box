package com.github.ezzziy.codesandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 代码执行配置
 * <p>
 * 配置编译、运行时间限制，内存限制等执行参数
 * </p>
 *
 * @author ezzziy
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sandbox.execution")
public class ExecutionConfig {

    /**
     * 编译超时时间（秒）
     */
    private int compileTimeout = 30;

    /**
     * 运行超时时间（秒）- 单个测试用例
     */
    private int runTimeout = 10;

    /**
     * 总执行超时时间（秒）- 所有测试用例
     */
    private int totalTimeout = 300;

    /**
     * 内存限制（MB）
     */
    private int memoryLimit = 256;

    /**
     * CPU 限制（核心数）
     */
    private double cpuLimit = 1.0;

    /**
     * 输出大小限制（字节）
     */
    private int outputLimit = 262144;

    /**
     * 最大进程数（容器池模式需要足够大以支持多次 exec）
     */
    private int maxProcesses = 256;

    /**
     * 最大打开文件数
     */
    private int maxOpenFiles = 256;

    /**
     * 最大测试用例数
     */
    private int maxTestCases = 100;

    /**
     * 工作目录基础路径
     */
    private String workDir = "/tmp/sandbox";

    /**
     * 是否启用危险代码扫描
     */
    private boolean enableCodeScan = true;

    /**
     * 并发执行的最大容器数
     */
    private int maxConcurrentContainers = 10;
}
