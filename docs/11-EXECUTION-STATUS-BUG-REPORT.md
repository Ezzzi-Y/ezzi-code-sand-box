# 代码执行状态返回逻辑漏洞排查报告

> 状态：**✅ 所有 BUG 均已修复**

## 概述

对代码沙箱执行流程进行了全链路审计，发现 **3 个严重 BUG**（导致编译错误和运行时错误完全无法正确返回）和若干中/低级问题。所有严重 BUG 和问题-4 均已在代码中修复，详见下方“修复记录”章节。

核心问题出在 `DockerCodeExecutor` 的 shell 命令包装逻辑上：为了获取精确的执行时间和内存使用量，在用户命令外包了一层 shell 脚本，但这层包装 **破坏了退出码传递** 和 **吞掉了输出**。

---

## 执行流程总览

```
Controller (executeSingle/executeBatch)
  └─ ExecutionServiceImpl
       └─ DockerCodeExecutor.execute()
            ├─ 危险代码扫描 → DangerousCodeException
            ├─ 编译阶段 → compileInTaskDir() / compile()
            │    └─ executeCommandInDir() / executeCommand()   ← BUG 所在
            │         └─ shell 包装命令（计时 + 内存监测）
            │              └─ 退出码判断 → CompileException
            └─ 运行阶段 → runCodeInTaskDir() / runCode()
                 └─ executeCommandInDir() / executeCommand()   ← BUG 所在
                      └─ shell 包装命令（计时 + 内存监测）
                           └─ 退出码判断 → ExecutionResult
```

---

## 严重 BUG

### BUG-1：Shell 退出码永远为 0 —— 编译错误和运行时错误全部丢失

**严重程度：** ★★★★★ 致命  
**影响范围：** 所有编译型语言的编译检测、所有语言的运行时错误检测  
**涉及文件：** `DockerCodeExecutor.java` 的 `executeCommand()` 和 `executeCommandInDir()` 方法

#### 问题分析

两个方法中，用户命令被包装在如下 shell 脚本中执行：

**有输入路径（input path）：**
```bash
START=$(date +%s%N);
MEMFILE=/tmp/mem_$$;
printf '%s' '<input>' | /usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <用户命令>';
END=$(date +%s%N);
echo '__EXEC_TIME_NS__:'$((END-START));          # ← 这里
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE;
echo '__EXEC_MEM_KB__:'$MEM                       # ← 这里（最后一条命令）
```

**无输入路径（no-input path）：**
```bash
START=$(date +%s%N);
MEM=$(/usr/bin/time -f '%M' sh -c 'exec <用户命令>' 2>&1 | tail -1);
END=$(date +%s%N);
echo '__EXEC_TIME_NS__:'$((END-START));
echo '__EXEC_MEM_KB__:'$MEM                       # ← 最后一条命令
```

整段脚本通过 `sh -c "..."` 执行，Docker exec inspect 获取到的退出码是 **最后一条命令** 的退出码，即 `echo` 命令 —— **永远为 0**。

#### 影响

```java
// DockerCodeExecutor.java 第 417 行 / 第 613 行
return exitCode == 0
    ? CommandResult.success(cleanedOutput, stderrStr, executionTime, memoryUsage)  // ← 永远走这个分支
    : CommandResult.failure(exitCode, cleanedOutput, stderrStr, executionTime, memoryUsage);
```

- **编译阶段**：`compileResult.isSuccess()` 永远为 `true`，`CompileException` **永远不会被抛出**
- **运行阶段**：`result.isSuccess()` 永远为 `true`，`RUNTIME_ERROR` 状态 **永远不会被返回**
- 所有执行结果均为 `SUCCESS`，即使代码有编译错误或运行时崩溃

#### 修复建议

在用户命令执行后立即捕获退出码，并在脚本末尾使用 `exit` 传递：

```bash
# 有输入路径修复
START=$(date +%s%N);
MEMFILE=/tmp/mem_$$;
printf '%s' '<input>' | /usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <cmd>';
EXIT_CODE=$?;
END=$(date +%s%N);
echo '__EXEC_TIME_NS__:'$((END-START));
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE;
echo '__EXEC_MEM_KB__:'$MEM;
exit $EXIT_CODE
```

