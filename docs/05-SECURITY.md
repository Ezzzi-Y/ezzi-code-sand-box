# OJ 代码执行沙箱服务 - 安全机制实现

## 1. 安全概述

代码沙箱面临的安全威胁：
- **资源耗尽**：无限循环、fork bomb、内存泄漏
- **越权访问**：读取宿主文件系统、网络访问
- **提权攻击**：利用漏洞获取 root 权限
- **恶意代码**：调用系统命令、访问敏感信息

本文档详细说明如何通过多层防护确保沙箱安全。

---

## 2. Docker 安全配置

### 2.1 核心安全参数

```java
/**
 * 安全配置构建器
 */
@Component
public class SecurityConfigBuilder {

    /**
     * 构建安全的 HostConfig
     */
    public HostConfig buildSecureHostConfig(int memoryMB, boolean enableNetwork) {
        return HostConfig.newHostConfig()
                // ============ 资源限制 ============
                // 内存硬限制
                .withMemory(memoryMB * 1024L * 1024L)
                // 禁用 swap（防止绕过内存限制）
                .withMemorySwap(memoryMB * 1024L * 1024L)
                // 内存软限制（触发 OOM 优先级）
                .withMemoryReservation((long) (memoryMB * 0.9 * 1024 * 1024))
                // CPU 限制（100000 微秒 = 0.1 秒，相当于 10% CPU）
                .withCpuQuota(100000L)
                .withCpuPeriod(100000L)
                // 进程数限制（防止 fork bomb）
                .withPidsLimit(10L)
                // 禁用 OOM Killer（让进程被直接终止）
                .withOomKillDisable(false)
                
                // ============ 网络隔离 ============
                .withNetworkMode(enableNetwork ? "bridge" : "none")
                
                // ============ 文件系统安全 ============
                // 只读根文件系统
                .withReadonlyRootfs(true)
                // 可写的临时目录（限制大小，禁止执行）
                .withTmpFs(Map.of(
                    "/tmp", "rw,noexec,nosuid,nodev,size=64m",
                    "/var/tmp", "rw,noexec,nosuid,nodev,size=16m"
                ))
                
                // ============ 权限控制 ============
                // 禁止获取新权限
                .withSecurityOpts(List.of("no-new-privileges"))
                // 删除所有 capabilities
                .withCapDrop(Capability.ALL)
                
                // ============ 用户隔离 ============
                // 使用 nobody 用户（UID 65534）
                // 注意：这在 createContainerCmd 中设置
                
                // ============ 设备限制 ============
                .withDevices(List.of())  // 不挂载任何设备
                
                // ============ IPC 隔离 ============
                .withIpcMode("private")
                
                // ============ PID 隔离 ============
                .withPidMode("private");
    }
}
```

### 2.2 安全参数详解

| 参数 | 作用 | 防护威胁 |
|------|------|---------|
| `--memory` | 硬内存限制 | 内存耗尽 |
| `--memory-swap` | 禁用 swap | 绕过内存限制 |
| `--pids-limit` | 进程数限制 | fork bomb |
| `--network=none` | 禁用网络 | 网络攻击、数据泄露 |
| `--read-only` | 只读文件系统 | 恶意文件写入 |
| `--cap-drop=ALL` | 删除所有能力 | 提权攻击 |
| `--security-opt=no-new-privileges` | 禁止提权 | setuid 攻击 |
| `--user=nobody` | 非 root 运行 | 特权操作 |

---

## 3. Linux Capabilities 控制

### 3.1 Capability 说明

默认删除的所有 Capabilities：

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
| `ALL` | 所有能力 | 完全禁用 |

### 3.2 代码实现

```java
/**
 * Capability 安全配置
 */
public class CapabilityConfig {
    
    /**
     * 获取需要删除的 Capabilities
     */
    public static List<Capability> getDroppedCapabilities() {
        // 删除所有 - 最安全的选择
        return List.of(Capability.ALL);
    }
    
    /**
     * 某些场景可能需要保留的 Capabilities（通常不需要）
     */
    public static List<Capability> getAddedCapabilities() {
        // OJ 沙箱不需要任何额外权限
        return List.of();
    }
}
```

