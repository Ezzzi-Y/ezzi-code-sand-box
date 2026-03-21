# 黑名单绕过修复方案

## 背景

排查发现三类语言均存在绕过正则黑名单的手段：

| 语言 | 绕过手段 | 严重性 |
|---|---|---|
| Java | `\uXXXX` Unicode 转义在词法分析前解析，正则看到原始文本 | **严重** |
| C/C++ | 预处理器宏 `#define X fopen` + token pasting `a##b` | **严重** |
| C/C++ | 连字符 `%:include` 绕过 `#include` 检测 | **中等** |
| Python | `getattr(__builtins__, 'open')` 绕过当前 getattr 模式 | **严重** |
| Python | `__class__.__bases__[0].__subclasses__()` dunder 链 | **严重** |

原则：黑名单是"尽力而为"的第一道防线，容器层兜底是真正的安全边界。修复目标是堵住**已知的、成本低的**绕过路径。

---

## 一、Java：源码预处理解析 Unicode 转义

### 1.1 问题

Java 编译器在词法分析之前解析 `\uXXXX`（JLS 3.3），且允许多个 `u`（`\uuuu0046` = `F`）。

```java
// 绕过所有现有黑名单
\u0052untime.getRuntime().exec("cat /etc/passwd");  // Runtime
new \u0046ile("/etc/passwd");                         // File
\u0046iles.readString(Path.of("/etc/passwd"));        // Files
```

### 1.2 修复方案

在 `LanguageStrategy.checkDangerousCode()` 中，对 Java 语言增加预处理步骤：先将所有 `\u+XXXX` 序列解析为实际字符，再执行正则匹配。

### 1.3 新增工具类

**新建文件**: `src/main/java/com/github/ezzziy/codesandbox/util/JavaUnicodeDecoder.java`

```java
package com.github.ezzziy.codesandbox.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java Unicode 转义预处理器
 * <p>
 * 按 JLS 3.3 规则，将源码中的 \uXXXX（含多 u 形式 \uuuuXXXX）解析为实际字符，
 * 用于在正则黑名单检测前消除 Unicode 转义绕过。
 */
public class JavaUnicodeDecoder {

    // 匹配 \u+ 后跟 4 位十六进制，与 JLS 3.3 一致
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+([0-9a-fA-F]{4})");

    /**
     * 将 Java 源码中的 Unicode 转义序列解析为实际字符
     *
     * @param source 原始 Java 源码
     * @return 解析后的源码
     */
    public static String decode(String source) {
        if (source == null || !source.contains("\\u")) {
            return source;
        }
        Matcher matcher = UNICODE_ESCAPE.matcher(source);
        StringBuilder sb = new StringBuilder(source.length());
        while (matcher.find()) {
            char ch = (char) Integer.parseInt(matcher.group(1), 16);
            // Matcher.quoteReplacement 防止 $ 和 \ 在 replacement 中被特殊解释
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(ch)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
```

### 1.4 修改 Java8 / Java17 策略

**文件**:
- `Java8LanguageStrategy.java`
- `Java17LanguageStrategy.java`

在两个文件中 Override `checkDangerousCode`，先解码再检测：

```java
@Override
public String checkDangerousCode(String code) {
    // 预处理：解析 Java Unicode 转义，防止 \uXXXX 绕过
    String decodedCode = JavaUnicodeDecoder.decode(code);
    for (Pattern pattern : getDangerousPatterns()) {
        if (pattern.matcher(decodedCode).find()) {
            return pattern.pattern();
        }
    }
    return null;
}
```

### 1.5 验证

| 测试用例 | 预期结果 |
|---|---|
| `\u0046iles.readString(Path.of("/etc/passwd"))` | 被 `Files\.read` 拦截 |
| `\uuuuu0052untime.getRuntime()` | 被 `Runtime\.getRuntime\(\)` 拦截 |
| `new \u0046ile("/tmp/test")` | 被 `new\s+File\s*\(` 拦截 |
| `import java.n\u0065t.Socket;` | 被 `import\s+java\.net\.` 拦截 |
| 正常 OJ 代码（Scanner + System.out） | 正常通过 |

---

## 二、C/C++：连字符检测 + 容器兜底

### 2.1 预处理器宏 — 无法用正则解决

```c
#define X fopen
FILE *fp = X("/etc/passwd", "r");   // 正则只看到 X(

#define CONCAT(a,b) a##b
CONCAT(sys,tem)("whoami");          // token pasting → system("whoami")
```

宏是 C/C++ 的核心语言特性，OJ 代码广泛使用 `#define`，无法禁用。

**结论**：宏绕过只能依赖容器层兜底（只读 rootfs + 无敏感数据 + 网络禁用）。

### 2.2 连字符 — 补充正则

连字符 `%:` 等价于 `#`，在 C11/C++11 下默认启用，无需编译参数：

```c
%:include <fcntl.h>    // 等价 #include <fcntl.h>，绕过正则
```

### 2.3 修改文件

**文件**:
- `CLanguageStrategy.java`
- `CppLanguageStrategy.java`

对每一条现有的 `#include` 检测模式，新增对应的 `%:include` 版本：

**CLanguageStrategy.java** 新增：
```java
// 连字符绕过 #include
Pattern.compile("%:\\s*include\\s*<dirent\\.h>"),
Pattern.compile("%:\\s*include\\s*<sys/stat\\.h>"),
Pattern.compile("%:\\s*include\\s*<fcntl\\.h>"),
Pattern.compile("%:\\s*include\\s*<sys/socket\\.h>"),
Pattern.compile("%:\\s*include\\s*<netinet/"),
Pattern.compile("%:\\s*include\\s*<arpa/"),
Pattern.compile("%:\\s*include\\s*<sys/ptrace\\.h>"),
```

