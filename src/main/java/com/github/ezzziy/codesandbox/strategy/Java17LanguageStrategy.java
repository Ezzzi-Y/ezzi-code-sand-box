package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.common.enums.LanguageEnum;
import com.github.ezzziy.codesandbox.util.JavaUnicodeDecoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Java 17 语言策略
 *
 * @author ezzziy
 */
@Component
public class Java17LanguageStrategy implements LanguageStrategy {

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
            // java.nio.file
            Pattern.compile("import\\s+java\\.nio\\.file\\."),
            Pattern.compile("Path\\.of\\s*\\("),
            Pattern.compile("Paths\\.get\\s*\\("),
            Pattern.compile("Files\\.read"),
            Pattern.compile("Files\\.list"),
            Pattern.compile("Files\\.walk"),
            Pattern.compile("Files\\.lines"),
            Pattern.compile("Files\\.newInputStream"),
            Pattern.compile("Files\\.newOutputStream"),
            Pattern.compile("Files\\.newBufferedReader"),
            Pattern.compile("Files\\.newBufferedWriter"),
            // 全限定类名绕过
            Pattern.compile("java\\.io\\.File[^s]"),
            // Channel
            Pattern.compile("FileChannel"),
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
            Pattern.compile("Thread\\.sleep\\s*\\("),  // 禁止所有 Thread.sleep 调用
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
        return LanguageEnum.JAVA17;
    }

    @Override
    public String getDockerImage() {
        return "sandbox-java17:latest";
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
                "-XX:+UseG1GC",
                "-cp", executableFile,
                "Main"
        };
    }

    @Override
    public String getExecutableFileName() {
        return ".";
    }

    @Override
    public List<Pattern> getDangerousPatterns() {
        return DANGEROUS_PATTERNS;
    }

    @Override
    public String checkDangerousCode(String code) {
        // 预处理：解析 Java Unicode 转义，防止绕过黑名单
        String decodedCode = JavaUnicodeDecoder.decode(code);
        for (Pattern pattern : getDangerousPatterns()) {
            if (pattern.matcher(decodedCode).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    @Override
    public int getCompileTimeout() {
        return 60;
    }
}