---

## 4. seccomp 系统调用过滤

### 4.1 seccomp 配置文件

```json
{
    "defaultAction": "SCMP_ACT_ERRNO",
    "architectures": ["SCMP_ARCH_X86_64", "SCMP_ARCH_X86"],
    "syscalls": [
        {
            "names": [
                "read", "write", "close", "fstat", "lseek",
                "mmap", "mprotect", "munmap", "brk",
                "ioctl", "access", "pipe", "select",
                "sched_yield", "nanosleep", "alarm",
                "getpid", "gettid", "getuid", "getgid",
                "exit", "exit_group", "uname",
                "fcntl", "flock", "fsync",
                "getcwd", "chdir", "readlink",
                "stat", "lstat", "poll", "ppoll",
                "clock_gettime", "clock_getres",
                "futex", "set_robust_list",
                "arch_prctl", "set_tid_address",
                "pread64", "pwrite64", "readv", "writev",
                "mremap", "rt_sigaction", "rt_sigprocmask",
                "rt_sigreturn", "sigaltstack",
                "prctl", "getrlimit", "setrlimit",
                "dup", "dup2", "dup3", "pipe2",
                "epoll_create", "epoll_create1", "epoll_ctl",
                "epoll_wait", "epoll_pwait",
                "openat", "newfstatat", "faccessat",
                "getrandom", "memfd_create",
                "clone", "fork", "vfork", "wait4", "waitid"
            ],
            "action": "SCMP_ACT_ALLOW"
        },
        {
            "names": [
                "execve"
            ],
            "action": "SCMP_ACT_ALLOW",
            "comment": "只允许执行一次（启动程序）"
        },
        {
            "names": [
                "socket", "connect", "accept", "bind", "listen",
                "sendto", "recvfrom", "sendmsg", "recvmsg"
            ],
            "action": "SCMP_ACT_ERRNO",
            "errnoRet": 1,
            "comment": "禁止网络系统调用"
        },
        {
            "names": [
                "ptrace", "process_vm_readv", "process_vm_writev"
            ],
            "action": "SCMP_ACT_ERRNO",
            "errnoRet": 1,
            "comment": "禁止进程调试"
        },
        {
            "names": [
                "mount", "umount2", "pivot_root", "chroot"
            ],
            "action": "SCMP_ACT_ERRNO",
            "errnoRet": 1,
            "comment": "禁止文件系统操作"
        },
        {
            "names": [
                "init_module", "finit_module", "delete_module"
            ],
            "action": "SCMP_ACT_ERRNO",
            "errnoRet": 1,
            "comment": "禁止内核模块操作"
        },
        {
            "names": [
                "reboot", "sethostname", "setdomainname",
                "kexec_load", "kexec_file_load"
            ],
            "action": "SCMP_ACT_ERRNO",
            "errnoRet": 1,
            "comment": "禁止系统管理操作"
        }
    ]
}
```

### 4.2 应用 seccomp 配置

```java
/**
 * 使用自定义 seccomp 配置
 */
public HostConfig buildHostConfigWithSeccomp(String seccompProfilePath) {
    return HostConfig.newHostConfig()
            // ... 其他配置 ...
            .withSecurityOpts(List.of(
                "no-new-privileges",
                "seccomp=" + seccompProfilePath
            ));
}
```

### 4.3 Docker 默认 seccomp 说明

Docker 默认已启用 seccomp，禁止了约 44 个危险系统调用：
- `reboot`, `settimeofday`
- `mount`, `umount`
- `init_module`, `delete_module`
- `acct`, `swapon`, `swapoff`
- 等等

对于 OJ 沙箱，默认配置通常足够，但可以根据需要加强。

---

## 5. 危险代码检测

### 5.1 代码扫描器

