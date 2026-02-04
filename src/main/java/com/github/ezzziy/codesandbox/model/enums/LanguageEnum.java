package com.github.ezzziy.codesandbox.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 编程语言枚举
 *
 * @author ezzziy
 */
@Getter
@AllArgsConstructor
public enum LanguageEnum {

    C("c", "C", "gcc:11", ".c"),
    CPP("cpp", "C++11", "gcc:11", ".cpp"),
    JAVA8("java8", "Java 8", "eclipse-temurin:8-jdk-alpine", ".java"),
    JAVA17("java17", "Java 17", "eclipse-temurin:17-jdk-alpine", ".java"),
    PYTHON3("python3", "Python 3", "python:3.10", ".py"),
    GOLANG("golang", "Go", "golang:1.20", ".go");

    /**
     * 语言标识符
     */
    private final String code;

    /**
     * 语言显示名称
     */
    private final String displayName;

    /**
     * Docker 镜像名称
     */
    private final String dockerImage;

    /**
     * 源文件扩展名
     */
    private final String extension;

    /**
     * 根据代码获取语言枚举
     */
    public static LanguageEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (LanguageEnum lang : values()) {
            if (lang.getCode().equalsIgnoreCase(code)) {
                return lang;
            }
        }
        return null;
    }

    /**
     * 判断是否为编译型语言
     */
    public boolean isCompiled() {
        return this == C || this == CPP || this == JAVA8 || this == JAVA17 || this == GOLANG;
    }

    /**
     * 判断是否为 Java 语言
     */
    public boolean isJava() {
        return this == JAVA8 || this == JAVA17;
    }
}
