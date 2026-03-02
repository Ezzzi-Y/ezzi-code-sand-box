# 代码执行沙箱服务 - 多语言支持配置

## 1. 概述

本沙箱支持 5 种编程语言：
- **C** (GCC, C11 标准)
- **C++** (G++, C++11 标准)
- **Java 8** (OpenJDK 8)
- **Java 17** (OpenJDK 17)
- **Python 3**

每种语言通过**策略模式**实现，直接实现 `LanguageStrategy` 接口（无抽象基类），通过 Spring 自动注册。

---

## 2. 语言枚举

### 2.1 LanguageEnum

```java
package com.github.ezzziy.codesandbox.common.enums;

@Getter
@AllArgsConstructor
public enum LanguageEnum {

    C("c", "C", "gcc:11", ".c"),
    CPP("cpp11", "C++11", "gcc:11", ".cpp"),
    JAVA8("java8", "Java 8", "eclipse-temurin:8-jdk-alpine", ".java"),
    JAVA17("java17", "Java 17", "eclipse-temurin:17-jdk-alpine", ".java"),
    PYTHON3("python3", "Python 3", "python:3.10", ".py");

    private final String code;           // 语言标识符（API 传参用）
    private final String displayName;    // 显示名称
    private final String dockerImage;    // 枚举中原始镜像名（策略类会覆盖为 sandbox-* 镜像）
    private final String extension;      // 源文件扩展名

    public static LanguageEnum fromCode(String code);
    public boolean isJava();
}
```

> 注意：枚举中的 `dockerImage` 字段是基准镜像名，实际运行时使用各策略类 `getDockerImage()` 返回的自定义沙箱镜像（`sandbox-gcc:latest` 等）。

---

## 3. 语言策略接口

### 3.1 LanguageStrategy 接口

```java
package com.github.ezzziy.codesandbox.strategy;

public interface LanguageStrategy {

    /** 获取支持的语言枚举 */
    LanguageEnum getLanguage();

    /** 获取实际使用的 Docker 镜像名称 */
    String getDockerImage();

    /** 获取源文件名 */
    String getSourceFileName();

    /** 获取编译命令（解释型语言返回 null） */
    String[] getCompileCommand(String sourceFile, String outputFile);

    /** 获取运行命令 */
    String[] getRunCommand(String executableFile);

    /** 获取可执行文件名（Java 返回 "."，Python 返回 "main.py"，C/C++ 返回 "main"） */
    String getExecutableFileName();

    /** 是否需要编译（默认通过 getCompileCommand 是否返回 null 判断） */
    default boolean needCompile() {
        return getCompileCommand("", "") != null;
    }

    /** 获取危险代码正则模式列表 */
    List<Pattern> getDangerousPatterns();

    /** 检查代码是否包含危险模式，返回匹配到的正则（null 表示安全） */
    default String checkDangerousCode(String code) {
        for (Pattern pattern : getDangerousPatterns()) {
            if (pattern.matcher(code).find()) {
                return pattern.pattern();
            }
        }
        return null;
    }

    /** 编译超时时间（秒），默认 30 秒 */
    default int getCompileTimeout() { return 30; }

    /** 额外环境变量，默认空 */
    default String[] getEnvironmentVariables() { return new String[0]; }
}
```

关键设计点：
- **无抽象基类**：每个策略直接实现接口
- **危险代码扫描集成在策略中**：每种语言定义自己的 `getDangerousPatterns()`，无独立的 `DangerousCodeScanner` 组件
- **编译判断**：通过 `getCompileCommand()` 返回值是否为 null 自动判断

---

## 4. 语言策略实现

### 4.1 C 语言策略

```java
@Component
public class CLanguageStrategy implements LanguageStrategy {

    @Override
    public LanguageEnum getLanguage() { return LanguageEnum.C; }

    @Override
    public String getDockerImage() { return "sandbox-gcc:latest"; }

    @Override
    public String getSourceFileName() { return "main.c"; }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{
            "gcc", "-std=c11", "-O2", "-Wall", "-Wextra",
            "-fno-asm", "-lm", "-o", outputFile, sourceFile
        };
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{executableFile};
    }

    @Override
    public String getExecutableFileName() { return "main"; }

    // 危险模式：system(), exec*(), fork(), socket(), 内联汇编, 危险头文件等
}
```

### 4.2 C++ 语言策略

```java
@Component
public class CppLanguageStrategy implements LanguageStrategy {

    @Override
    public LanguageEnum getLanguage() { return LanguageEnum.CPP; }

    @Override
    public String getDockerImage() { return "sandbox-gcc:latest"; }

    @Override
    public String getSourceFileName() { return "main.cpp"; }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{
            "g++", "-std=c++11", "-O2", "-Wall", "-Wextra",
            "-fno-asm", "-o", outputFile, sourceFile
        };
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{executableFile};
    }

    @Override
    public String getExecutableFileName() { return "main"; }

    // 危险模式：与 C 类似，额外包含 std::thread, std::async, std::filesystem 等
}
```