```java
package com.github.ezzziy.codesandbox.security;

import com.github.ezzziy.codesandbox.exception.DangerousCodeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 危险代码扫描器
 */
@Slf4j
@Component
public class DangerousCodeScanner {

    /**
     * 各语言的危险模式
     */
    private static final Map<String, List<DangerPattern>> LANGUAGE_PATTERNS = Map.of(
        "c", List.of(
            new DangerPattern("system\\s*\\(", "禁止使用 system() 函数"),
            new DangerPattern("popen\\s*\\(", "禁止使用 popen() 函数"),
            new DangerPattern("exec[lvpe]*\\s*\\(", "禁止使用 exec 系列函数"),
            new DangerPattern("fork\\s*\\(", "禁止使用 fork() 函数"),
            new DangerPattern("#include\\s*<sys/socket\\.h>", "禁止使用网络头文件"),
            new DangerPattern("#include\\s*<netinet/", "禁止使用网络头文件"),
            new DangerPattern("#include\\s*<arpa/", "禁止使用网络头文件"),
            new DangerPattern("__asm__", "禁止使用内联汇编"),
            new DangerPattern("asm\\s*\\(", "禁止使用内联汇编"),
            new DangerPattern("/etc/passwd", "禁止访问系统文件"),
            new DangerPattern("/etc/shadow", "禁止访问系统文件")
        ),
        
        "cpp", List.of(
            new DangerPattern("system\\s*\\(", "禁止使用 system() 函数"),
            new DangerPattern("popen\\s*\\(", "禁止使用 popen() 函数"),
            new DangerPattern("exec[lvpe]*\\s*\\(", "禁止使用 exec 系列函数"),
            new DangerPattern("fork\\s*\\(", "禁止使用 fork() 函数"),
            new DangerPattern("#include\\s*<sys/socket\\.h>", "禁止使用网络头文件"),
            new DangerPattern("__asm__", "禁止使用内联汇编"),
            new DangerPattern("asm\\s*\\(", "禁止使用内联汇编"),
            new DangerPattern("asm\\s+volatile", "禁止使用内联汇编")
        ),
        
        "java", List.of(
            new DangerPattern("Runtime\\.getRuntime\\(\\)\\.exec", "禁止执行系统命令"),
            new DangerPattern("ProcessBuilder", "禁止创建进程"),
            new DangerPattern("java\\.net\\.", "禁止使用网络类"),
            new DangerPattern("java\\.io\\.File(?!Reader|Writer|InputStream|OutputStream)", "限制文件操作"),
            new DangerPattern("java\\.lang\\.reflect\\.", "禁止使用反射"),
            new DangerPattern("Class\\.forName\\(", "禁止动态加载类"),
            new DangerPattern("setAccessible\\(true\\)", "禁止绕过访问控制"),
            new DangerPattern("System\\.exit\\(", "禁止调用 System.exit"),
            new DangerPattern("SecurityManager", "禁止操作安全管理器"),
            new DangerPattern("\\.loadLibrary\\(", "禁止加载本地库"),
            new DangerPattern("\\.load\\(", "禁止加载本地库"),
            new DangerPattern("native\\s+", "禁止使用 native 方法")
        ),
        
        "python", List.of(
            new DangerPattern("os\\.system\\(", "禁止执行系统命令"),
            new DangerPattern("os\\.popen\\(", "禁止执行系统命令"),
            new DangerPattern("os\\.exec[lvpe]*\\(", "禁止执行系统命令"),
            new DangerPattern("subprocess\\.", "禁止使用 subprocess"),
            new DangerPattern("commands\\.", "禁止使用 commands 模块"),
            new DangerPattern("import\\s+socket", "禁止导入 socket"),
            new DangerPattern("from\\s+socket\\s+import", "禁止导入 socket"),
            new DangerPattern("import\\s+urllib", "禁止导入网络库"),
            new DangerPattern("import\\s+requests", "禁止导入网络库"),
            new DangerPattern("import\\s+http", "禁止导入网络库"),
            new DangerPattern("eval\\(", "禁止使用 eval"),
            new DangerPattern("exec\\(", "禁止使用 exec"),
            new DangerPattern("compile\\(", "禁止使用 compile"),
            new DangerPattern("__import__\\(", "禁止使用 __import__"),
            new DangerPattern("open\\([^)]*['\"]/(etc|proc|sys|dev)", "禁止访问系统目录"),
            new DangerPattern("ctypes\\.", "禁止使用 ctypes"),
            new DangerPattern("multiprocessing\\.", "禁止使用多进程")
        ),
        
        "golang", List.of(
            new DangerPattern("os/exec", "禁止导入 os/exec"),
            new DangerPattern("exec\\.Command\\(", "禁止执行命令"),
            new DangerPattern("syscall\\.", "禁止使用 syscall"),
            new DangerPattern("net\\.", "禁止使用网络"),
            new DangerPattern("net/http", "禁止使用 HTTP"),
            new DangerPattern("unsafe\\.", "禁止使用 unsafe"),
            new DangerPattern("reflect\\.(?!TypeOf|ValueOf)", "限制反射使用"),
            new DangerPattern("os\\.(?!Stdin|Stdout|Stderr)", "限制 os 包使用"),
            new DangerPattern("plugin\\.", "禁止使用插件"),
            new DangerPattern("cgo", "禁止使用 CGO")
        )
    );

    /**
     * 扫描代码中的危险模式
     */
    public void scan(String language, String code) {
        List<DangerPattern> patterns = LANGUAGE_PATTERNS.get(language.toLowerCase());
        if (patterns == null) {
            log.warn("未找到语言 {} 的安全规则", language);
            return;
        }

        for (DangerPattern pattern : patterns) {
            if (pattern.matches(code)) {
                log.warn("检测到危险代码: language={}, pattern={}, reason={}", 
                        language, pattern.pattern, pattern.reason);
                throw new DangerousCodeException(pattern.reason);
            }
        }
        
        log.debug("代码安全检查通过: language={}", language);
    }

    /**
     * 危险模式定义
     */
    private record DangerPattern(String pattern, String reason) {
        private static final Map<String, Pattern> COMPILED_PATTERNS = 
                new java.util.concurrent.ConcurrentHashMap<>();

        public boolean matches(String code) {
            Pattern compiled = COMPILED_PATTERNS.computeIfAbsent(
                    pattern, 
                    p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
            );
            return compiled.matcher(code).find();
        }
    }
}
```