```bash
# 无输入路径修复（需同时解决 BUG-2）
START=$(date +%s%N);
MEMFILE=/tmp/mem_$$;
/usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <cmd>';
EXIT_CODE=$?;
END=$(date +%s%N);
echo '__EXEC_TIME_NS__:'$((END-START));
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE;
echo '__EXEC_MEM_KB__:'$MEM;
exit $EXIT_CODE
```

---

### BUG-2：无输入路径（no-input path）吞掉全部 stdout 和 stderr

**严重程度：** ★★★★★ 致命  
**影响范围：** 编译阶段（始终走无输入路径）、运行阶段（无输入时）  
**涉及文件：** `DockerCodeExecutor.java` 的 `executeCommand()` 和 `executeCommandInDir()`

#### 问题分析

无输入路径的命令：
```bash
MEM=$(/usr/bin/time -f '%M' sh -c 'exec <用户命令>' 2>&1 | tail -1);
```

逐步分析：
1. `sh -c 'exec <用户命令>'` 执行用户命令
2. `/usr/bin/time -f '%M'` 将内存值写到 stderr
3. `2>&1` 将 stderr 重定向到 stdout（内存值和用户程序的错误信息混在一起）
4. `| tail -1` 只保留最后一行（期望是内存值）
5. `$()` 捕获结果赋值给 `MEM` 变量

**结果：**
- 用户程序的 **stdout** → 进入 `$()` 子 shell → 被 `tail -1` 丢弃 → **丢失**
- 用户程序的 **stderr** → 被 `2>&1` 重定向 → 进入 `$()` 子 shell → 被 `tail -1` 丢弃 → **丢失**
- Docker 层面的 stderr 输出流 → **为空**（因为 `2>&1` 已在 shell 内部把 stderr 转走了）

#### 对编译阶段的影响

编译命令（如 `gcc ...`）始终以 `input=null` 调用，走的就是无输入路径：

```java
// compileInTaskDir() 和 compile() 均传 null
return executeCommandInDir(containerId, compileCmd, null, strategy.getCompileTimeout() * 1000L, taskDir);
return executeCommand(containerId, compileCmd, null, strategy.getCompileTimeout() * 1000L);
```

因此：
- `compileResult.getStdout()` = `""` （空字符串，编译器输出全部丢失）
- `compileResult.getStderr()` = `""` （空字符串，编译错误信息全部丢失）
- 即使 BUG-1 修复了，`CompileException(compileResult.getStderr())` 抛出的异常消息也是空的

#### 对运行阶段的影响

当 `input` 为 `null` 或空字符串时走此路径：

```java
// executeSingle 中
String input = request.getInput() == null ? "" : request.getInput();
// "" -> isEmpty() = true -> 走无输入路径
```

- 单次执行无输入的情况下，用户程序的所有输出丢失，始终返回空字符串
- 所有测试用例输入为空的情况同理

#### 修复建议

无输入路径也应使用 `-o $MEMFILE` 把内存值写到临时文件，避免输出流被污染：

```bash
START=$(date +%s%N);
MEMFILE=/tmp/mem_$$;
/usr/bin/time -f '%M' -o $MEMFILE sh -c 'exec <cmd>';
EXIT_CODE=$?;
END=$(date +%s%N);
echo '__EXEC_TIME_NS__:'$((END-START));
MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE;
echo '__EXEC_MEM_KB__:'$MEM;
exit $EXIT_CODE
```

这样用户程序的 stdout 直接输出到 Docker stdout，stderr 输出到 Docker stderr，不会被吞掉。

---

### BUG-3：无输入路径错误时内存值可能是垃圾数据

**严重程度：** ★★★☆☆ 中等  
**影响范围：** 编译失败或运行异常时的内存报告  
**涉及文件：** `DockerCodeExecutor.java`

#### 问题分析

```bash
MEM=$(/usr/bin/time -f '%M' sh -c 'exec <cmd>' 2>&1 | tail -1);
```

