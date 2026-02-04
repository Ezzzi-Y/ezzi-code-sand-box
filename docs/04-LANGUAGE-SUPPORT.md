# OJ 代码执行沙箱服务 - 多语言支持配置

## 1. 概述

本沙箱支持 6 种编程语言：
- **C** (GCC 11)
- **C++** (G++ 11, C++11 标准)
- **Java** (OpenJDK 8 / 11)
- **Python** (Python 3.10)
- **Go** (Go 1.20)

每种语言通过**策略模式**实现，便于扩展新语言。

---

## 2. 语言策略接口

### 2.1 LanguageStrategy 接口

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;

/**
 * 语言执行策略接口
 */
public interface LanguageStrategy {

    /**
     * 获取语言标识
     */
    String getLanguageId();

    /**
     * 获取语言显示名称
     */
    String getLanguageName();

    /**
     * 获取语言版本
     */
    String getVersion();

    /**
     * 获取 Docker 镜像
     */
    String getDockerImage();

    /**
     * 获取源代码文件名
     */
    String getSourceFileName();

    /**
     * 获取文件扩展名
     */
    String getFileExtension();

    /**
     * 是否需要编译
     */
    boolean needsCompilation();

    /**
     * 获取编译命令
     * @param context 执行上下文
     * @return 编译命令数组
     */
    String[] getCompileCommand(ExecutionContext context);

    /**
     * 获取运行命令
     * @param context 执行上下文
     * @return 运行命令数组
     */
    String[] getRunCommand(ExecutionContext context);

    /**
     * 获取额外的环境变量
     */
    default java.util.Map<String, String> getEnvironmentVariables() {
        return java.util.Collections.emptyMap();
    }

    /**
     * 获取额外的 JVM 参数（仅 Java）
     */
    default String[] getJvmArgs(ExecutionContext context) {
        return new String[0];
    }

    /**
     * 获取推荐的进程数限制
     */
    default int getRecommendedPidsLimit() {
        return 10;
    }

    /**
     * 获取编译超时时间（毫秒）
     */
    default long getCompileTimeout() {
        return 30000;
    }
}
```

### 2.2 抽象基类

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import lombok.Getter;

/**
 * 语言策略抽象基类
 */
@Getter
public abstract class AbstractLanguageStrategy implements LanguageStrategy {

    protected final String languageId;
    protected final String languageName;
    protected final String version;
    protected final String dockerImage;
    protected final String sourceFileName;
    protected final String fileExtension;
    protected final boolean needsCompilation;

    protected AbstractLanguageStrategy(String languageId, String languageName, String version,
                                       String dockerImage, String sourceFileName, 
                                       String fileExtension, boolean needsCompilation) {
        this.languageId = languageId;
        this.languageName = languageName;
        this.version = version;
        this.dockerImage = dockerImage;
        this.sourceFileName = sourceFileName;
        this.fileExtension = fileExtension;
        this.needsCompilation = needsCompilation;
    }

    @Override
    public boolean needsCompilation() {
        return needsCompilation;
    }
}
```

---

## 3. 语言策略实现

### 3.1 C 语言策略

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * C 语言执行策略
 */
@Component
public class CLanguageStrategy extends AbstractLanguageStrategy {

    public CLanguageStrategy() {
        super(
            "c",                    // languageId
            "C",                    // languageName
            "GCC 11",               // version
            "gcc:11-bullseye",      // dockerImage
            "main.c",               // sourceFileName
            ".c",                   // fileExtension
            true                    // needsCompilation
        );
    }

    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        return new String[]{
            "/bin/sh", "-c",
            "gcc -O2 -std=c11 -Wall -o main main.c -lm 2>&1"
        };
    }

    @Override
    public String[] getRunCommand(ExecutionContext context) {
        return new String[]{"./main"};
    }

    @Override
    public int getRecommendedPidsLimit() {
        return 5;  // C 程序通常单进程
    }
}
```

### 3.2 C++ 语言策略

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * C++ 语言执行策略
 */
@Component
public class CppLanguageStrategy extends AbstractLanguageStrategy {

    public CppLanguageStrategy() {
        super(
            "cpp",                  // languageId
            "C++",                  // languageName
            "G++ 11 (C++11)",       // version
            "gcc:11-bullseye",      // dockerImage
            "main.cpp",             // sourceFileName
            ".cpp",                 // fileExtension
            true                    // needsCompilation
        );
    }

    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        // 可根据 context 中的 languageVersion 选择 C++ 标准
        String cppStandard = getCppStandard(context.getLanguageVersion());
        
        return new String[]{
            "/bin/sh", "-c",
            String.format("g++ -O2 -std=%s -Wall -o main main.cpp 2>&1", cppStandard)
        };
    }

    @Override
    public String[] getRunCommand(ExecutionContext context) {
        return new String[]{"./main"};
    }

    @Override
    public int getRecommendedPidsLimit() {
        return 5;
    }

    /**
     * 获取 C++ 标准版本
     */
    private String getCppStandard(String languageVersion) {
        if (languageVersion == null) {
            return "c++11";
        }
        return switch (languageVersion.toLowerCase()) {
            case "14", "c++14" -> "c++14";
            case "17", "c++17" -> "c++17";
            case "20", "c++20" -> "c++20";
            default -> "c++11";
        };
    }
}
```

