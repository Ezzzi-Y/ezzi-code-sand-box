package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.common.enums.LanguageEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * C++11 语言策略
 *
 * @author ezzziy
 */
@Component
public class CppLanguageStrategy implements LanguageStrategy {

    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            // 系统调用
            Pattern.compile("\\bsystem\\s*\\("),
            Pattern.compile("\\bexec[lv]?[pe]?\\s*\\("),
            Pattern.compile("\\bfork\\s*\\("),
            Pattern.compile("\\bvfork\\s*\\("),
            Pattern.compile("\\bclone\\s*\\("),
            // 文件操作（全面拦截）
            Pattern.compile("\\bfopen\\s*\\("),
            Pattern.compile("\\bopen\\s*\\("),
            Pattern.compile("\\bfreopen\\s*\\("),
            Pattern.compile("\\bfdopen\\s*\\("),
            Pattern.compile("\\bcreat\\s*\\("),
            Pattern.compile("\\bunlink\\s*\\("),
            Pattern.compile("\\bremove\\s*\\("),
            Pattern.compile("\\bstd::filesystem::remove"),
            Pattern.compile("\\bstd::remove\\s*\\("),
            // 目录操作
            Pattern.compile("\\bopendir\\s*\\("),
            Pattern.compile("\\breaddir\\s*\\("),
            Pattern.compile("\\bscandir\\s*\\("),
            // 文件信息
            Pattern.compile("\\bstat\\s*\\("),
            Pattern.compile("\\blstat\\s*\\("),
            Pattern.compile("\\baccess\\s*\\("),
            // 危险头文件
            Pattern.compile("#include\\s*<dirent\\.h>"),
            Pattern.compile("#include\\s*<sys/stat\\.h>"),
            Pattern.compile("#include\\s*<fcntl\\.h>"),
            // 连字符绕过 #include（%: 等价于 #，C++11 默认启用）
            Pattern.compile("%:\\s*include\\s*<dirent\\.h>"),
            Pattern.compile("%:\\s*include\\s*<sys/stat\\.h>"),
            Pattern.compile("%:\\s*include\\s*<fcntl\\.h>"),
            // C++ 文件流
            Pattern.compile("\\bstd::ifstream"),
            Pattern.compile("\\bstd::ofstream"),
            Pattern.compile("\\bstd::fstream"),
            // 网络操作
            Pattern.compile("\\bsocket\\s*\\("),
            Pattern.compile("\\bconnect\\s*\\("),
            Pattern.compile("\\bbind\\s*\\("),
            // 内联汇编
            Pattern.compile("\\b__asm__\\b"),
            Pattern.compile("\\basm\\s*\\("),
            Pattern.compile("\\basm\\s*volatile"),
            // 危险头文件
            Pattern.compile("#include\\s*<sys/socket\\.h>"),
            Pattern.compile("#include\\s*<netinet/"),
            Pattern.compile("#include\\s*<arpa/"),
            Pattern.compile("#include\\s*<sys/ptrace\\.h>"),
            Pattern.compile("#include\\s*<filesystem>"),
            // 连字符绕过
            Pattern.compile("%:\\s*include\\s*<sys/socket\\.h>"),
            Pattern.compile("%:\\s*include\\s*<netinet/"),
            Pattern.compile("%:\\s*include\\s*<arpa/"),
            Pattern.compile("%:\\s*include\\s*<sys/ptrace\\.h>"),
            Pattern.compile("%:\\s*include\\s*<filesystem>"),
            // 宏 token pasting —— 可拼接出任意危险函数，OJ 场景无正当用途
            Pattern.compile("##"),
            // 危险函数
            Pattern.compile("\\bpopen\\s*\\("),
            Pattern.compile("\\bpclose\\s*\\("),
            Pattern.compile("\\bdlopen\\s*\\("),
            Pattern.compile("\\bdlsym\\s*\\("),
            Pattern.compile("\\bptrace\\s*\\("),
            Pattern.compile("\\bmmap\\s*\\("),
            // C++ 特定
            Pattern.compile("\\bstd::thread\\b"),
            Pattern.compile("\\bstd::async\\b"),
            Pattern.compile("\\bstd::future\\b")
    );

    @Override
    public LanguageEnum getLanguage() {
        return LanguageEnum.CPP;
    }

    @Override
    public String getDockerImage() {
        return "sandbox-gcc:latest";
    }

    @Override
    public String getSourceFileName() {
        return "main.cpp";
    }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{
                "g++",
                "-std=c++11",
                "-O2",
                "-Wall",
                "-Wextra",
                "-fno-asm",
                "-o", outputFile,
                sourceFile
        };
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{executableFile};
    }

    @Override
    public String getExecutableFileName() {
        return "main";
    }

    @Override
    public List<Pattern> getDangerousPatterns() {
        return DANGEROUS_PATTERNS;
    }
}