### 5.2 在执行流程中集成

```java
@Service
@RequiredArgsConstructor
public class ExecutionServiceImpl implements ExecutionService {

    private final DangerousCodeScanner codeScanner;
    private final CodeExecutor executor;

    @Override
    public ExecutionResult execute(ExecuteRequest request) {
        // 1. 代码安全扫描
        codeScanner.scan(request.getLanguage(), request.getCode());
        
        // 2. 继续执行...
        return executor.execute(buildContext(request));
    }
}
```

---

## 6. 资源限制实现

### 6.1 内存限制

```java
/**
 * 内存限制配置
 */
public class MemoryLimiter {
    
    /**
     * 计算容器内存限制
     * @param requestedMB 请求的内存（MB）
     * @param maxMB 最大允许（MB）
     * @return 实际限制（字节）
     */
    public long calculateMemoryLimit(int requestedMB, int maxMB) {
        int actualMB = Math.min(requestedMB, maxMB);
        return actualMB * 1024L * 1024L;
    }
    
    /**
     * 判断是否内存超限
     */
    public boolean isMemoryExceeded(long usedBytes, int limitMB) {
        return usedBytes > limitMB * 1024L * 1024L;
    }
}
```

### 6.2 时间限制

```java
/**
 * 时间限制实现
 */
public class TimeLimiter {

    /**
     * 带超时的执行
     */
    public <T> T executeWithTimeout(Callable<T> task, long timeoutMs) 
            throws TimeoutException, ExecutionException, InterruptedException {
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(task);
        
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }
    
    /**
     * 使用 timeout 命令（Linux）
     */
    public String[] wrapWithTimeout(String[] command, long timeoutSeconds) {
        String cmd = String.join(" ", command);
        return new String[]{
            "/bin/sh", "-c",
            String.format("timeout -s KILL %d %s", timeoutSeconds, cmd)
        };
    }
}
```

