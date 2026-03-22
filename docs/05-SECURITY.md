# 代码执行沙箱服务 - 安全机制实现

## 1. 安全概述

代码沙箱面临的安全威胁：
- **资源耗尽**：无限循环、fork bomb、内存泄漏
- **越权访问**：读取宿主文件系统、网络访问
- **提权攻击**：利用漏洞获取 root 权限
- **恶意代码**：调用系统命令、访问敏感信息

本服务通过多层防护确保沙箱安全：Docker 容器隔离 → 资源限制 → 权限控制 → 危险代码扫描。

---

## 2. Docker 容器安全配置

### 2.1 核心安全参数

安全配置在 `ContainerManager.buildHostConfig()` 中集中构建，无独立的安全配置类：

```java
private HostConfig buildHostConfig(String workDir) {
    long memoryBytes = executionConfig.getMemoryLimit() * 1024L * 1024L;
    long cpuPeriod = 100000L;
    long cpuQuota = (long) (cpuPeriod * executionConfig.getCpuLimit());

    HostConfig hostConfig = HostConfig.newHostConfig()
            // ============ 资源限制 ============
            .withMemory(memoryBytes)
            .withMemorySwap(memoryBytes)          // 等于 memory → 禁用 swap
            .withOomKillDisable(false)              // 允许 OOM Killer 终止
            .withCpuPeriod(cpuPeriod)
            .withCpuQuota(cpuQuota)                // cpuLimit * 100000
            .withPidsLimit((long) executionConfig.getMaxProcesses())

            // ============ 文件系统 ============
            .withReadonlyRootfs(true)              // 只读根文件系统
            .withTmpFs(Map.of(
                    "/tmp", "rw,noexec,nosuid,size=64m",
                    "/sandbox/workspace", "rw,exec,nosuid,size=64m"
            ))

            // ============ 权限控制 ============
            .withCapDrop(Capability.ALL)           // 删除所有 Linux capabilities
            .withSecurityOpts(List.of("no-new-privileges:true"))

            // ============ Ulimits ============
            .withUlimits(List.of(
                    new Ulimit("nofile", maxOpenFiles, maxOpenFiles),
                    new Ulimit("nproc", maxProcesses, maxProcesses),
                    new Ulimit("fsize", outputLimit, outputLimit)
            ));

    // 卷挂载
    if (workDir != null) {
        hostConfig.withBinds(
                new Bind(workDir, new Volume("/sandbox/workspace")),
                new Bind("/var/lib/sandbox-inputs", new Volume("/sandbox/inputs"), AccessMode.ro)
        );
    }
    return hostConfig;
}
```

容器创建时额外设置：

```java
dockerClient.createContainerCmd(image)
        .withHostConfig(hostConfig)
        .withNetworkDisabled(true)               // 完全禁用网络
        .withWorkingDir("/sandbox/workspace")
        .withCmd("tail", "-f", "/dev/null")      // 保持容器运行
        .withStdinOpen(true)
        .withTty(false)
        .exec();
```

Exec 命令中的用户隔离：

```java
dockerClient.execCreateCmd(containerId)
        .withUser("sandbox")                     // sandbox 用户 (uid=1000)
        .withCmd(cmd)
        .exec();
```

### 2.2 安全参数详解

| 参数 | 实际值 | 作用 | 防护威胁 |
|------|--------|------|---------|
| `withMemory` | 配置的 memoryLimit (MB) | 硬内存限制 | 内存耗尽 |
| `withMemorySwap` | 等于 memory | 禁用 swap | 绕过内存限制 |
| `withPidsLimit` | executionConfig.maxProcesses (1024) | 进程数限制 | fork bomb |
| `withNetworkDisabled(true)` | true | 完全禁用网络 | 网络攻击、数据泄露 |
| `withReadonlyRootfs` | **true** | 只读根文件系统 | 防止容器内篡改系统文件 |
| `withCapDrop(ALL)` | 删除所有 capabilities | 降权 | 提权攻击 |
| `no-new-privileges:true` | 防止获取新权限 | 阻止 setuid | setuid 攻击 |
| `withUser("sandbox")` | uid=1000 | 非 root 运行 | 特权操作 |
| `withTmpFs` | `/tmp` (64MB, noexec) + `/sandbox/workspace` (64MB, exec) | 可写临时目录 | 限制临时文件滥用，工作区可执行 |
| `withUlimits` | nofile/nproc/fsize | 资源上限 | 文件描述符/进程/输出耗尽 |

> 注意：根文件系统设为只读（`readonlyRootfs(true)`），编译和运行所需的写入通过 tmpfs 挂载实现：`/sandbox/workspace`（可执行，用于编译产物）和 `/tmp`（不可执行，用于临时文件）。tmpfs 在容器销毁后自动清空，不会残留数据。