### 4.3 Java 8 策略

```java
@Component
public class Java8LanguageStrategy implements LanguageStrategy {

    @Override
    public LanguageEnum getLanguage() { return LanguageEnum.JAVA8; }

    @Override
    public String getDockerImage() { return "sandbox-java8:latest"; }

    @Override
    public String getSourceFileName() { return "Main.java"; }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{"javac", "-encoding", "UTF-8", "-d", outputFile, sourceFile};
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{
            "java", "-Xmx256m", "-Xms64m",
            "-Djava.security.manager=default",
            "-cp", executableFile, "Main"
        };
    }

    @Override
    public String getExecutableFileName() { return "."; }  // 当前目录作为 classpath

    @Override
    public int getCompileTimeout() { return 60; }  // Java 编译较慢

    // 危险模式：Runtime.exec, ProcessBuilder, 反射, 文件操作, 网络, ClassLoader, JNI, Unsafe 等
}
```

### 4.4 Java 17 策略

```java
@Component
public class Java17LanguageStrategy implements LanguageStrategy {

    @Override
    public LanguageEnum getLanguage() { return LanguageEnum.JAVA17; }

    @Override
    public String getDockerImage() { return "sandbox-java17:latest"; }

    @Override
    public String getSourceFileName() { return "Main.java"; }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{"javac", "-encoding", "UTF-8", "-d", outputFile, sourceFile};
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{
            "java", "-Xmx256m", "-Xms64m",
            "-XX:+UseG1GC",           // Java 17 使用 G1GC
            "-cp", executableFile, "Main"
        };
    }

    @Override
    public String getExecutableFileName() { return "."; }

    @Override
    public int getCompileTimeout() { return 60; }

    // 危险模式：与 Java 8 相同
}
```

> 与 Java 8 的区别：Java 17 使用 `-XX:+UseG1GC`，不设置 `-Djava.security.manager`（Java 17 中 SecurityManager 已弃用）。

### 4.5 Python 3 策略

```java
@Component
public class Python3LanguageStrategy implements LanguageStrategy {

    @Override
    public LanguageEnum getLanguage() { return LanguageEnum.PYTHON3; }

    @Override
    public String getDockerImage() { return "sandbox-python:latest"; }

    @Override
    public String getSourceFileName() { return "main.py"; }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return null;  // 解释型语言，不编译
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{"python3", "-u", executableFile};  // -u 禁用输出缓冲
    }

    @Override
    public String getExecutableFileName() { return "main.py"; }

    @Override
    public boolean needCompile() { return false; }

    @Override
    public String[] getEnvironmentVariables() {
        return new String[]{
            "PYTHONDONTWRITEBYTECODE=1",
            "PYTHONUNBUFFERED=1"
        };
    }

    // 危险模式：os.system, subprocess, eval, exec, socket, ctypes, multiprocessing, pickle 等
}
```

---

## 5. 各语言危险代码模式概览

| 语言 | 主要拦截类别 | 典型模式 |
|------|------------|---------|
| C | 系统调用, 文件, 网络, 内联汇编, 危险头文件 | `system()`, `fork()`, `socket()`, `asm`, `#include <sys/socket.h>` |
| C++ | 同 C + C++ 特有 | `std::thread`, `std::async`, `std::filesystem` |
| Java 8 | Runtime, 反射, 文件, 网络, ClassLoader, JNI, Unsafe | `Runtime.getRuntime()`, `Class.forName()`, `System.exit` |
| Java 17 | 同 Java 8 | 相同危险模式列表 |
| Python | 系统命令, eval/exec, 文件, 网络, ctypes, pickle | `os.system()`, `subprocess`, `eval()`, `socket` |

> 扫描在 `DockerCodeExecutor` 中调用 `strategy.checkDangerousCode(code)`，仅在 `executionConfig.isEnableCodeScan()` 为 true 时执行。

---

## 6. 语言策略工厂