### 6.3 输出限制

```java
/**
 * 输出限制
 */
public class OutputLimiter {
    
    private static final int MAX_OUTPUT_SIZE = 64 * 1024;  // 64KB
    
    /**
     * 限制输出大小
     */
    public String limitOutput(String output) {
        if (output == null) return "";
        
        if (output.length() > MAX_OUTPUT_SIZE) {
            return output.substring(0, MAX_OUTPUT_SIZE) + 
                   "\n... [OUTPUT TRUNCATED - exceeded 64KB limit]";
        }
        return output;
    }
    
    /**
     * 带流式限制的输出收集器
     */
    public static class LimitedOutputStream extends OutputStream {
        
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxSize;
        private boolean truncated = false;
        
        public LimitedOutputStream(int maxSize) {
            this.maxSize = maxSize;
        }
        
        @Override
        public void write(int b) throws IOException {
            if (buffer.size() < maxSize) {
                buffer.write(b);
            } else {
                truncated = true;
            }
        }
        
        public String getOutput() {
            String output = buffer.toString(StandardCharsets.UTF_8);
            if (truncated) {
                output += "\n... [OUTPUT TRUNCATED]";
            }
            return output;
        }
        
        public boolean isTruncated() {
            return truncated;
        }
    }
}
```

---

## 7. Fork Bomb 防护

### 7.1 进程数限制

```bash
# Docker --pids-limit 参数
docker run --pids-limit=10 ...
```

### 7.2 测试 Fork Bomb 防护

```c
// C 语言 fork bomb 测试
#include <unistd.h>
int main() {
    while(1) fork();  // 无限创建进程
    return 0;
}
```

预期行为：
- 创建约 10 个进程后，fork() 返回 -1 (EAGAIN)
- 进程总数不会超过限制
- 系统稳定运行

### 7.3 ulimit 作为后备

```java
/**
 * 在容器中设置 ulimit
 */
public String[] wrapWithUlimit(String[] command) {
    String cmd = String.join(" ", command);
    return new String[]{
        "/bin/sh", "-c",
        "ulimit -u 10 && " + cmd  // 限制用户进程数
    };
}
```

---

## 8. 网络隔离

### 8.1 Docker 网络配置

```java
// 完全禁用网络
HostConfig.newHostConfig()
    .withNetworkMode("none")
```

### 8.2 iptables 规则（额外防护）

```bash
# 在宿主机上阻止沙箱容器的网络访问
iptables -I DOCKER-USER -i br-sandbox -j DROP
iptables -I DOCKER-USER -o br-sandbox -j DROP
```

### 8.3 网络隔离验证

```python
# Python 网络测试代码
import socket
try:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect(("8.8.8.8", 53))
    print("ERROR: Network should be disabled!")
except Exception as e:
    print("OK: Network is disabled:", e)
```

预期输出：`OK: Network is disabled: [Errno 101] Network is unreachable`

---

## 9. 文件系统安全

### 9.1 只读根文件系统

```java
HostConfig.newHostConfig()
    .withReadonlyRootfs(true)
    .withTmpFs(Map.of(
        "/tmp", "rw,noexec,nosuid,nodev,size=64m",
        "/workspace", "rw,noexec,nosuid,nodev,size=128m"
    ))
```

### 9.2 挂载选项说明

| 选项 | 作用 |
|------|------|
| `rw` | 可读写 |
| `noexec` | 禁止执行二进制文件 |
| `nosuid` | 忽略 setuid 位 |
| `nodev` | 禁止设备文件 |
| `size=64m` | 限制大小 64MB |

