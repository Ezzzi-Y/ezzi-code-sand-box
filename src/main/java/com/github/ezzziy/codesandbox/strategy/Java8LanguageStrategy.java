package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.common.enums.LanguageEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Java 8 语言策略
 *
 * @author ezzziy
 */
@Component
public class Java8LanguageStrategy implements LanguageStrategy {

    private static final List<Pattern> DANGEROUS_PATTERNS = Arrays.asList(
            // Runtime 执行
            Pattern.compile("Runtime\\.getRuntime\\(\\)"),
            Pattern.compile("ProcessBuilder"),
            Pattern.compile("\\.exec\\s*\\("),
            // 反射
            Pattern.compile("Class\\.forName\\s*\\("),
            Pattern.compile("\\.getMethod\\s*\\("),
            Pattern.compile("\\.getDeclaredMethod\\s*\\("),
            Pattern.compile("\\.invoke\\s*\\("),
            Pattern.compile("setAccessible\\s*\\(\\s*true"),
            // 文件操作
            Pattern.compile("new\\s+File\\s*\\("),
            Pattern.compile("new\\s+java\\.io\\.File\\s*\\("),
            Pattern.compile("FileInputStream"),
            Pattern.compile("FileOutputStream"),
            Pattern.compile("RandomAccessFile"),
            Pattern.compile("FileWriter"),
            Pattern.compile("FileReader"),
            Pattern.compile("Files\\.delete"),
            Pattern.compile("Files\\.write"),
            Pattern.compile("Files\\.move"),
            Pattern.compile("Files\\.copy"),
            Pattern.compile("File\\.delete"),
            Pattern.compile("\\.deleteOnExit\\("),
            // 网络操作
            Pattern.compile("Socket\\s*\\("),
            Pattern.compile("ServerSocket"),
            Pattern.compile("DatagramSocket"),
            Pattern.compile("URL\\s*\\("),
            Pattern.compile("URLConnection"),
            Pattern.compile("HttpURLConnection"),
            Pattern.compile("HttpClient"),
            // 危险类
            Pattern.compile("import\\s+java\\.net\\."),
            Pattern.compile("import\\s+java\\.nio\\.channels\\."),
            Pattern.compile("import\\s+javax\\.net\\."),
            Pattern.compile("import\\s+sun\\."),
            Pattern.compile("import\\s+com\\.sun\\."),
            // ClassLoader
            Pattern.compile("ClassLoader"),
            Pattern.compile("URLClassLoader"),
            Pattern.compile("defineClass"),
            // 线程操作（限制）
            Pattern.compile("Thread\\.sleep\\s*\\(\\s*[0-9]{5,}"),  // 睡眠超过10秒
            Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exit"),
            Pattern.compile("System\\.exit"),
            // JNI
            Pattern.compile("System\\.loadLibrary"),
            Pattern.compile("System\\.load\\("),
            Pattern.compile("native\\s+\\w+"),
            // Unsafe
            Pattern.compile("sun\\.misc\\.Unsafe"),
            Pattern.compile("Unsafe\\.getUnsafe")
    );

    @Override
    public LanguageEnum getLanguage() {
        return LanguageEnum.JAVA8;
    }

    @Override
    public String getDockerImage() {
        return "sandbox-java8:latest";
    }

    @Override
    public String getSourceFileName() {
        return "Main.java";
    }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{
                "javac",
                "-encoding", "UTF-8",
                "-d", outputFile,
                sourceFile
        };
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{
                "java",
                "-Xmx256m",
                "-Xms64m",
                "-Djava.security.manager=default",
                "-cp", executableFile,
                "Main"
        };
    }

    @Override
    public String getExecutableFileName() {
        return ".";  // Java 使用当前目录作为 classpath
    }

    @Override
    public List<Pattern> getDangerousPatterns() {
        return DANGEROUS_PATTERNS;
    }

    @Override
    public int getCompileTimeout() {
        return 60;  // Java 编译可能较慢
    }
}