---

## 3. Linux Capabilities 控制

### 3.1 Capability 删除策略

使用 `Capability.ALL` 删除所有 capabilities，这是最严格的配置：

| Capability | 功能 | 为何禁用 |
|------------|------|---------|
| `CAP_NET_RAW` | 原始套接字 | 网络嗅探 |
| `CAP_SYS_ADMIN` | 系统管理 | 挂载、命名空间 |
| `CAP_SYS_PTRACE` | 进程追踪 | 调试其他进程 |
| `CAP_SYS_MODULE` | 内核模块 | 加载恶意模块 |
| `CAP_MKNOD` | 创建设备 | 设备文件攻击 |
| `CAP_CHOWN` | 更改所有者 | 权限提升 |
| `CAP_SETUID/SETGID` | 更改用户 | 身份伪造 |
| `CAP_KILL` | 发送信号 | 终止其他进程 |

> 项目中无独立的 `CapabilityConfig` 类，capability 配置直接在 `ContainerManager` 中设置。

---

## 4. 用户隔离

### 4.1 sandbox 用户

自定义沙箱镜像（`sandbox-base`）中创建 `sandbox` 用户：

```dockerfile
RUN groupadd -g 1000 sandbox && \
    useradd -u 1000 -g sandbox -d /sandbox -s /bin/bash sandbox && \
    mkdir -p /sandbox/workspace && \
    chown -R sandbox:sandbox /sandbox
```

- 用户：`sandbox` (uid=1000, gid=1000)
- Home 目录：`/sandbox`
- 工作目录：`/sandbox/workspace`
- exec 命令通过 `.withUser("sandbox")` 以该用户执行

### 4.2 容器内部权限结构

```
/ (root owned)
├── sandbox/
│   └── workspace/     (tmpfs, sandbox:sandbox, rw, exec, 64MB)
│       └── job-{id}/  (sandbox:sandbox, rw) ← 用户代码在此执行
├── tmp/               (tmpfs, 64MB, noexec)
└── sandbox/inputs/    (readonly bind mount)
```

---

## 5. 危险代码检测

### 5.1 架构设计

危险代码扫描**集成在语言策略中**，每种语言的 `LanguageStrategy` 实现定义自己的 `getDangerousPatterns()`。无独立的 `DangerousCodeScanner` 组件。

```java
// LanguageStrategy 接口
public interface LanguageStrategy {
    /** 获取危险代码正则模式列表 */
    List<Pattern> getDangerousPatterns();

    /** 检查代码是否包含危险模式 */
    default String checkDangerousCode(String code) {
        for (Pattern pattern : getDangerousPatterns()) {
            if (pattern.matcher(code).find()) {
                return pattern.pattern();
            }
        }
        return null;  // null 表示安全
    }
}
```

### 5.2 在执行流程中集成

扫描在 `DockerCodeExecutor` 中触发，受 `executionConfig.isEnableCodeScan()` 开关控制：

```java
// DockerCodeExecutor.execute() 中
if (executionConfig.isEnableCodeScan()) {
    String dangerousPattern = strategy.checkDangerousCode(code);
    if (dangerousPattern != null) {
        throw new DangerousCodeException(
            "检测到危险代码模式: " + dangerousPattern, requestId);
    }
}
```

### 5.3 各语言危险模式概览

| 语言 | 拦截类别 | 典型模式 | 模式数量 |
|------|---------|---------|---------|
| C | 系统调用, 文件, 网络, 内联汇编, 危险头文件, mmap, **连字符绕过** | `system()`, `fork()`, `socket()`, `asm`, `#include <sys/socket.h>`, `ptrace()`, `%:include` | ~35 |
| C++ | 同 C + C++ 特有 + **连字符绕过** | `std::thread`, `std::async`, `std::future`, `std::filesystem`, `%:include` | ~40 |
| Java 8/17 | Runtime, 反射, 文件, 网络, ClassLoader, JNI, Unsafe, **Unicode 转义预处理** | `Runtime.getRuntime()`, `Class.forName()`, `System.exit`, `Thread.sleep` | ~40 |
| Python | 系统命令, eval/exec, 文件, 网络, ctypes, pickle, **dunder 链拦截** | `os.system()`, `subprocess`, `eval()`, `socket`, `__subclasses__`, `getattr` | ~50 |

### 5.4 C/C++ 危险模式详情