`/usr/bin/time -f '%M'` 的输出在 stderr 最后一行。但如果用户命令产生了 stderr 输出，顺序为：
1. 用户命令的 stdout
2. 用户命令的 stderr（通过 `2>&1` 合入）
3. `/usr/bin/time` 的输出（也在 stderr，最后写入）

正常情况下 `tail -1` 确实能获取到 `/usr/bin/time` 的值。但在某些边界情况（如命令被信号杀死、time 未正常执行完），`tail -1` 可能拿到的是用户程序的错误输出，导致 `MEM` 变量包含非数字内容。

后续解析时：
```java
memoryUsage = Long.parseLong(memStr);  // 可能抛 NumberFormatException，回退到 0
```

虽然有 catch 兜底不会崩溃，但内存值会不准确。

---

## 中等问题

### 问题-4：编译错误/危险代码响应缺少 totalTime 字段

**涉及文件：** `ExecutionServiceImpl.java`

```java
// executeSingle - CompileException 分支
catch (CompileException e) {
    return SingleExecuteResponse.builder()
        .status(ExecutionStatus.COMPILE_ERROR)
        .compileOutput(e.getMessage())
        .build();   // ← 缺少 .totalTime(System.currentTimeMillis() - startTime)
}
```

```java
// executeBatch - CompileException 分支
catch (CompileException e) {
    return BatchExecuteResponse.builder()
        .status(ExecutionStatus.COMPILE_ERROR)
        .compileOutput(e.getMessage())
        .build();   // ← 缺少 totalTime, results, summary
}
```

同样的问题也存在于 `DangerousCodeException` 和 `IllegalArgumentException` 的 catch 分支中。

**影响：** 客户端拿到的 `totalTime` 为 null，无法统计编译错误的请求耗时。

---

### 问题-5：全局异常处理器返回类型与 Controller 不一致

**涉及文件：** `GlobalExceptionHandler.java`

```java
@ExceptionHandler(SandboxException.class)
public ResponseEntity<ExecuteResponse> handleSandboxException(SandboxException e) {
    // 返回 ExecuteResponse
}
```

而 Controller 端点返回的是 `Result<SingleExecuteResponse>` 或 `Result<BatchExecuteResponse>`：

```java
@PostMapping("/single")
public Result<SingleExecuteResponse> executeSingle(...) { ... }

@PostMapping("/batch")
public Result<BatchExecuteResponse> executeBatch(...) { ... }
```

如果某些异常逃逸到全局异常处理器（目前 Service 层已 catch，正常情况不会发生），响应格式会从 `{code, message, data}` 变为 `{status, compileOutput, errorMessage, ...}`，造成客户端解析失败。

---

### 问题-6：runCodeInTaskDir / runCode 使用 Java 层面时间而非容器精确时间

**涉及文件：** `DockerCodeExecutor.java`

```java
private ExecutionResult runCodeInTaskDir(...) {
    long startTime = System.currentTimeMillis();
    CommandResult result = executeCommandInDir(...);
    long executionTime = System.currentTimeMillis() - startTime;  // Java 层面时间

    // ...
    // result.getExecutionTime() 是容器内纳秒级精确时间，但未被使用
    return ExecutionResult.success(index, ..., executionTime, result.getMemoryUsage());
    //                                         ^^^^^^^^^^^^^ 用了 Java 时间
}
```

`executeCommandInDir` 已经从容器输出中解析了纳秒级精确时间存储在 `result.getExecutionTime()` 中，但 `runCodeInTaskDir` 和 `runCode` 都忽略了这个值，改用 Java 层面的 `System.currentTimeMillis()` 差值。

Java 层面时间包含了 Docker API 通信开销、命令序列化/反序列化等额外耗时，对于有严格时间限制的判题场景可能导致时间偏大。

---

## 问题影响矩阵

