package com.github.ezzziy.codesandbox.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java Unicode 转义预处理器
 * <p>
 * 按 JLS 3.3 规则，将源码中的 Unicode 转义序列（反斜杠 + u + 4位十六进制）
 * 解析为实际字符，用于在正则黑名单检测前消除 Unicode 转义绕过。
 * </p>
 *
 * @author ezzziy
 */
public class JavaUnicodeDecoder {

    /**
     * 匹配反斜杠 + 一个或多个 u + 4 位十六进制，与 JLS 3.3 一致
     */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile(
            "\\\\" + "u+([0-9a-fA-F]{4})");

    /**
     * 将 Java 源码中的 Unicode 转义序列解析为实际字符
     *
     * @param source 原始 Java 源码
     * @return 解析后的源码
     */
    public static String decode(String source) {
        if (source == null || !source.contains("\\" + "u")) {
            return source;
        }
        Matcher matcher = UNICODE_ESCAPE.matcher(source);
        StringBuilder sb = new StringBuilder(source.length());
        while (matcher.find()) {
            char ch = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