```java
// CLanguageStrategy.DANGEROUS_PATTERNS（部分）
Pattern.compile("\\bsystem\\s*\\("),       // 系统命令执行
Pattern.compile("\\bexec[lv]?[pe]?\\s*\\("), // exec 族函数
Pattern.compile("\\bfork\\s*\\("),         // 创建子进程
Pattern.compile("\\bsocket\\s*\\("),       // 网络 socket
Pattern.compile("\\b__asm__\\b"),          // 内联汇编
Pattern.compile("\\basm\\s*\\("),          // 内联汇编
Pattern.compile("#include\\s*<sys/socket\\.h>"),  // 网络头文件
Pattern.compile("\\bptrace\\s*\\("),       // 进程追踪
Pattern.compile("\\bmmap\\s*\\("),         // 内存映射
Pattern.compile("\\bdlopen\\s*\\("),       // 动态库加载
// 文件操作补全
Pattern.compile("\\bfopen\\s*\\("),        // 文件打开
Pattern.compile("\\bopen\\s*\\("),         // 低级文件打开
Pattern.compile("\\bunlink\\s*\\("),       // 文件删除
Pattern.compile("\\bremove\\s*\\("),       // 文件/目录删除
Pattern.compile("\\bopendir\\s*\\("),      // 目录操作
// C11 连字符（digraph）绕过检测
Pattern.compile("%:\\s*include"),           // %:include 等价于 #include
```

### 5.5 Java 危险模式详情

```java
// Java8LanguageStrategy.DANGEROUS_PATTERNS（部分）
Pattern.compile("Runtime\\.getRuntime\\(\\)"),   // 命令执行
Pattern.compile("ProcessBuilder"),               // 进程构建器
Pattern.compile("Class\\.forName\\s*\\("),       // 反射加载
Pattern.compile("setAccessible\\s*\\(\\s*true"), // 绕过访问控制
Pattern.compile("System\\.exit"),                // 退出 JVM
Pattern.compile("Thread\\.sleep\\s*\\("),        // 休眠（可用于计时攻击）
Pattern.compile("System\\.loadLibrary"),         // JNI 加载
Pattern.compile("sun\\.misc\\.Unsafe"),          // Unsafe 操作
Pattern.compile("import\\s+java\\.net\\."),      // 网络类导入
Pattern.compile("ClassLoader"),                  // 类加载器
// 文件操作补全
Pattern.compile("\\bFileWriter\\b"),             // 文件写入
Pattern.compile("\\bFileReader\\b"),             // 文件读取
Pattern.compile("\\bFiles\\."),                  // NIO Files 工具类
Pattern.compile("\\bPath\\.of\\b"),              // NIO Path
Pattern.compile("\\bPaths\\.get\\b"),            // NIO Paths
```

> **Java Unicode 转义预处理**：Java 8/17 策略覆盖了 `checkDangerousCode()` 方法，在正则匹配前先通过 `JavaUnicodeDecoder.decode()` 将 `\uXXXX` 形式的 Unicode 转义还原为实际字符，防止攻击者用 `\u0052untime` 等方式绕过黑名单。

### 5.6 Python 危险模式详情

```java
// Python3LanguageStrategy.DANGEROUS_PATTERNS（部分）
Pattern.compile("\\bos\\.system\\s*\\("),        // 系统命令
Pattern.compile("\\bsubprocess\\."),             // subprocess 模块
Pattern.compile("\\beval\\s*\\("),               // eval 执行
Pattern.compile("\\bexec\\s*\\("),               // exec 执行
Pattern.compile("\\bsocket\\.socket\\s*\\("),    // 网络 socket
Pattern.compile("import\\s+ctypes"),             // C 类型接口
Pattern.compile("import\\s+multiprocessing"),    // 多进程
Pattern.compile("\\bpickle\\.loads?\\s*\\("),    // pickle 反序列化
Pattern.compile("\\b__import__\\s*\\("),         // 动态导入
// 文件操作补全
Pattern.compile("\\bos\\.remove\\s*\\("),        // 文件删除
Pattern.compile("\\bos\\.unlink\\s*\\("),        // 文件删除
Pattern.compile("\\bshutil\\."),                 // 高级文件操作
Pattern.compile("import\\s+pathlib"),            // pathlib 模块
// dunder 链拦截
Pattern.compile("__builtins__"),                 // 内建函数访问
Pattern.compile("__subclasses__"),               // 子类链遍历
Pattern.compile("__globals__"),                  // 全局变量访问
Pattern.compile("__bases__"),                    // 基类链遍历
Pattern.compile("__mro__"),                      // MRO 链遍历
Pattern.compile("__class__"),                    // 类属性访问
Pattern.compile("__dict__"),                     // 字典属性访问
Pattern.compile("__loader__"),                   // 加载器访问
Pattern.compile("__spec__"),                     // 模块规范访问
// 反射函数
Pattern.compile("\\bgetattr\\s*\\("),            // 动态属性获取
Pattern.compile("\\bsetattr\\s*\\("),            // 动态属性设置
Pattern.compile("\\bdelattr\\s*\\("),            // 动态属性删除
Pattern.compile("\\btype\\s*\\("),               // 元类操作
```