| 场景 | 退出码 | stdout | stderr | 状态返回 | 实际表现 |
|------|--------|--------|--------|----------|----------|
| **编译成功** (C/C++/Java) | ~~正确~~ 始终0 | 丢失(无输入路径) | 丢失 | ~~SUCCESS~~ 始终SUCCESS | 表面正常但编译输出丢失 |
| **编译失败** (C/C++/Java) | ~~非0~~ 始终0 | 丢失 | 丢失 | ~~COMPILE_ERROR~~ **错误地返回SUCCESS** | **致命：编译错误被静默忽略，直接进入运行阶段（运行必然失败）** |
| **运行成功+有输入** | ~~正确~~ 始终0 | ✅ 正确 | ✅ 正确 | SUCCESS | 正常 |
| **运行成功+无输入** | ~~正确~~ 始终0 | **丢失** | **丢失** | SUCCESS | 状态正确但输出为空 |
| **运行时错误+有输入** | ~~非0~~ 始终0 | ✅ 正确 | ✅ 正确 | ~~RUNTIME_ERROR~~ **错误地返回SUCCESS** | **致命：段错误/异常全部显示为"成功"** |
| **运行时错误+无输入** | ~~非0~~ 始终0 | 丢失 | 丢失 | ~~RUNTIME_ERROR~~ **错误地返回SUCCESS** | **致命：错误被完全掩盖** |
| **超时** | N/A | N/A | N/A | TIME_LIMIT_EXCEEDED | ✅ 正确（Java层面超时检测，不依赖退出码） |
| **危险代码** | N/A | N/A | N/A | DANGEROUS_CODE | ✅ 正确（在执行前抛异常） |
| **Python 运行成功+有输入** | ~~正确~~ 始终0 | ✅ 正确 | ✅ 正确 | SUCCESS | 正常（Python不需编译） |
| **Python 运行错误+有输入** | ~~非0~~ 始终0 | ✅ 正确 | ✅ 正确 | ~~RUNTIME_ERROR~~ **错误地返回SUCCESS** | **致命** |

---

## 编译失败后的实际行为链路

以 C 语言编译错误为例，当前代码的实际执行路径：

```
1. gcc 编译失败，退出码=1
2. shell 包装脚本继续执行 echo 命令
3. shell 最终退出码=0（来自最后的 echo）
4. Docker inspect exitCode=0
5. CommandResult.success("", "", time, mem) 被返回
6. compileResult.isSuccess() == true
7. 跳过 CompileException 抛出
8. compileOutput = buildCompileOutput(compileResult) = null（stdout和stderr都为空）
9. 进入"运行测试用例"阶段
10. 运行 ./main（不存在，因为编译没成功）
11. sh -c 'exec ./main' 失败，但退出码再次被包装成 0
12. CommandResult.success() 被返回
13. ExecutionResult.success() 被返回
14. 最终：status=SUCCESS, output="", errorOutput=null
```

**用户看到的结果：** 编译错误的代码返回"执行成功"，输出为空，无任何错误提示。

---

## 修复记录

所有修复已完成。以下是每处改动的详细说明。

---

### 修复 1：`executeCommand()` shell 脚本 — 退出码丢失 + 无输入路径输出丢失

**文件：** `src/main/java/com/github/ezzziy/codesandbox/executor/DockerCodeExecutor.java`  
**方法：** `executeCommand()`（传统模式使用）  
**对应 BUG：** BUG-1（退出码永远为 0）、BUG-2（无输入路径吞掉 stdout/stderr）、BUG-3（内存值不准）

#### 有输入路径（input path）