### 3.3 Java 8 策略

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Java 8 执行策略
 */
@Component
public class Java8LanguageStrategy extends AbstractLanguageStrategy {

    public Java8LanguageStrategy() {
        super(
            "java8",                    // languageId
            "Java",                     // languageName
            "OpenJDK 8",                // version
            "openjdk:8-jdk-slim",       // dockerImage
            "Main.java",                // sourceFileName
            ".java",                    // fileExtension
            true                        // needsCompilation
        );
    }

    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        return new String[]{
            "/bin/sh", "-c",
            "javac -encoding UTF-8 -J-Xmx256m Main.java 2>&1"
        };
    }

    @Override
    public String[] getRunCommand(ExecutionContext context) {
        int memoryLimit = context.getMemoryLimit();
        return new String[]{
            "java",
            "-Xmx" + memoryLimit + "m",
            "-Xms16m",
            "-XX:+UseSerialGC",
            "-Djava.security.manager",
            "-Djava.security.policy=/dev/null",
            "Main"
        };
    }

    @Override
    public String[] getJvmArgs(ExecutionContext context) {
        int memoryLimit = context.getMemoryLimit();
        return new String[]{
            "-Xmx" + memoryLimit + "m",
            "-Xms16m",
            "-XX:+UseSerialGC",
            "-Djava.awt.headless=true"
        };
    }

    @Override
    public Map<String, String> getEnvironmentVariables() {
        return Map.of(
            "JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8"
        );
    }

    @Override
    public int getRecommendedPidsLimit() {
        return 50;  // Java 需要更多线程（GC、JIT 等）
    }

    @Override
    public long getCompileTimeout() {
        return 60000;  // Java 编译较慢，60 秒
    }
}
```

### 3.4 Java 11 策略

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Java 11 执行策略
 */
@Component
public class Java11LanguageStrategy extends AbstractLanguageStrategy {

    public Java11LanguageStrategy() {
        super(
            "java11",                   // languageId
            "Java",                     // languageName
            "OpenJDK 11",               // version
            "openjdk:11-jdk-slim",      // dockerImage
            "Main.java",                // sourceFileName
            ".java",                    // fileExtension
            true                        // needsCompilation
        );
    }

    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        return new String[]{
            "/bin/sh", "-c",
            "javac -encoding UTF-8 -J-Xmx256m Main.java 2>&1"
        };
    }

    @Override
    public String[] getRunCommand(ExecutionContext context) {
        int memoryLimit = context.getMemoryLimit();
        return new String[]{
            "java",
            "-Xmx" + memoryLimit + "m",
            "-Xms16m",
            "-XX:+UseSerialGC",
            "-XX:+DisableAttachMechanism",
            "--illegal-access=deny",
            "Main"
        };
    }

    @Override
    public Map<String, String> getEnvironmentVariables() {
        return Map.of(
            "JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8"
        );
    }

    @Override
    public int getRecommendedPidsLimit() {
        return 50;
    }

    @Override
    public long getCompileTimeout() {
        return 60000;
    }
}
```

### 3.5 Python 3 策略

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Python 3 执行策略
 */
@Component
public class Python3LanguageStrategy extends AbstractLanguageStrategy {