### 6.1 LanguageStrategyFactory

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class LanguageStrategyFactory {

    private final List<LanguageStrategy> strategies;
    private final Map<LanguageEnum, LanguageStrategy> strategyMap = new EnumMap<>(LanguageEnum.class);

    @PostConstruct
    public void init() {
        for (LanguageStrategy strategy : strategies) {
            strategyMap.put(strategy.getLanguage(), strategy);
            log.info("注册语言策略: {} -> {}",
                    strategy.getLanguage().getCode(),
                    strategy.getClass().getSimpleName());
        }
        log.info("共注册 {} 种语言策略", strategyMap.size());
    }

    /** 根据语言枚举获取策略 */
    public LanguageStrategy getStrategy(LanguageEnum language);

    /** 根据语言代码字符串获取策略（内部通过 LanguageEnum.fromCode 转换） */
    public LanguageStrategy getStrategy(String languageCode);

    /** 判断是否支持指定语言 */
    public boolean isSupported(String languageCode);

    /** 获取所有支持的语言枚举列表 */
    public List<LanguageEnum> getSupportedLanguages();
}
```

关键设计：
- 使用 `EnumMap<LanguageEnum, LanguageStrategy>`，类型安全且高效
- 语言查找通过 `LanguageEnum.fromCode()` 实现，不支持的语言抛出 `IllegalArgumentException`
- 所有策略通过 Spring `List<LanguageStrategy>` 自动注入，`@PostConstruct` 时注册到 Map

---

## 7. Docker 沙箱镜像

项目使用自定义构建的沙箱镜像（非公共镜像），位于 `sandbox-images/` 目录：

| 镜像名 | 适用语言 | 构建文件 |
|-------|---------|---------|
| `sandbox-gcc:latest` | C, C++ | `sandbox-images/gcc/Dockerfile` |
| `sandbox-java8:latest` | Java 8 | `sandbox-images/java/Dockerfile.java8` |
| `sandbox-java17:latest` | Java 17 | `sandbox-images/java/Dockerfile.java17` |
| `sandbox-python:latest` | Python 3 | `sandbox-images/python/Dockerfile` |

所有沙箱镜像基于 `sandbox-base:latest` 基础镜像构建，内含 `sandbox` 用户 (uid=1000)、`/sandbox/workspace` 工作目录以及 `/usr/bin/time` 工具。

构建命令：
```bash
cd sandbox-images && bash build.sh
```

---

## 8. 各语言配置对比

| 属性 | C | C++ | Java 8 | Java 17 | Python 3 |
|------|---|-----|--------|---------|----------|
| 语言代码 | `c` | `cpp11` | `java8` | `java17` | `python3` |
| Docker 镜像 | sandbox-gcc | sandbox-gcc | sandbox-java8 | sandbox-java17 | sandbox-python |
| 源文件名 | main.c | main.cpp | Main.java | Main.java | main.py |
| 可执行文件 | main | main | . (classpath) | . (classpath) | main.py |
| 需要编译 | 是 | 是 | 是 | 是 | 否 |
| 编译超时 | 30s | 30s | 60s | 60s | N/A |
| 编译器 | gcc | g++ | javac | javac | N/A |
| 编译标准 | C11 | C++11 | UTF-8 | UTF-8 | N/A |
| 优化选项 | -O2 -Wall -Wextra -fno-asm | -O2 -Wall -Wextra -fno-asm | N/A | N/A | N/A |
| 运行参数 | 直接执行 | 直接执行 | -Xmx256m -Djava.security.manager=default | -Xmx256m -XX:+UseG1GC | -u (无缓冲) |

---

## 9. 扩展新语言

添加新语言只需三步：

### 步骤 1：新增 LanguageEnum 枚举值

```java
RUST("rust", "Rust", "rust:1.70", ".rs"),
```

### 步骤 2：创建策略类

```java
@Component
public class RustLanguageStrategy implements LanguageStrategy {

    @Override
    public LanguageEnum getLanguage() { return LanguageEnum.RUST; }

    @Override
    public String getDockerImage() { return "sandbox-rust:latest"; }

    @Override
    public String getSourceFileName() { return "main.rs"; }

    @Override
    public String[] getCompileCommand(String sourceFile, String outputFile) {
        return new String[]{"rustc", "-O", "-o", outputFile, sourceFile};
    }

    @Override
    public String[] getRunCommand(String executableFile) {
        return new String[]{executableFile};
    }

    @Override
    public String getExecutableFileName() { return "main"; }

    @Override
    public List<Pattern> getDangerousPatterns() {
        return List.of(
            Pattern.compile("\\bstd::process::Command\\b"),
            Pattern.compile("\\bstd::net::")
        );
    }
}
```

### 步骤 3：构建沙箱镜像

在 `sandbox-images/` 下新增 Dockerfile 并运行构建脚本。

> 无需修改工厂类或任何配置文件——Spring 自动扫描注册新策略。

---

## 10. 下一步

下一篇文档将详细介绍 **安全机制实现**，包括：
- 容器安全配置（capDrop、no-new-privileges、tmpFs）
- 危险代码检测策略
- 资源限制实现

详见 [05-SECURITY.md](05-SECURITY.md)