### 9.3 路径黑名单

```java
/**
 * 检查路径是否安全
 */
public class PathValidator {
    
    private static final List<String> FORBIDDEN_PATHS = List.of(
        "/etc/passwd",
        "/etc/shadow",
        "/etc/hosts",
        "/proc",
        "/sys",
        "/dev",
        "/root",
        "/home",
        "/var/run/docker.sock"
    );
    
    public boolean isPathSafe(String path) {
        String normalized = normalizePath(path);
        return FORBIDDEN_PATHS.stream()
                .noneMatch(normalized::startsWith);
    }
    
    private String normalizePath(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return path;
        }
    }
}
```

---

## 10. 安全审计日志

### 10.1 审计事件记录

```java
package com.github.ezzziy.codesandbox.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityAuditLogger {

    /**
     * 记录执行请求
     */
    public void logExecutionRequest(String executionId, String language, 
                                    String clientIp, int codeLength) {
        log.info("[AUDIT] EXEC_REQUEST executionId={} language={} clientIp={} codeLength={}",
                executionId, language, clientIp, codeLength);
    }

    /**
     * 记录安全违规
     */
    public void logSecurityViolation(String executionId, String violationType, 
                                     String details) {
        log.warn("[AUDIT] SECURITY_VIOLATION executionId={} type={} details={}",
                executionId, violationType, details);
    }

    /**
     * 记录资源超限
     */
    public void logResourceExceeded(String executionId, String resourceType,
                                    long used, long limit) {
        log.warn("[AUDIT] RESOURCE_EXCEEDED executionId={} resource={} used={} limit={}",
                executionId, resourceType, used, limit);
    }

    /**
     * 记录执行完成
     */
    public void logExecutionCompleted(String executionId, String status, 
                                      long duration) {
        log.info("[AUDIT] EXEC_COMPLETED executionId={} status={} duration={}ms",
                executionId, status, duration);
    }
}
```

### 10.2 日志格式示例

```
2024-01-15 10:30:15.123 INFO  [AUDIT] EXEC_REQUEST executionId=exec-abc123 language=cpp clientIp=192.168.1.100 codeLength=512
2024-01-15 10:30:15.456 WARN  [AUDIT] SECURITY_VIOLATION executionId=exec-abc123 type=DANGEROUS_CODE details=检测到 system() 函数调用
2024-01-15 10:30:20.789 WARN  [AUDIT] RESOURCE_EXCEEDED executionId=exec-def456 resource=MEMORY used=274877906944 limit=268435456
2024-01-15 10:30:25.012 INFO  [AUDIT] EXEC_COMPLETED executionId=exec-ghi789 status=ACCEPTED duration=1234ms
```

---

## 11. 安全检查清单

### 11.1 部署前检查

- [ ] Docker 版本 >= 20.10
- [ ] 已启用 Docker seccomp
- [ ] 已配置资源限制
- [ ] 已禁用 Docker remote API 或已加密
- [ ] 宿主机内核版本支持 cgroups v2
- [ ] 已创建非 root 运行用户

### 11.2 配置检查

- [ ] `--network=none` 已配置
- [ ] `--read-only` 已配置
- [ ] `--cap-drop=ALL` 已配置
- [ ] `--pids-limit` 已配置
- [ ] `--memory` 和 `--memory-swap` 已配置
- [ ] `--security-opt=no-new-privileges` 已配置

### 11.3 运行时检查

- [ ] 危险代码扫描已启用
- [ ] 审计日志已启用
- [ ] 超时机制已实现
- [ ] 清理机制已实现
- [ ] 错误处理已完善

---

## 12. 下一步

下一篇文档将详细介绍 **缓存与 OSS 集成**，包括：
- 本地文件缓存设计
- OSS 客户端实现
- 缓存刷新机制
- 性能优化

详见 [06-CACHE-OSS.md](06-CACHE-OSS.md)
