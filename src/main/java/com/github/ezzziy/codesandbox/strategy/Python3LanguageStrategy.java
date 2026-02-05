package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.common.enums.LanguageEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Python 3 语言策略
 *
 * @author ezzziy
 */
@Component
public class Python3LanguageStrategy implements LanguageStrategy {

    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            // 系统命令执行
            Pattern.compile("\\bos\\.system\\s*\\("),
            Pattern.compile("\\bos\\.popen\\s*\\("),
            Pattern.compile("\\bos\\.spawn[lv]?[pe]?\\s*\\("),
            Pattern.compile("\\bos\\.exec[lv]?[pe]?\\s*\\("),
            Pattern.compile("\\bsubprocess\\."),
            Pattern.compile("\\bcommands\\."),
            // eval/exec
            Pattern.compile("\\beval\\s*\\("),
            Pattern.compile("\\bexec\\s*\\("),
            Pattern.compile("\\bcompile\\s*\\("),
            // 文件操作（危险路径）
            Pattern.compile("open\\s*\\([^)]*['\"][/~]"),
            Pattern.compile("\\bos\\.remove\\s*\\("),
            Pattern.compile("\\bos\\.unlink\\s*\\("),
            Pattern.compile("\\bos\\.rmdir\\s*\\("),
            Pattern.compile("\\bshutil\\.rmtree\\s*\\("),
            Pattern.compile("\\bshutil\\.move\\s*\\("),
            Pattern.compile("\\bpathlib\\.Path.*\\.unlink"),
            // 网络操作
            Pattern.compile("\\bsocket\\.socket\\s*\\("),
            Pattern.compile("\\burllib\\.request\\."),
            Pattern.compile("\\brequests\\."),
            Pattern.compile("\\bhttplib\\."),
            Pattern.compile("\\bftplib\\."),
            Pattern.compile("\\bsmtplib\\."),
            // 危险导入
            Pattern.compile("import\\s+ctypes"),
            Pattern.compile("from\\s+ctypes"),
            Pattern.compile("import\\s+_thread"),
            Pattern.compile("import\\s+multiprocessing"),
            Pattern.compile("from\\s+multiprocessing"),
            Pattern.compile("import\\s+signal"),
            // 危险函数
            Pattern.compile("\\b__import__\\s*\\("),
            Pattern.compile("\\bglobals\\s*\\(\\s*\\)"),
            Pattern.compile("\\blocals\\s*\\(\\s*\\)"),
            Pattern.compile("\\bvars\\s*\\("),
            Pattern.compile("\\bdir\\s*\\(\\s*\\)"),
            Pattern.compile("\\bgetattr\\s*\\(.*,\\s*['\"]__"),
            // pickle（可执行任意代码）
            Pattern.compile("\\bpickle\\.loads?\\s*\\("),
            Pattern.compile("\\bcPickle\\."),
            // 危险模块
            Pattern.compile("import\\s+pty"),
            Pattern.compile("import\\s+tty"),
            Pattern.compile("import\\s+fcntl"),
            Pattern.compile("import\\s+resource")
    );

    @Override
    public LanguageEnum getLanguage() {
        return LanguageEnum.PYTHON3;
    }

    @Override
    public String getDockerImage() {
        return "sandbox-python:latest";
    }

    @Override
    public String getSourceFileName() {
        return "main.py";
    }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        // Python 是解释型语言，但可以进行语法检查
        return null;
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{
                "python3",
                "-u",  // 无缓冲输出
                executableFile
        };
    }

    @Override
    public String getExecutableFileName() {
        return "main.py";
    }

    @Override
    public boolean needCompile() {
        return false;
    }

    @Override
    public List<Pattern> getDangerousPatterns() {
        return DANGEROUS_PATTERNS;
    }

    @Override
    public String[] getEnvironmentVariables() {
        return new String[]{
                "PYTHONDONTWRITEBYTECODE=1",
                "PYTHONUNBUFFERED=1"
        };
    }
}