**CppLanguageStrategy.java** 新增（除上述外额外加）：
```java
Pattern.compile("%:\\s*include\\s*<filesystem>"),
```

### 2.4 验证

| 测试用例 | 预期结果 |
|---|---|
| `%:include <fcntl.h>` | 被拦截 |
| `%: include <dirent.h>` | 被拦截（`%:\s*include` 覆盖空格） |
| `#include <stdio.h>`（正常头文件） | 正常通过 |
| `#define X fopen` + `X(...)` | **黑名单无法拦截，由容器兜底** |

---

## 三、Python：封堵 dunder 反射链 + 加强 getattr

### 3.1 问题

Python 的对象模型提供大量反射路径，当前黑名单均未覆盖：

```python
# 绕过1：getattr 第二参数非 __ 开头
getattr(__builtins__, 'open')('/etc/passwd').read()

# 绕过2：MRO 子类链
().__class__.__bases__[0].__subclasses__()

# 绕过3：__builtins__.__dict__ 访问
__builtins__.__dict__['open']('/etc/passwd').read()
```

### 3.2 修改文件

**文件**: `Python3LanguageStrategy.java`

新增以下模式：

```java
// ===== dunder 反射链（封堵 MRO / subclass / globals 等绕过）=====
Pattern.compile("__builtins__"),
Pattern.compile("__subclasses__"),
Pattern.compile("__globals__"),
Pattern.compile("__bases__"),
Pattern.compile("__mro__"),
Pattern.compile("__class__"),
Pattern.compile("__dict__"),
Pattern.compile("__loader__"),
Pattern.compile("__spec__"),
// ===== getattr 全面拦截 =====
Pattern.compile("\\bgetattr\\s*\\("),
// ===== setattr / delattr =====
Pattern.compile("\\bsetattr\\s*\\("),
Pattern.compile("\\bdelattr\\s*\\("),
// ===== type() 元类操作 =====
Pattern.compile("\\btype\\s*\\("),
```

同时移除现有的过窄的 getattr 模式（`getattr\s*\(.*,\s*['"]__`），替换为上面的全面拦截版本。

### 3.3 误杀风险评估

| 被拦截的 token | OJ 正常代码是否使用 | 结论 |
|---|---|---|
| `__builtins__` | 不使用 | 安全 |
| `__subclasses__` | 不使用 | 安全 |
| `__globals__` | 不使用 | 安全 |
| `__bases__` / `__mro__` | 不使用 | 安全 |
| `__class__` | 极少，OJ 不需要 | 可接受 |
| `__dict__` | 极少，OJ 不需要 | 可接受 |
| `getattr()` | 极少，OJ 不需要 | 可接受 |
| `type()` | 偶尔用于调试，OJ 提交不需要 | 可接受 |

OJ 场景下用户代码只需 `input()` / `print()` / 数据结构 / 算法，以上 token 均不属于正常 OJ 代码。

### 3.4 验证

| 测试用例 | 预期结果 |
|---|---|
| `getattr(__builtins__, 'open')` | 被 `\bgetattr\s*\(` 拦截 |
| `().__class__.__bases__[0].__subclasses__()` | 被 `__class__` 拦截 |
| `__builtins__.__dict__['open']` | 被 `__builtins__` 拦截 |
| `sc.__init__.__globals__['os']` | 被 `__globals__` 拦截 |
| 正常 OJ 代码（input/print/list/dict） | 正常通过 |

---

## 修改文件清单

| 文件 | 改动类型 | 改动内容 |
|---|---|---|
| `util/JavaUnicodeDecoder.java` | **新建** | Unicode 转义解析工具类 |
| `Java8LanguageStrategy.java` | 修改 | Override `checkDangerousCode`，增加预处理 |
| `Java17LanguageStrategy.java` | 修改 | 同上 |
| `CLanguageStrategy.java` | 修改 | 新增 7 条 `%:include` 连字符模式 |
| `CppLanguageStrategy.java` | 修改 | 新增 8 条 `%:include` 连字符模式 |
| `Python3LanguageStrategy.java` | 修改 | 新增 ~14 条 dunder/getattr/type 模式，替换旧 getattr 模式 |

---

## 防御体系总览

```
用户代码
  │
  ▼
┌─────────────────────────────────────┐
│ 第一道防线：正则黑名单              │  ← 本次修复重点
│  • Java: Unicode 转义预处理后匹配   │
│  • C/C++: 补充连字符检测            │
│  • Python: 封堵 dunder 反射链       │
└─────────────┬───────────────────────┘
              │ （仍可能被绕过）
              ▼
┌─────────────────────────────────────┐
│ 第二道防线：容器层兜底（不可绕过）  │  ← 已在上一次加固中完成
│  • readonlyRootfs = true            │
│  • 无 /sandbox/inputs 挂载          │
│  • 网络禁用                         │
│  • capability 全部 drop             │
│  • tmpFs 仅 /tmp + /workspace       │
│  • ulimit 限制文件数/进程数/文件大小│
└─────────────────────────────────────┘
```

即使黑名单被绕过，容器内：无敏感数据可读、无网络可外传、无持久化路径可写、无特权可提升。