**修改前：**
```java
timedCmd = String.format(
        "START=$(date +%%s%%N); " +
        "MEMFILE=/tmp/mem_$$; " +
        "printf '%%s' '%s' | /usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
        "echo '%s'$MEM",
        escapedInput, originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**修改后：**
```java
timedCmd = String.format(
        "MEMFILE=/tmp/mem_$$; " +
        "START=$(date +%%s%%N); " +
        "printf '%%s' '%s' | /usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
        "EXIT_CODE=$?; " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
        "echo '%s'$MEM; " +
        "exit $EXIT_CODE",
        escapedInput, originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**改动说明：**
- 在用户命令执行后立即 `EXIT_CODE=$?` 捕获真实退出码
- 在脚本末尾 `exit $EXIT_CODE` 将退出码传递给 Docker inspect
- 调整 `MEMFILE` 声明顺序（先于 START）

#### 无输入路径（no-input path）

**修改前：**
```java
timedCmd = String.format(
        "START=$(date +%%s%%N); " +
        "MEM=$(/usr/bin/time -f '%%M' sh -c 'exec %s' 2>&1 | tail -1); " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "echo '%s'$MEM",
        originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**修改后：**
```java
timedCmd = String.format(
        "MEMFILE=/tmp/mem_$$; " +
        "START=$(date +%%s%%N); " +
        "/usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
        "EXIT_CODE=$?; " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
        "echo '%s'$MEM; " +
        "exit $EXIT_CODE",
        originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**改动说明：**
- 移除 `$()` 子 shell 包裹，改用 `-o $MEMFILE` 将内存值写入临时文件
- 移除 `2>&1 | tail -1`，不再将 stderr 重定向到 stdout
- 用户程序的 stdout 直接输出到 Docker stdout，stderr 直接输出到 Docker stderr
- 在用户命令执行后 `EXIT_CODE=$?` 捕获退出码，末尾 `exit $EXIT_CODE` 传递

---

### 修复 2：`executeCommandInDir()` shell 脚本 — 退出码丢失 + 无输入路径输出丢失

**文件：** `src/main/java/com/github/ezzziy/codesandbox/executor/DockerCodeExecutor.java`  
**方法：** `executeCommandInDir()`（容器池模式使用）  
**对应 BUG：** BUG-1、BUG-2、BUG-3（与修复 1 相同的问题在容器池路径中的镜像）

#### 有输入路径

**修改前：**
```java
timedCmd = String.format(
        "%sSTART=$(date +%%s%%N); " +
        "MEMFILE=/tmp/mem_$$; " +
        "printf '%%s' '%s' | /usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
        "echo '%s'$MEM",
        cdPrefix, escapedInput, originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**修改后：**
```java
timedCmd = String.format(
        "%sMEMFILE=/tmp/mem_$$; " +
        "START=$(date +%%s%%N); " +
        "printf '%%s' '%s' | /usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
        "EXIT_CODE=$?; " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
        "echo '%s'$MEM; " +
        "exit $EXIT_CODE",
        cdPrefix, escapedInput, originalCmd, TIME_MARKER, MEMORY_MARKER);
```

#### 无输入路径

**修改前：**
```java
timedCmd = String.format(
        "%sSTART=$(date +%%s%%N); " +
        "MEM=$(/usr/bin/time -f '%%M' sh -c 'exec %s' 2>&1 | tail -1); " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "echo '%s'$MEM",
        cdPrefix, originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**修改后：**
```java
timedCmd = String.format(
        "%sMEMFILE=/tmp/mem_$$; " +
        "START=$(date +%%s%%N); " +
        "/usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; " +
        "EXIT_CODE=$?; " +
        "END=$(date +%%s%%N); " +
        "echo '%s'$((END-START)); " +
        "MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; " +
        "echo '%s'$MEM; " +
        "exit $EXIT_CODE",
        cdPrefix, originalCmd, TIME_MARKER, MEMORY_MARKER);
```

**改动说明：** 与修复 1 完全一致的修复策略，应用于容器池模式的 `executeCommandInDir` 方法。

---

### 修复 3：`runCodeInTaskDir()` 和 `runCode()` — 使用容器精确时间替代 Java 层面时间

**文件：** `src/main/java/com/github/ezzziy/codesandbox/executor/DockerCodeExecutor.java`  
**方法：** `runCodeInTaskDir()`（容器池模式）、`runCode()`（传统模式）  
**对应问题：** 问题-6（时间精度问题）

**修改前（两个方法结构相同）：**
```java
long startTime = System.currentTimeMillis();

CommandResult result = executeCommand/executeCommandInDir(...);

long executionTime = System.currentTimeMillis() - startTime;  // Java 层面时间

// 后续使用 executionTime 构建 ExecutionResult
```

**修改后：**
```java
CommandResult result = executeCommand/executeCommandInDir(...);

// 优先使用容器内纳秒级精确时间，避免 Docker API 通信开销干扰
long executionTime = result.getExecutionTime();

// 后续使用 executionTime 构建 ExecutionResult
```

**改动说明：**
- 移除 `System.currentTimeMillis()` 的 start/end 差值计算
- 直接使用 `result.getExecutionTime()`，该值从容器内 `date +%s%N` 纳秒时间戳计算而来
- 排除了 Docker API 调用、网络通信、exec 创建等额外开销
- 对于超时场景，`CommandResult.timeout()` 已设置 `executionTime = timeLimit`，不受影响

---

### 修复 4：`ExecutionServiceImpl` — 异常分支补充 `totalTime` 字段

**文件：** `src/main/java/com/github/ezzziy/codesandbox/service/impl/ExecutionServiceImpl.java`  
**方法：** `executeSingle()`、`executeBatch()`  
**对应问题：** 问题-4（编译错误/危险代码响应缺少 totalTime 字段）

#### executeSingle() — 4 个 catch 分支

**修改前**（以 CompileException 为例，其他分支同理）：
```java
catch (CompileException e) {
    log.warn("编译错误: requestId={}, error={}", requestId, e.getMessage());
    return SingleExecuteResponse.builder()
        .status(ExecutionStatus.COMPILE_ERROR)
        .compileOutput(e.getMessage())
        .build();                                  // ← 缺少 totalTime
}
```

**修改后：**
```java
catch (CompileException e) {
    long totalTime = System.currentTimeMillis() - startTime;
    log.warn("编译错误: requestId={}, error={}", requestId, e.getMessage());
    return SingleExecuteResponse.builder()
        .status(ExecutionStatus.COMPILE_ERROR)
        .compileOutput(e.getMessage())
        .totalTime(totalTime)                      // ← 新增
        .build();
}
```

同样的修改应用于 `DangerousCodeException`、`IllegalArgumentException`、`Exception` 共 4 个 catch 分支。

#### executeBatch() — 3 个 catch 分支

**修改前**（以 CompileException 为例）：
```java
catch (CompileException e) {
    return BatchExecuteResponse.builder()
        .status(ExecutionStatus.COMPILE_ERROR)
        .compileOutput(e.getMessage())
        .build();                                  // ← 缺少 totalTime
}
```

**修改后：**
```java
catch (CompileException e) {
    return BatchExecuteResponse.builder()
        .status(ExecutionStatus.COMPILE_ERROR)
        .compileOutput(e.getMessage())
        .totalTime(System.currentTimeMillis() - startTime)  // ← 新增
        .build();
}
```

同样的修改应用于 `DangerousCodeException`、`Exception` 共 3 个 catch 分支。

---

## 修复后的执行状态矩阵

| 场景 | 退出码 | stdout | stderr | 状态返回 |
|------|--------|--------|--------|----------|
| **编译成功** (C/C++/Java) | ✅ 0 | ✅ 正确 | ✅ 正确 | ✅ SUCCESS |
| **编译失败** (C/C++/Java) | ✅ 非0 | ✅ 正确 | ✅ 正确（编译错误信息） | ✅ COMPILE_ERROR |
| **运行成功+有输入** | ✅ 0 | ✅ 正确 | ✅ 正确 | ✅ SUCCESS |
| **运行成功+无输入** | ✅ 0 | ✅ 正确 | ✅ 正确 | ✅ SUCCESS |
| **运行时错误+有输入** | ✅ 非0 | ✅ 正确 | ✅ 正确 | ✅ RUNTIME_ERROR |
| **运行时错误+无输入** | ✅ 非0 | ✅ 正确 | ✅ 正确 | ✅ RUNTIME_ERROR |
| **超时** | N/A | N/A | N/A | ✅ TIME_LIMIT_EXCEEDED |
| **危险代码** | N/A | N/A | N/A | ✅ DANGEROUS_CODE |

---

## 未修复的遗留问题

### 问题-5：全局异常处理器返回类型与 Controller 不一致

**文件：** `GlobalExceptionHandler.java`

`handleSandboxException` 返回 `ResponseEntity<ExecuteResponse>`，而 Controller 端返回 `Result<SingleExecuteResponse>` / `Result<BatchExecuteResponse>`。

**现状：** 因为 Service 层已经 catch 了所有 `SandboxException`（CompileException、DangerousCodeException），异常不会逃逸到全局处理器。此 handler 实际上是死代码。

**建议：** 可以在后续清理中移除或统一返回类型，不影响当前功能。
