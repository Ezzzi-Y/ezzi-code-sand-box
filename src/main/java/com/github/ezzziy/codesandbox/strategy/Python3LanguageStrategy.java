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
            // 文件操作（全面拦截）
            Pattern.compile("\\bopen\\s*\\("),
            Pattern.compile("\\bos\\.remove\\s*\\("),
            Pattern.compile("\\bos\\.unlink\\s*\\("),
            Pattern.compile("\\bos\\.rmdir\\s*\\("),
            Pattern.compile("\\bshutil\\.rmtree\\s*\\("),
            Pattern.compile("\\bshutil\\.move\\s*\\("),
            // pathlib（整个模块拦截）
            Pattern.compile("\\bpathlib\\."),
            Pattern.compile("from\\s+pathlib"),
            Pattern.compile("import\\s+pathlib"),
            // os 文件操作
            Pattern.compile("\\bos\\.listdir\\s*\\("),
            Pattern.compile("\\bos\\.scandir\\s*\\("),
            Pattern.compile("\\bos\\.walk\\s*\\("),
            Pattern.compile("\\bos\\.path\\."),
            Pattern.compile("\\bos\\.getcwd"),
            Pattern.compile("\\bos\\.chdir"),
            Pattern.compile("\\bos\\.mkdir"),
            Pattern.compile("\\bos\\.makedirs"),
            Pattern.compile("\\bos\\.rename\\s*\\("),
            // io 模块
            Pattern.compile("\\bio\\.open\\s*\\("),
            Pattern.compile("import\\s+io\\b"),
            Pattern.compile("from\\s+io\\s+import"),
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
            // getattr/setattr/delattr 全面拦截
            Pattern.compile("\\bgetattr\\s*\\("),
            Pattern.compile("\\bsetattr\\s*\\("),
            Pattern.compile("\\bdelattr\\s*\\("),
            // type() 元类操作
            Pattern.compile("\\btype\\s*\\("),
            // dunder 反射链（封堵 MRO / subclass / globals 等绕过）
            Pattern.compile("__builtins__"),
            Pattern.compile("__subclasses__"),
            Pattern.compile("__globals__"),
            Pattern.compile("__bases__"),
            Pattern.compile("__mro__"),
            Pattern.compile("__class__"),
            Pattern.compile("__dict__"),
            Pattern.compile("__loader__"),
            Pattern.compile("__spec__"),
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