    public Python3LanguageStrategy() {
        super(
            "python3",              // languageId
            "Python",               // languageName
            "Python 3.10",          // version
            "python:3.10-slim",     // dockerImage
            "main.py",              // sourceFileName
            ".py",                  // fileExtension
            false                   // needsCompilation（解释型语言）
        );
    }

    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        // Python 是解释型语言，但可以进行语法检查
        return new String[]{
            "/bin/sh", "-c",
            "python3 -m py_compile main.py 2>&1"
        };
    }

    @Override
    public String[] getRunCommand(ExecutionContext context) {
        return new String[]{
            "python3", "-u", "main.py"  // -u 禁用输出缓冲
        };
    }

    @Override
    public Map<String, String> getEnvironmentVariables() {
        return Map.of(
            "PYTHONIOENCODING", "utf-8",
            "PYTHONDONTWRITEBYTECODE", "1",
            "PYTHONUNBUFFERED", "1"
        );
    }

    @Override
    public int getRecommendedPidsLimit() {
        return 10;
    }
}
```

### 3.6 Go 语言策略

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.model.dto.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Go 语言执行策略
 */
@Component
public class GoLanguageStrategy extends AbstractLanguageStrategy {

    public GoLanguageStrategy() {
        super(
            "golang",               // languageId
            "Go",                   // languageName
            "Go 1.20",              // version
            "golang:1.20-alpine",   // dockerImage
            "main.go",              // sourceFileName
            ".go",                  // fileExtension
            true                    // needsCompilation
        );
    }

    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        return new String[]{
            "/bin/sh", "-c",
            "go build -o main main.go 2>&1"
        };
    }

    @Override
    public String[] getRunCommand(ExecutionContext context) {
        return new String[]{"./main"};
    }

    @Override
    public Map<String, String> getEnvironmentVariables() {
        return Map.of(
            "GOOS", "linux",
            "GOARCH", "amd64",
            "CGO_ENABLED", "0"  // 禁用 CGO，纯静态编译
        );
    }

    @Override
    public int getRecommendedPidsLimit() {
        return 20;  // Go 有 goroutine，可能需要更多
    }

    @Override
    public long getCompileTimeout() {
        return 60000;  // Go 编译可能较慢
    }
}
```

---

## 4. 语言策略工厂

### 4.1 LanguageStrategyFactory

```java
package com.github.ezzziy.codesandbox.executor.strategy;

import com.github.ezzziy.codesandbox.exception.UnsupportedLanguageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语言策略工厂
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LanguageStrategyFactory {

    private final List<LanguageStrategy> strategies;
    private final Map<String, LanguageStrategy> strategyMap = new HashMap<>();

    @PostConstruct
    public void init() {
        for (LanguageStrategy strategy : strategies) {
            strategyMap.put(strategy.getLanguageId().toLowerCase(), strategy);
            log.info("注册语言策略: {} ({})", strategy.getLanguageId(), strategy.getVersion());
        }
        log.info("共加载 {} 个语言策略", strategyMap.size());
    }

    /**
     * 获取语言策略
     * @param language 语言标识
     * @param version 语言版本（可选）
     * @return 语言策略
     */
    public LanguageStrategy getStrategy(String language, String version) {
        String key = resolveLanguageKey(language, version);
        
        LanguageStrategy strategy = strategyMap.get(key.toLowerCase());
        if (strategy == null) {
            throw new UnsupportedLanguageException(
                    "不支持的编程语言: " + language + (version != null ? " " + version : ""),
                    getSupportedLanguages()
            );
        }
        
        return strategy;
    }

    /**
     * 获取所有支持的语言
     */
    public List<LanguageStrategy> getAllStrategies() {
        return List.copyOf(strategies);
    }

    /**
     * 获取支持的语言 ID 列表
     */
    public List<String> getSupportedLanguages() {
        return strategies.stream()
                .map(LanguageStrategy::getLanguageId)
                .toList();
    }

    /**
     * 检查语言是否支持
     */
    public boolean isSupported(String language) {
        return strategyMap.containsKey(language.toLowerCase());
    }

    /**
     * 解析语言 Key
     */
    private String resolveLanguageKey(String language, String version) {
        language = language.toLowerCase().trim();
        
        // Java 需要根据版本选择
        if ("java".equals(language)) {
            if (version == null || version.isEmpty()) {
                return "java11";  // 默认 Java 11
            }
            return switch (version) {
                case "8", "1.8" -> "java8";
                case "11", "17", "21" -> "java11";  // 11+ 共用策略
                default -> "java11";
            };
        }
        
        // C++ 别名处理
        if ("c++".equals(language) || "cplusplus".equals(language)) {
            return "cpp";
        }
        
        // Python 别名处理
        if ("python".equals(language) || "py".equals(language)) {
            return "python3";
        }
        
        // Go 别名处理
        if ("go".equals(language)) {
            return "golang";
        }
        
        return language;
    }
}
```

