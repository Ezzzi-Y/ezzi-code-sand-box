package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.common.enums.LanguageEnum;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 语言策略接口
 * <p>
 * 定义不同编程语言的编译、运行命令及安全检查规则
 * </p>
 *
 * @author ezzziy
 */
public interface LanguageStrategy {

    /**
     * 获取支持的语言枚举
     */
    LanguageEnum getLanguage();

    /**
     * 获取 Docker 镜像名称
     */
    String getDockerImage();

    /**
     * 获取源文件名
     */
    String getSourceFileName();

    /**
     * 获取编译命令
     *
     * @param sourceFile 源文件路径
     * @param outputFile 输出文件路径
     * @return 编译命令数组，如果是解释型语言返回 null
     */
    String[] getCompileCommand(String sourceFile, String outputFile);

    /**
     * 获取运行命令
     *
     * @param executableFile 可执行文件路径
     * @return 运行命令数组
     */
    String[] getRunCommand(String executableFile);

    /**
     * 获取可执行文件名
     */
    String getExecutableFileName();

    /**
     * 是否需要编译
     */
    default boolean needCompile() {
        return getCompileCommand("", "") != null;
    }

    /**
     * 获取危险代码模式列表
     */
    List<Pattern> getDangerousPatterns();

    /**
     * 检查代码是否包含危险模式
     *
     * @param code 用户代码
     * @return 检测到的危险模式描述，null 表示安全
     */
    default String checkDangerousCode(String code) {
        for (Pattern pattern : getDangerousPatterns()) {
            if (pattern.matcher(code).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    /**
     * 获取编译超时时间（秒）
     */
    default int getCompileTimeout() {
        return 30;
    }

    /**
     * 获取额外的环境变量
     */
    default String[] getEnvironmentVariables() {
        return new String[0];
    }
}
