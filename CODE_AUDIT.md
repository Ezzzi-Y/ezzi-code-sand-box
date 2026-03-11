# Code Quality Audit Report

**Project:** ezzi-code-sand-box  
**Date:** 2026-03-11  
**Auditor Role:** Principal Software Engineer  
**Scope:** Full codebase review — architecture, design, code smells, security, and refactoring recommendations

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [What the Code Does](#2-what-the-code-does)
3. [Module & Class Inventory](#3-module--class-inventory)
4. [Architecture Evaluation](#4-architecture-evaluation)
5. [Code Smell Analysis](#5-code-smell-analysis)
6. [Detailed Findings](#6-detailed-findings)
7. [Refactoring Suggestions](#7-refactoring-suggestions)
8. [Example Improved Structure](#8-example-improved-structure)
9. [Summary & Prioritized Action Items](#9-summary--prioritized-action-items)

---

## 1. Executive Summary

The **ezzi-code-sand-box** is a Docker-based remote code execution sandbox built with Spring Boot 3.5.7 and Java 21. It supports five programming languages (C, C++11, Java 8, Java 17, Python 3) and provides REST APIs for single and batch code execution. The project demonstrates solid domain understanding with good security practices (network isolation, capability dropping, dangerous-code scanning, sandboxed user execution).

**Overall Architecture Rating: B+** — The layered design (Controller → Service → Executor) is sound. The Strategy pattern for language support is well applied. However, several code smells — most notably **Duplicate Code** in `DockerCodeExecutor` and **Long Function** patterns — reduce maintainability and increase the risk of bugs propagating during future changes.

### Key Metrics

| Metric | Value |
|---|---|
| Total Java source files | ~35 production + 1 test |
| Largest file | `DockerCodeExecutor.java` (~930 lines) |
| Languages supported | 5 (C, C++11, Java 8, Java 17, Python 3) |
| REST endpoints | 7 |
| Test coverage | Minimal (1 context-load test) |

---

## 2. What the Code Does

The application is an **online judge / code execution engine** that:

1. **Accepts code** via REST API (`/execute/single`, `/execute/batch`) with a specified language and input data.
2. **Scans for dangerous code patterns** using regex-based rules defined per language (Strategy pattern).
3. **Acquires a Docker container** — either from a hot container pool (performance mode) or by creating a new one (traditional mode).
4. **Writes source code** into the container via Docker's TAR copy API.
5. **Compiles** the code (for compiled languages) inside the container.
6. **Runs the code** against one or more test case inputs, capturing stdout/stderr with precise nanosecond-level timing and memory measurement.
7. **Returns structured results** including execution status, output, time, and memory usage.
8. **Cleans up** task directories and releases containers back to the pool.

### Execution Flow

```
HTTP Request → ExecuteController → ExecutionServiceImpl → DockerCodeExecutor
                                                              ├── LanguageStrategyFactory → LanguageStrategy (scan, compile/run commands)
                                                              ├── ContainerPool → PooledContainer (acquire/release)
                                                              ├── ContainerManager (create, start, stop, remove)
                                                              └── Docker API (exec, copy, inspect)
```

---

## 3. Module & Class Inventory

### 3.1 Layers & Responsibilities

| Layer | Package | Classes | Responsibility |
|---|---|---|---|
| **API** | `controller` | `ExecuteController`, `HealthController` | REST endpoint definitions, request validation |
| **Service** | `service` / `service.impl` | `ExecutionService(Impl)`, `HealthService(Impl)`, `InputDataService(Impl)` | Business orchestration, input resolution, health checks |
| **Executor** | `executor` | `DockerCodeExecutor`, `ContainerManager`, `CommandResult` | Core Docker command execution, container lifecycle |
| **Pool** | `pool` | `ContainerPool`, `PooledContainer` | Hot container pool management with locking |
| **Strategy** | `strategy` | `LanguageStrategy` (interface), `LanguageStrategyFactory`, 5 concrete strategies | Language-specific compilation/execution/security rules |
| **Model** | `model.dto` / `model.vo` | DTOs and View Objects (8 classes) | Data transfer and response shapes |
| **Config** | `config` | `DockerConfig`, `ExecutionConfig` | Spring configuration beans |
| **Exception** | `exception` | `SandboxException` + 5 subclasses, `GlobalExceptionHandler` | Error hierarchy and centralized handling |
| **Common** | `common.enums` / `common.result` | `ExecutionStatus`, `LanguageEnum`, `Result` | Shared enums and response wrapper |
| **Util** | `util` | `OssUrlParser` | URL parsing utility |

### 3.2 Key Classes by Size (Approximate LOC)

| Class | LOC | Concern |
|---|---|---|
| `DockerCodeExecutor` | ~930 | ⚠️ Largest — execution orchestration, shell command building, output parsing, file I/O |
| `ContainerPool` | ~460 | Container pool lifecycle, locking, scheduled cleanup |
| `InputDataServiceImpl` | ~441 | ZIP download, caching, version comparison |
| `ContainerManager` | ~373 | Docker container CRUD operations |
| `ExecutionServiceImpl` | ~260 | Business logic orchestration |
| Language strategies | ~100–125 each | Language-specific commands and patterns |

---

## 4. Architecture Evaluation

### 4.1 Strengths

| Area | Assessment |
|---|---|
| **Layered architecture** | ✅ Clear Controller → Service → Executor layering with interface-based service contracts |
| **Strategy pattern** | ✅ Well-applied for language support; new languages can be added by implementing `LanguageStrategy` |
| **Container pool** | ✅ Solid implementation with proper locking, scheduled cleanup, and zombie detection |
| **Security** | ✅ Multi-layered: network disabled, capabilities dropped, sandboxed user, code scanning, resource limits |
| **Configuration** | ✅ Externalized via `ExecutionConfig` with sensible defaults |
| **Exception hierarchy** | ✅ Clean domain-specific exception tree rooted at `SandboxException` |
| **Docker integration** | ✅ Proper use of docker-java API with TAR-based file copy and exec-based command execution |

### 4.2 Weaknesses

| Area | Assessment |
|---|---|
| **`DockerCodeExecutor` size** | ⚠️ 930 lines — God Object tendency; mixes orchestration, shell-building, output parsing, file I/O |
| **Duplicate code** | ⚠️ `executeCommand()` and `executeCommandInDir()` are ~90% identical (~160 lines each) |
| **Duplicate result mapping** | ⚠️ `runCode()` and `runCodeInTaskDir()` have nearly identical result-mapping logic |
| **Test coverage** | ❌ Only 1 context-load test; no unit tests for strategies, executor, pool, or services |
| **Error handling** | ⚠️ Extensive use of `RuntimeException` instead of domain-specific exceptions in several places |
| **Inconsistent formatting** | ⚠️ `ExecutionServiceImpl` has inconsistent indentation (mix of 4-space and 16-space blocks) |
| **Unused dependencies** | ⚠️ `pom.xml` includes JJWT, POI, MyBatis Plus, OKHttp — not referenced in source code |
| **Hardcoded values** | ⚠️ Some magic numbers/strings scattered (e.g., retry counts, sleep durations, path prefixes) |

### 4.3 Separation of Concerns

| Concern | Current Location | Assessment |
|---|---|---|
| Shell command construction | `DockerCodeExecutor` | ⚠️ Should be extracted to a dedicated builder |
| Output/time/memory parsing | `DockerCodeExecutor` | ⚠️ Should be extracted to a parser class |
| File I/O (TAR creation, file writes) | `DockerCodeExecutor` | ⚠️ Could be separated into a utility |
| Result mapping (CommandResult → ExecutionResult) | `DockerCodeExecutor` | ⚠️ Duplicated between traditional and pool modes |
| Container lifecycle | `ContainerManager` | ✅ Well-encapsulated |
| Pool management | `ContainerPool` | ✅ Well-encapsulated |

### 4.4 Dependency Management

- **Unused dependencies in `pom.xml`**: `jjwt-api`, `jjwt-impl`, `jjwt-jackson`, `poi`, `poi-ooxml`, `mybatis-plus-spring-boot3-starter`, `okhttp` — none of these are imported in any source file. This adds unnecessary build time and attack surface.
- **Hutool** is used only in `InputDataServiceImpl` for HTTP requests (`cn.hutool.http`). Consider whether this justifies the entire library as a dependency.

---

## 5. Code Smell Analysis

### 5.1 Code Smell Summary

| # | Smell | Severity | Location | Description |
|---|---|---|---|---|
| 1 | **Long Function / God Object** | 🔴 High | `DockerCodeExecutor` (930 LOC) | Single class handles orchestration, shell construction, output parsing, file I/O, and result mapping |
| 2 | **Duplicate Code** | 🔴 High | `executeCommand()` vs `executeCommandInDir()` | ~160 lines duplicated with only a `cd` prefix difference |
| 3 | **Duplicate Code** | 🟠 Medium | `runCode()` vs `runCodeInTaskDir()` | Nearly identical result-mapping logic (~50 lines) |
| 4 | **Duplicate Code** | 🟠 Medium | `executeTraditional()` vs `executeWithPool()` | Same compile→run→collect pattern with minor variations |
| 5 | **Duplicate Code** | 🟡 Low | `executeSingle()` vs `executeBatch()` | Similar validation, strategy lookup, and exception handling |
| 6 | **Primitive Obsession** | 🟠 Medium | `DockerCodeExecutor.execute()` | 6 primitive parameters instead of a request/context object |
| 7 | **Primitive Obsession** | 🟠 Medium | `HealthServiceImpl` | Returns `Map<String, Object>` instead of typed response objects |
| 8 | **Data Clumps** | 🟡 Low | `(containerId, strategy, taskDir)` | Frequently passed together — could be encapsulated |
| 9 | **Feature Envy** | 🟡 Low | `DockerCodeExecutor` | Extensively manipulates `CommandResult` fields — parsing logic should belong to `CommandResult` |
| 10 | **Shotgun Surgery** | 🟡 Low | Adding a new execution mode | Would require changes in `DockerCodeExecutor` (2 places), `ContainerManager`, `ContainerPool` |
| 11 | **Inconsistent Formatting** | 🟡 Low | `ExecutionServiceImpl` | Mixed indentation in `executeBatch()` method (appears auto-formatted differently) |

### 5.2 Detailed Smell Analysis

#### 5.2.1 Duplicate Code — `executeCommand()` vs `executeCommandInDir()` (Critical)

These two methods in `DockerCodeExecutor` are ~160 lines each and are nearly identical. The only difference is that `executeCommandInDir()` prepends a `cd <workDir> &&` to the shell command. Both methods:

1. Build a timed shell command with `date +%s%N` and `/usr/bin/time`
2. Handle input escaping and piping
3. Create a Docker exec instance
4. Capture stdout/stderr
5. Wait for completion with timeout
6. Parse time markers and memory markers from output
7. Check output limits
8. Return `CommandResult`

**Risk:** Any bug fix or feature (e.g., changing the timing mechanism) must be applied in two places, and divergence is likely over time.

#### 5.2.2 Duplicate Code — `runCode()` vs `runCodeInTaskDir()` (Medium)

Both methods map `CommandResult` → `ExecutionResult` with the same logic chain: check timeout → check memory exceeded → check output exceeded → check exit code → return success. The only difference is how the executable path is constructed.

#### 5.2.3 Long Function / God Object — `DockerCodeExecutor` (Critical)

At ~930 lines, this class has too many responsibilities:
- **Orchestration**: `execute()`, `executeWithPool()`, `executeTraditional()`
- **Shell command construction**: Building complex shell scripts with timing/memory measurement
- **Output parsing**: Extracting time/memory markers from stdout
- **File I/O**: `writeSourceCodeToContainer()`, `writeSourceCode()`, `createWorkDirectory()`
- **Result mapping**: Converting `CommandResult` to `ExecutionResult`

#### 5.2.4 Primitive Obsession — execute() Parameters

```java
public ExecuteResult execute(LanguageStrategy strategy,
                             String code,
                             List<String> inputList,
                             String requestId,
                             int timeLimit,
                             int memoryLimit)
```

Six parameters (with more being threaded through to inner methods) indicate a missing **Execution Context** concept.

---

## 6. Detailed Findings

### 6.1 Encapsulation Issues

1. **`PooledContainer`** uses public setters (`setInUse()`, `setUseCount()`, `setLastUsedTime()`) that are called from `ContainerPool`. These mutations should ideally be encapsulated within `PooledContainer` methods (e.g., `markInUse()`, `markIdle()`, `incrementUseCount()`).

2. **`CommandResult`** is a passive data holder. The output-parsing logic (extracting time/memory markers) in `DockerCodeExecutor` is a form of **Feature Envy** — it would be more cohesive as a factory method on `CommandResult`.

### 6.2 Error Handling

1. **Generic RuntimeException usage**: `ContainerManager.createContainer()`, `InputDataServiceImpl`, and several places in `DockerCodeExecutor` throw `RuntimeException` with Chinese-language messages. These should use domain-specific exceptions (e.g., `ContainerLimitException`, `InputDataException`).

2. **Silent catch blocks**: `ContainerManager.stopContainer()` and `killContainer()` catch and log but may hide critical failures during cleanup.

### 6.3 Unused Dependencies (pom.xml)

The following dependencies are declared but not used anywhere in the source:

| Dependency | Group ID | Usage Found |
|---|---|---|
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | `io.jsonwebtoken` | ❌ None |
| `poi` / `poi-ooxml` | `org.apache.poi` | ❌ None |
| `mybatis-plus-spring-boot3-starter` | `com.baomidou` | ❌ None |
| `okhttp` | `com.squareup.okhttp3` | ❌ None |

These add ~30+ MB to the final artifact and increase the security surface area.

### 6.4 Configuration Hardcoding

| Hardcoded Value | Location | Suggestion |
|---|---|---|
| `"sandbox"` user | `ContainerManager`, `DockerCodeExecutor` | Extract to `ExecutionConfig` |
| `"/sandbox/workspace"` path | `ContainerManager`, `DockerCodeExecutor` | Extract to constant or config |
| Retry count `5`, sleep `200ms` | `ContainerPool.createAndAddContainer()` | Extract to config |
| `ACQUIRE_WAIT_SECONDS = 30` | `ContainerPool` | Already a constant ✅, could be config-driven |

### 6.5 Test Coverage

The project has only one test (`CodeSandBoxApplicationTests`) which performs a basic Spring context load. There are no unit tests for:

- Language strategies (dangerous pattern detection)
- `DockerCodeExecutor` (output parsing, shell escaping)
- `ContainerPool` (acquire/release/cleanup logic)
- `InputDataServiceImpl` (caching, ZIP extraction)
- `OssUrlParser` (URL parsing)
- `ExecutionServiceImpl` (request validation, error handling)

---

## 7. Refactoring Suggestions

### 7.1 Priority 1 (High Impact) — Eliminate Duplicate Code in DockerCodeExecutor

**Problem:** `executeCommand()` and `executeCommandInDir()` are ~90% identical.

**Solution:** Unify into a single method that accepts an optional `workDir` parameter.

```java
// Before: Two methods of ~160 lines each
private CommandResult executeCommand(String containerId, String[] cmd, String input, long timeoutMs) { ... }
private CommandResult executeCommandInDir(String containerId, String[] cmd, String input, long timeoutMs, String workDir) { ... }

// After: Single method with optional workDir
private CommandResult executeCommand(String containerId, String[] cmd, String input, long timeoutMs, String workDir) {
    String cdPrefix = (workDir != null) ? "cd " + workDir + " && " : "";
    // ... unified implementation ...
}
```

Similarly, unify `runCode()` and `runCodeInTaskDir()` by extracting the shared result-mapping logic.

### 7.2 Priority 2 (High Impact) — Extract Shell Command Builder

**Problem:** Complex shell command construction is inline within the execution methods.

**Solution:** Extract a `ShellCommandBuilder` utility class.

```java
public class ShellCommandBuilder {
    public static String buildTimedCommand(String command, String input, String workDir) {
        StringBuilder sb = new StringBuilder();
        if (workDir != null) {
            sb.append("cd ").append(workDir).append(" && ");
        }
        sb.append("MEMFILE=/tmp/mem_$$; ");
        sb.append("START=$(date +%s%N); ");
        if (input != null && !input.isEmpty()) {
            sb.append(String.format("printf '%%s' '%s' | ", escapeShellInput(input)));
        }
        sb.append(String.format("/usr/bin/time -f '%%M' -o $MEMFILE sh -c 'exec %s'; ", command));
        sb.append("EXIT_CODE=$?; END=$(date +%s%N); ");
        sb.append(String.format("echo '%s'$((END-START)); ", TIME_MARKER));
        sb.append("MEM=$(cat $MEMFILE 2>/dev/null || echo 0); rm -f $MEMFILE; ");
        sb.append(String.format("echo '%s'$MEM; ", MEMORY_MARKER));
        sb.append("exit $EXIT_CODE");
        return sb.toString();
    }
}
```

### 7.3 Priority 3 (Medium Impact) — Extract Output Parser

**Problem:** Time/memory marker parsing from stdout is interleaved with execution logic.

**Solution:** Create a `CommandOutputParser` that encapsulates this:

```java
public class CommandOutputParser {
    public record ParsedOutput(String cleanOutput, long executionTimeMs, long memoryUsageKB) {}

    public static ParsedOutput parse(String rawStdout, long fallbackTimeMs) {
        // Extract TIME_MARKER, MEMORY_MARKER, clean output
        // Return structured result
    }
}
```

### 7.4 Priority 4 (Medium Impact) — Introduce ExecutionContext

**Problem:** 6+ primitive parameters are threaded through multiple method calls.

**Solution:** Introduce a context object:

```java
public record ExecutionContext(
    LanguageStrategy strategy,
    String code,
    List<String> inputList,
    String requestId,
    int timeLimitMs,
    int memoryLimitMB
) {}
```

### 7.5 Priority 5 (Medium Impact) — Unify Result Mapping

**Problem:** `runCode()` and `runCodeInTaskDir()` duplicate the `CommandResult` → `ExecutionResult` mapping.

**Solution:** Extract a shared method:

```java
private ExecutionResult mapToExecutionResult(CommandResult result, int index, int timeLimit, int memoryLimit) {
    long executionTime = result.getExecutionTime();
    if (result.isTimeout()) return ExecutionResult.timeout(index, timeLimit);
    if (result.isMemoryExceeded()) return ExecutionResult.memoryExceeded(index, executionTime, result.getMemoryUsage());
    if (result.isOutputExceeded()) return ExecutionResult.runtimeError(index, normalizeOutput(result.getStdout()), "Output limit exceeded", executionTime, result.getMemoryUsage(), result.getExitCode());
    if (!result.isSuccess()) return ExecutionResult.runtimeError(index, normalizeOutput(result.getStdout()), result.getStderr(), executionTime, result.getMemoryUsage(), result.getExitCode());
    return ExecutionResult.success(index, normalizeOutput(result.getStdout()), result.getStderr(), executionTime, result.getMemoryUsage());
}
```

### 7.6 Priority 6 (Low Impact) — Remove Unused Dependencies

Remove from `pom.xml`:
- `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson`
- `org.apache.poi:poi`, `poi-ooxml`
- `com.baomidou:mybatis-plus-spring-boot3-starter`
- `com.squareup.okhttp3:okhttp`

### 7.7 Priority 7 (Low Impact) — Fix Indentation in ExecutionServiceImpl

The `executeBatch()` method has inconsistent indentation (mix of 4-space and 16-space). Apply consistent formatting.

### 7.8 Priority 8 (Low Impact) — Add Unit Tests

Priority test targets:
1. **Language strategy pattern detection** — unit test each strategy's `checkDangerousCode()`
2. **Shell input escaping** — `escapeShellInput()` with edge cases
3. **Output parsing** — time/memory marker extraction
4. **OssUrlParser** — URL parsing for different providers
5. **ContainerPool** — acquire/release logic (mockable with `ContainerManager`)

---

## 8. Example Improved Structure

### 8.1 Proposed Package Structure (Executor Refactoring)

```
executor/
├── DockerCodeExecutor.java          // Orchestration only (~300 LOC)
├── ContainerManager.java            // Container lifecycle (unchanged)
├── CommandResult.java               // Execution result DTO (unchanged)
├── CommandOutputParser.java         // NEW: Parse time/memory markers from output
├── ShellCommandBuilder.java         // NEW: Build timed shell commands
├── ExecutionContext.java            // NEW: Encapsulate execution parameters
└── ResultMapper.java               // NEW: CommandResult → ExecutionResult mapping
```

### 8.2 Refactored DockerCodeExecutor (Conceptual)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerCodeExecutor {

    private final DockerClient dockerClient;
    private final ContainerManager containerManager;
    private final ContainerPool containerPool;
    private final ExecutionConfig executionConfig;

    @Value("${sandbox.pool.enabled:true}")
    private boolean poolEnabled;

    public ExecuteResult execute(ExecutionContext ctx) {
        // 1. Dangerous code scan
        scanForDangerousCode(ctx);

        // 2. Delegate to appropriate mode
        return poolEnabled
            ? executeWithPool(ctx)
            : executeTraditional(ctx);
    }

    private ExecuteResult executeWithPool(ExecutionContext ctx) {
        PooledContainer pooled = null;
        String taskDir = null;
        try {
            pooled = containerPool.acquireContainer(ctx.strategy());
            taskDir = containerManager.getTaskDirectory("job-" + ctx.requestId());

            writeSourceCodeToContainer(pooled.getContainerId(), taskDir, ctx);
            String compileOutput = compileIfNeeded(pooled.getContainerId(), ctx, taskDir);
            List<ExecutionResult> results = runAllTestCases(pooled.getContainerId(), ctx, taskDir);

            return new ExecuteResult(compileOutput, results);
        } finally {
            cleanup(pooled, taskDir, ctx.requestId());
        }
    }

    private CommandResult executeCommand(String containerId, String[] cmd, 
                                          String input, long timeoutMs, String workDir) {
        // UNIFIED method — workDir can be null for traditional mode
        String timedCmd = ShellCommandBuilder.buildTimedCommand(
            String.join(" ", cmd), input, workDir);
        // ... Docker exec logic ...
        // ... Parse output using CommandOutputParser ...
    }

    private ExecutionResult runSingleTestCase(String containerId, ExecutionContext ctx,
                                               String taskDir, String input, int index) {
        CommandResult result = executeCommand(/* ... */);
        return ResultMapper.map(result, index, ctx.timeLimitMs(), ctx.memoryLimitMB());
    }
}
```

### 8.3 Before vs After — Line Count Estimate

| Component | Before | After (Estimated) |
|---|---|---|
| `DockerCodeExecutor` | ~930 LOC | ~350 LOC |
| `ShellCommandBuilder` | — | ~60 LOC |
| `CommandOutputParser` | — | ~70 LOC |
| `ResultMapper` | — | ~40 LOC |
| `ExecutionContext` | — | ~15 LOC |
| **Total** | **~930 LOC** | **~535 LOC** |

Net reduction of ~395 lines through deduplication and extraction, with improved readability and testability in every extracted component.

---

## 9. Summary & Prioritized Action Items

### Critical (Should Fix)

| # | Item | Impact | Effort |
|---|---|---|---|
| 1 | Unify `executeCommand()` / `executeCommandInDir()` to eliminate ~160 lines of duplication | High — reduces bug risk | Medium |
| 2 | Extract `ShellCommandBuilder` from `DockerCodeExecutor` | High — improves readability & testability | Medium |
| 3 | Extract `CommandOutputParser` from `DockerCodeExecutor` | High — isolates parsing logic | Low |
| 4 | Unify `runCode()` / `runCodeInTaskDir()` result-mapping logic | High — eliminates ~50 lines of duplication | Low |

### Recommended (Should Plan)

| # | Item | Impact | Effort |
|---|---|---|---|
| 5 | Add unit tests for language strategies, output parsing, shell escaping | High — prevents regression | Medium |
| 6 | Introduce `ExecutionContext` parameter object | Medium — cleaner API | Low |
| 7 | Remove unused dependencies from `pom.xml` | Medium — reduces artifact size and attack surface | Low |
| 8 | Replace generic `RuntimeException` with domain-specific exceptions | Medium — better error handling | Low |

### Nice to Have (Low Priority)

| # | Item | Impact | Effort |
|---|---|---|---|
| 9 | Fix inconsistent indentation in `ExecutionServiceImpl` | Low — code readability | Low |
| 10 | Extract hardcoded values (`"sandbox"`, `"/sandbox/workspace"`) to configuration | Low — flexibility | Low |
| 11 | Add behavior-enriching methods to `PooledContainer` (`markInUse()`, `markIdle()`) | Low — encapsulation | Low |
| 12 | Evaluate whether Hutool dependency is justified for HTTP-only usage | Low — dependency hygiene | Low |

---

*End of audit report.*