---

## 5. 语言服务

### 5.1 LanguageService

```java
package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.executor.strategy.LanguageStrategy;
import com.github.ezzziy.codesandbox.executor.strategy.LanguageStrategyFactory;
import com.github.ezzziy.codesandbox.model.response.LanguageInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LanguageService {

    private final LanguageStrategyFactory strategyFactory;
    private final DockerImageChecker imageChecker;

    /**
     * 获取所有支持的语言信息
     */
    public List<LanguageInfo> getSupportedLanguages() {
        return strategyFactory.getAllStrategies().stream()
                .map(this::toLanguageInfo)
                .toList();
    }

    /**
     * 检查语言是否可用
     */
    public boolean isLanguageAvailable(String languageId) {
        if (!strategyFactory.isSupported(languageId)) {
            return false;
        }
        
        LanguageStrategy strategy = strategyFactory.getStrategy(languageId, null);
        return imageChecker.isImageAvailable(strategy.getDockerImage());
    }

    private LanguageInfo toLanguageInfo(LanguageStrategy strategy) {
        return LanguageInfo.builder()
                .id(strategy.getLanguageId())
                .name(strategy.getLanguageName())
                .version(strategy.getVersion())
                .extension(strategy.getFileExtension())
                .compilable(strategy.needsCompilation())
                .image(strategy.getDockerImage())
                .available(imageChecker.isImageAvailable(strategy.getDockerImage()))
                .build();
    }
}
```

### 5.2 LanguageInfo 响应模型

```java
package com.github.ezzziy.codesandbox.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LanguageInfo {
    
    /**
     * 语言 ID
     */
    private String id;
    
    /**
     * 语言名称
     */
    private String name;
    
    /**
     * 版本信息
     */
    private String version;
    
    /**
     * 文件扩展名
     */
    private String extension;
    
    /**
     * 是否需要编译
     */
    private Boolean compilable;
    
    /**
     * Docker 镜像
     */
    private String image;
    
    /**
     * 是否可用（镜像是否存在）
     */
    private Boolean available;
}
```

---

## 6. Docker 镜像管理

### 6.1 镜像检查器

```java
package com.github.ezzziy.codesandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerImageChecker {

    private final DockerClient dockerClient;

    /**
     * 检查镜像是否存在
     */
    @Cacheable(value = "imageExists", key = "#imageName")
    public boolean isImageAvailable(String imageName) {
        try {
            List<Image> images = dockerClient.listImagesCmd()
                    .withImageNameFilter(imageName)
                    .exec();
            return !images.isEmpty();
        } catch (Exception e) {
            log.warn("检查镜像失败: {}", imageName, e);
            return false;
        }
    }

    /**
     * 拉取镜像
     */
    public void pullImage(String imageName) {
        try {
            log.info("开始拉取镜像: {}", imageName);
            dockerClient.pullImageCmd(imageName)
                    .start()
                    .awaitCompletion();
            log.info("镜像拉取完成: {}", imageName);
        } catch (Exception e) {
            log.error("拉取镜像失败: {}", imageName, e);
            throw new RuntimeException("拉取镜像失败: " + imageName, e);
        }
    }

    /**
     * 确保镜像存在（不存在则拉取）
     */
    public void ensureImageExists(String imageName) {
        if (!isImageAvailable(imageName)) {
            pullImage(imageName);
        }
    }
}
```

### 6.2 镜像预热脚本

```bash
#!/bin/bash
# scripts/pull-images.sh
# 预拉取所有需要的 Docker 镜像

IMAGES=(
    "gcc:11-bullseye"
    "openjdk:8-jdk-slim"
    "openjdk:11-jdk-slim"
    "python:3.10-slim"
    "golang:1.20-alpine"
)

echo "开始拉取 OJ 沙箱镜像..."

for image in "${IMAGES[@]}"; do
    echo "拉取: $image"
    docker pull "$image"
    if [ $? -eq 0 ]; then
        echo "✓ $image 拉取成功"
    else
        echo "✗ $image 拉取失败"
    fi
done

echo "镜像拉取完成！"
docker images | grep -E "gcc|openjdk|python|golang"
```

---

## 7. 语言配置文件

