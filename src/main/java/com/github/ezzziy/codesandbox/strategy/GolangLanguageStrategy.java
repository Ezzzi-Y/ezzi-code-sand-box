package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.model.enums.LanguageEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Golang 语言策略
 *
 * @author ezzziy
 */
@Component
public class GolangLanguageStrategy implements LanguageStrategy {

    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            // 系统命令执行
            Pattern.compile("exec\\.Command\\s*\\("),
            Pattern.compile("os\\.StartProcess\\s*\\("),
            Pattern.compile("syscall\\.Exec\\s*\\("),
            Pattern.compile("syscall\\.ForkExec\\s*\\("),
            // 文件操作（危险路径）
            Pattern.compile("os\\.Remove\\s*\\("),
            Pattern.compile("os\\.RemoveAll\\s*\\("),
            Pattern.compile("os\\.Rename\\s*\\("),
            Pattern.compile("os\\.Create\\s*\\([^)]*[\"']/"),
            Pattern.compile("os\\.OpenFile\\s*\\([^)]*[\"']/"),
            Pattern.compile("ioutil\\.WriteFile\\s*\\([^)]*[\"']/"),
            // 网络操作
            Pattern.compile("net\\.Dial\\s*\\("),
            Pattern.compile("net\\.Listen\\s*\\("),
            Pattern.compile("http\\.Get\\s*\\("),
            Pattern.compile("http\\.Post\\s*\\("),
            Pattern.compile("http\\.ListenAndServe"),
            // 危险导入
            Pattern.compile("import\\s+\"os/exec\""),
            Pattern.compile("import\\s+\"net\""),
            Pattern.compile("import\\s+\"net/http\""),
            Pattern.compile("import\\s+\"syscall\""),
            Pattern.compile("import\\s+\"unsafe\""),
            Pattern.compile("import\\s+\"plugin\""),
            Pattern.compile("import\\s+\"reflect\""),
            // cgo
            Pattern.compile("import\\s+\"C\""),
            Pattern.compile("#cgo\\s+"),
            // 危险函数
            Pattern.compile("runtime\\.GOMAXPROCS\\s*\\("),
            Pattern.compile("runtime\\.LockOSThread\\s*\\("),
            Pattern.compile("os\\.Exit\\s*\\("),
            Pattern.compile("panic\\s*\\("),  // 虽然 panic 有时是合理的，但过多使用可能是恶意的
            // 协程炸弹
            Pattern.compile("for\\s*\\{[^}]*go\\s+func")  // 无限循环创建 goroutine
    );

    @Override
    public LanguageEnum getLanguage() {
        return LanguageEnum.GOLANG;
    }

    @Override
    public String getDockerImage() {
        return "sandbox-golang:latest";
    }

    @Override
    public String getSourceFileName() {
        return "main.go";
    }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{
                "go",
                "build",
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

    @Override
    public int getCompileTimeout() {
        return 60;  // Go 编译可能较慢
    }

    @Override
    public String[] getEnvironmentVariables() {
        return new String[]{
                "GOCACHE=/tmp/go-cache",
                "GOPATH=/tmp/go",
                "CGO_ENABLED=0"  // 禁用 cgo
        };
    }
}
