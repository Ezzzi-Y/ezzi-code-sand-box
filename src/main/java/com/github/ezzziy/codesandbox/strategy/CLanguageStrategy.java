package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.model.enums.LanguageEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * C 语言策略
 *
 * @author ezzziy
 */
@Component
public class CLanguageStrategy implements LanguageStrategy {

    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            // 系统调用
            Pattern.compile("\\bsystem\\s*\\("),
            Pattern.compile("\\bexec[lv]?[pe]?\\s*\\("),
            Pattern.compile("\\bfork\\s*\\("),
            Pattern.compile("\\bvfork\\s*\\("),
            Pattern.compile("\\bclone\\s*\\("),
            // 文件操作
            Pattern.compile("\\bfopen\\s*\\([^)]*[\"'][/~]"),
            Pattern.compile("\\bopen\\s*\\([^)]*[\"'][/~]"),
            Pattern.compile("\\bunlink\\s*\\("),
            Pattern.compile("\\bremove\\s*\\("),
            Pattern.compile("\\brename\\s*\\("),
            Pattern.compile("\\brmdir\\s*\\("),
            Pattern.compile("\\bmkdir\\s*\\("),
            // 网络操作
            Pattern.compile("\\bsocket\\s*\\("),
            Pattern.compile("\\bconnect\\s*\\("),
            Pattern.compile("\\bbind\\s*\\("),
            Pattern.compile("\\blisten\\s*\\("),
            Pattern.compile("\\baccept\\s*\\("),
            // 内联汇编
            Pattern.compile("\\b__asm__\\b"),
            Pattern.compile("\\basm\\s*\\("),
            Pattern.compile("\\basm\\s*volatile"),
            // 危险头文件
            Pattern.compile("#include\\s*<sys/socket\\.h>"),
            Pattern.compile("#include\\s*<netinet/"),
            Pattern.compile("#include\\s*<arpa/"),
            Pattern.compile("#include\\s*<sys/ptrace\\.h>"),
            // 危险函数
            Pattern.compile("\\bpopen\\s*\\("),
            Pattern.compile("\\bpclose\\s*\\("),
            Pattern.compile("\\bdlopen\\s*\\("),
            Pattern.compile("\\bdlsym\\s*\\("),
            Pattern.compile("\\bptrace\\s*\\("),
            Pattern.compile("\\bmmap\\s*\\("),
            Pattern.compile("\\bsbrk\\s*\\("),
            Pattern.compile("\\bbrk\\s*\\(")
    );

    @Override
    public LanguageEnum getLanguage() {
        return LanguageEnum.C;
    }

    @Override
    public String getDockerImage() {
        return "sandbox-gcc:latest";
    }

    @Override
    public String getSourceFileName() {
        return "main.c";
    }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{
                "gcc",
                "-std=c11",
                "-O2",
                "-Wall",
                "-Wextra",
                "-fno-asm",
                "-lm",
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