### 7.1 languages.yml

```yaml
# 语言配置（可选，用于动态配置）
sandbox:
  languages:
    c:
      enabled: true
      image: "gcc:11-bullseye"
      source-file: "main.c"
      compile-command: "gcc -O2 -std=c11 -Wall -o main main.c -lm"
      run-command: "./main"
      compile-timeout: 30000
      pids-limit: 5
      
    cpp:
      enabled: true
      image: "gcc:11-bullseye"
      source-file: "main.cpp"
      compile-command: "g++ -O2 -std=c++11 -Wall -o main main.cpp"
      run-command: "./main"
      compile-timeout: 30000
      pids-limit: 5
      
    java8:
      enabled: true
      image: "openjdk:8-jdk-slim"
      source-file: "Main.java"
      compile-command: "javac -encoding UTF-8 Main.java"
      run-command: "java -Xmx{memoryLimit}m Main"
      compile-timeout: 60000
      pids-limit: 50
      
    java11:
      enabled: true
      image: "openjdk:11-jdk-slim"
      source-file: "Main.java"
      compile-command: "javac -encoding UTF-8 Main.java"
      run-command: "java -Xmx{memoryLimit}m Main"
      compile-timeout: 60000
      pids-limit: 50
      
    python3:
      enabled: true
      image: "python:3.10-slim"
      source-file: "main.py"
      compile-command: "python3 -m py_compile main.py"  # 语法检查
      run-command: "python3 -u main.py"
      compile-timeout: 10000
      pids-limit: 10
      
    golang:
      enabled: true
      image: "golang:1.20-alpine"
      source-file: "main.go"
      compile-command: "go build -o main main.go"
      run-command: "./main"
      compile-timeout: 60000
      pids-limit: 20
```

---

## 8. 语言特定限制

### 8.1 各语言安全配置对比

| 语言 | 进程限制 | 特殊安全措施 | 编译超时 | 备注 |
|------|---------|-------------|---------|------|
| C | 5 | 禁用 execve | 30s | 纯静态 |
| C++ | 5 | 禁用 execve | 30s | 纯静态 |
| Java 8 | 50 | Security Manager | 60s | 禁用反射 |
| Java 11 | 50 | --illegal-access=deny | 60s | 模块安全 |
| Python | 10 | 禁用 os.system | 10s | 解释执行 |
| Go | 20 | 禁用 CGO | 60s | goroutine |

### 8.2 Java 安全策略文件

```
// java.policy - Java 安全策略
grant {
    // 基本权限
    permission java.io.FilePermission "/workspace/-", "read,write";
    permission java.util.PropertyPermission "*", "read";
    
    // 禁止的权限（不授予）
    // permission java.lang.RuntimePermission "exitVM.*";
    // permission java.lang.RuntimePermission "createClassLoader";
    // permission java.lang.RuntimePermission "setSecurityManager";
    // permission java.io.FilePermission "<<ALL FILES>>", "execute";
    // permission java.net.SocketPermission "*", "connect,resolve";
};
```

---

## 9. 扩展新语言

### 9.1 添加新语言步骤

1. **创建策略类**

```java
@Component
public class RustLanguageStrategy extends AbstractLanguageStrategy {
    
    public RustLanguageStrategy() {
        super("rust", "Rust", "1.70", "rust:1.70-slim", 
              "main.rs", ".rs", true);
    }
    
    @Override
    public String[] getCompileCommand(ExecutionContext context) {
        return new String[]{"/bin/sh", "-c", 
            "rustc -O -o main main.rs 2>&1"};
    }
    
    @Override
    public String[] getRunCommand(ExecutionContext context) {
        return new String[]{"./main"};
    }
}
```

2. **添加 Docker 镜像**

```bash
docker pull rust:1.70-slim
```

3. **更新配置文件**（如使用动态配置）

4. **测试**

```bash
curl -X POST http://localhost:8090/api/v1/execute \
  -H "Content-Type: application/json" \
  -d '{
    "language": "rust",
    "code": "fn main() { println!(\"Hello, World!\"); }",
    "inputDataKey": "test/empty.in",
    "timeLimit": 5000,
    "memoryLimit": 256
  }'
```

---

## 10. 下一步

下一篇文档将详细介绍 **安全机制实现**，包括：
- Docker 隔离配置
- 系统调用限制
- 危险代码检测
- 资源限制实现

详见 [05-SECURITY.md](05-SECURITY.md)