---

## 6. 资源限制

### 6.1 限制参数（来自 ExecutionConfig）

| 资源 | 配置项 | 默认值 | 实现方式 |
|------|--------|--------|---------|
| 内存 | `memoryLimit` | 256 MB | Docker `--memory` + `--memory-swap` |
| CPU | `cpuLimit` | 1.0 核 | Docker `--cpu-period` + `--cpu-quota` |
| 编译超时 | `compileTimeout` | 30 秒 | Docker exec timeout |
| 运行超时 | `runTimeout` | 10 秒 | Docker exec timeout |
| 总超时 | `totalTimeout` | 300 秒 | 全流程超时 |
| 进程数 | `maxProcesses` | 1024 | Docker `--pids-limit` + ulimit nproc |
| 文件描述符 | `maxOpenFiles` | 256 | ulimit nofile |
| 输出大小 | `outputLimit` | 65536 字节 | ulimit fsize + 代码层截断 |

### 6.2 时间测量

运行时间通过 shell 脚本在容器内精确测量（纳秒精度）：

```bash
START=$(date +%s%N); <command>; EXIT_CODE=$?; END=$(date +%s%N);
echo '__EXEC_TIME_NS__:'$((END-START))
```

`DockerCodeExecutor` 从 stdout 提取时间标记并转换为毫秒。

### 6.3 内存测量

使用 `/usr/bin/time -f '%M' -o $MEMFILE` 在容器内捕获 RSS 峰值（KB）：

```bash
MEMFILE=/tmp/mem_$$;
/usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <command>';
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE;
echo '__EXEC_MEM_KB__:'$MEM
```

内存值写入临时文件 `$MEMFILE`，不污染 stdout/stderr。

---

## 7. 网络隔离

```java
// ContainerManager 创建容器时
.withNetworkDisabled(true)
```

同时 `--cap-drop ALL` 也移除了 `CAP_NET_RAW` 等网络相关 capability。

---

## 8. 安全检查清单

### 8.1 当前已实现的安全措施

- [x] `--cap-drop=ALL` — 删除所有 Linux capabilities
- [x] `--security-opt=no-new-privileges:true` — 禁止获取新权限
- [x] `--network-disabled` — 完全禁用网络
- [x] `--memory` + `--memory-swap` — 内存限制 + 禁用 swap
- [x] `--pids-limit` — 进程数限制（防 fork bomb）
- [x] ulimits (nofile, nproc, fsize) — 资源上限
- [x] `readonlyRootfs(true)` — 只读根文件系统
- [x] tmpfs `/tmp` (noexec, 64MB) — 可写但不可执行的临时目录
- [x] tmpfs `/sandbox/workspace` (exec, 64MB) — 可写可执行的工作目录
- [x] `sandbox` 用户 (uid=1000) — 非 root 执行
- [x] 各语言危险代码正则扫描（可通过 `enableCodeScan` 开关）
- [x] Java Unicode 转义预处理（防 `\uXXXX` 绕过黑名单）
- [x] C/C++ 连字符检测（防 `%:include` 绕过 `#include` 黑名单）
- [x] Python dunder 链拦截（防 `__subclasses__`/`__globals__` 等反射链攻击）
- [x] Docker 默认 seccomp 配置
- [x] `DangerousCodeException` 异常处理
- [x] 只读输入数据挂载（`/sandbox/inputs` 以 `AccessMode.ro` 挂载）
- [x] 源代码通过 exec + base64 写入容器（适配只读根文件系统）

### 8.2 注意事项

- `readonlyRootfs` 设为 `true`，写入需求通过 tmpfs 挂载实现（`/sandbox/workspace` 和 `/tmp`）
- 源代码通过 `exec + base64` 方式写入容器（Docker archive API 被只读根文件系统阻止）
- Docker 默认 seccomp 已禁止约 44 个危险系统调用（reboot, mount, module 等）
- 项目中无独立的 `SecurityConfigBuilder`、`SecurityAuditLogger`、`PathValidator` 类
- 无自定义 seccomp profile 文件，依赖 Docker 默认

---

## 9. 下一步

下一篇文档将详细介绍 **缓存与 OSS 集成**，包括：
- 本地文件缓存设计
- InputDataService 实现
- 远程数据下载与缓存

详见 [06-CACHE-OSS.md](06-CACHE-OSS.md)
