# 代码执行沙箱服务 - 系统架构设计

## 1. 系统概述

### 1.1 职责边界

本沙箱服务**仅负责**：
- ✅ 接收用户代码与执行参数
- ✅ 通过预签名 URL 下载测试输入数据（支持本地缓存）
- ✅ 在隔离的 Docker 容器中编译和执行代码
- ✅ 采集执行结果（stdout、stderr、exitCode、时间、内存）
- ✅ 返回统一格式的执行结果

### 1.2 技术栈

| 组件 | 技术选型 | 版本 |
|------|---------|------|
| 运行时 | Java | 21 |
| 框架 | Spring Boot | 3.5.x |
| 容器化 | Docker | 20.10+ |
| Docker 交互 | docker-java + zerodep transport | 3.3.4 |
| HTTP 客户端 | Hutool HttpRequest | 5.8.38 |
| 本地缓存 | 磁盘文件缓存（InputDataService） | - |
| 工具库 | Hutool | 5.8.38 |
| 压缩 | Apache Commons Compress | 1.26.0 |

### 1.3 支持的编程语言

| 语言 | 枚举标识 | Docker 镜像 | 编译命令 | 执行命令 |
|------|---------|-------------|---------|---------|
| C | `c` | `sandbox-gcc:latest` | `gcc -std=c11 -O2 -Wall -Wextra -fno-asm -lm -o main main.c` | `./main` |
| C++ | `cpp11` | `sandbox-gcc:latest` | `g++ -std=c++11 -O2 -Wall -Wextra -fno-asm -o main main.cpp` | `./main` |
| Java 8 | `java8` | `sandbox-java8:latest` | `javac -encoding UTF-8 -d . Main.java` | `java -Xmx256m -Xms64m -Djava.security.manager=default -cp . Main` |
| Java 17 | `java17` | `sandbox-java17:latest` | `javac -encoding UTF-8 -d . Main.java` | `java -Xmx256m -Xms64m -XX:+UseG1GC -cp . Main` |
| Python 3 | `python3` | `sandbox-python:latest` | - | `python3 -u main.py` |

---

## 2. 系统架构

### 2.1 整体架构图

> 2026-02 实现说明：输入数据缓存由 `InputDataService` 内聚实现，当前无独立 `CacheController` / `CacheService`。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              上游业务服务                                      │
│                    (如在线评测平台/教学平台/代码练习平台)                       │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTP
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           代码沙箱服务                                        │
│                     (ezzi-code-sand-box :6060)                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         API Layer                                    │   │
│  │  ┌──────────────────┐                     ┌──────────────────┐     │   │
│  │  │ ExecuteController│                     │ HealthController │     │   │
│  │  │ /execute/*       │                     │ /health/*        │     │   │
│  │  └────────┬─────────┘                     └──────────────────┘     │   │
│  └───────────┼─────────────────────────────────────────────────────────┘   │
│              │                                                              │
│  ┌───────────▼──────────────────────────────────────────────────────────┐   │
│  │                        Service Layer                                 │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │ExecutionService  │  │ InputDataService │  │LanguageStrategy  │  │   │
│  │  │  Impl            │  │  Impl            │  │  Factory         │  │   │
│  │  └────────┬─────────┘  └────────┬─────────┘  └──────────────────┘  │   │
│  └───────────┼──────────────────────┼──────────────────────────────────┘   │
│              │                      │                                       │
│  ┌───────────▼──────────────────────▼──────────────────────────────────┐   │
│  │                       Core Layer                                     │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │DockerCodeExecutor│  │ ContainerManager │  │  ContainerPool   │  │   │
│  │  └────────┬─────────┘  └──────────────────┘  └──────────────────┘  │   │
│  └───────────┼──────────────────────────────────────────────────────────┘   │
│              │                                                              │
│  ┌───────────▼──────────────────────────────────────────────────────────┐   │
│  │                      Infrastructure Layer                            │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │  Docker Engine   │  │  Local Disk Cache│  │  HTTP Download   │  │   │
│  │  │   (容器运行时)     │  │ (_meta+*.in 文件) │  │  (预签名URL GET) │  │   │
│  │  └──────────────────┘  └──────────────────┘  └──────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 执行时序图

```
┌──────┐     ┌──────────┐    ┌───────────┐    ┌─────────────────┐   ┌────────────┐   ┌──────────┐  ┌──────┐
│Client│     │Controller│    │ ExecService│    │InputDataService │   │ContainerPool│  │Executor  │  │Docker│
└──┬───┘     └────┬─────┘    └─────┬─────┘    └──────┬──────────┘   └─────┬──────┘  └────┬─────┘  └──┬───┘
   │              │                │                  │                    │              │           │
   │ POST /execute│                │                  │                    │              │           │
   │─────────────>│                │                  │                    │              │           │
   │              │                │                  │                    │              │           │
   │              │  executeSingle │                  │                    │              │           │
   │              │  /executeBatch │                  │                    │              │           │
   │              │───────────────>│                  │                    │              │           │
   │              │                │                  │                    │              │           │
   │              │                │ getInputDataSet()│                    │              │           │
   │              │                │─────────────────>│                    │              │           │
   │              │                │                  │                    │              │           │
   │              │                │                  │─┐                  │              │           │
   │              │                │                  │ │ GET(预签名URL)   │              │           │
   │              │                │                  │<┘                  │              │           │
   │              │                │                  │                    │              │           │
   │              │                │                  │ (缓存命中则复用    │              │           │
   │              │                │                  │  否则解压落盘)     │              │           │
   │              │                │                  │                    │              │           │
   │              │                │  inputDataSet    │                    │              │           │
   │              │                │<─────────────────│                    │              │           │
   │              │                │                  │                    │              │           │
   │              │                │     acquireContainer                  │              │           │
   │              │                │─────────────────────────────────────>│              │           │
   │              │                │                  │                    │              │           │
   │              │                │     execute(strategy, code, inputs)  │              │           │
   │              │                │─────────────────────────────────────────────────────>│           │
   │              │                │                  │                    │              │           │
   │              │                │                  │                    │              │ compile   │
   │              │                │                  │                    │              │──────────>│
   │              │                │                  │                    │              │           │
   │              │                │                  │                    │              │ run       │
   │              │                │                  │                    │              │──────────>│
   │              │                │                  │                    │              │           │
   │              │                │                  │                    │              │<──────────│
   │              │                │                  │                    │              │  result   │
   │              │                │                  │                    │              │           │
   │              │                │     releaseContainer                  │              │           │
   │              │                │─────────────────────────────────────>│              │           │
   │              │                │                  │                    │              │           │
   │              │   response     │                  │                    │              │           │
   │              │<───────────────│                  │                    │              │           │
   │              │                │                  │                    │              │           │
   │   Result<>   │                │                  │                    │              │           │
   │<─────────────│                │                  │                    │              │           │
   │              │                │                  │                    │              │           │
```

---

## 3. 模块划分

### 3.1 包结构

```
com.github.ezzziy.codesandbox
├── CodeSandBoxApplication.java          # 启动类（@EnableScheduling）
│
├── common/                              # 通用模块
│   ├── enums/
│   │   ├── ExecutionStatus.java        # 执行状态枚举（SUCCESS/COMPILE_ERROR/RUNTIME_ERROR/...）
│   │   └── LanguageEnum.java           # 语言枚举（C/CPP/JAVA8/JAVA17/PYTHON3）
│   └── result/
│       └── Result.java                 # 统一响应包装（code/message/data）
│
├── config/                              # 配置层
│   ├── DockerConfig.java               # Docker 客户端配置（@Value 注入）
│   └── ExecutionConfig.java            # 执行限制配置（@ConfigurationProperties）
│
├── controller/                          # API 层
│   ├── ExecuteController.java          # 代码执行接口（/execute/*）
│   └── HealthController.java           # 健康检查接口（/health/*）
│
├── service/                             # 服务层
│   ├── ExecutionService.java           # 执行服务接口
│   ├── InputDataService.java           # 输入数据服务接口
│   ├── HealthService.java              # 健康服务接口
│   └── impl/
│       ├── ExecutionServiceImpl.java   # 执行服务实现
│       ├── InputDataServiceImpl.java   # 输入数据缓存实现
│       └── HealthServiceImpl.java      # 健康服务实现
│
├── executor/                            # 执行器层（核心）
│   ├── DockerCodeExecutor.java         # Docker 代码执行器
│   ├── ContainerManager.java           # 容器管理器（创建/启动/清理）
│   └── CommandResult.java              # 命令执行结果
│
├── pool/                                # 容器池
│   ├── ContainerPool.java              # 容器池管理（预热/获取/归还/清理）
│   └── PooledContainer.java            # 池化容器对象
│
├── strategy/                            # 语言策略
│   ├── LanguageStrategy.java           # 语言策略接口
│   ├── LanguageStrategyFactory.java    # 策略工厂
│   ├── CLanguageStrategy.java          # C 语言策略
│   ├── CppLanguageStrategy.java        # C++ 语言策略
│   ├── Java8LanguageStrategy.java      # Java 8 策略
│   ├── Java17LanguageStrategy.java     # Java 17 策略
│   └── Python3LanguageStrategy.java    # Python 3 策略
│
├── model/                               # 数据模型
│   ├── dto/
│   │   ├── SingleExecuteRequest.java   # 单次执行请求
│   │   ├── BatchExecuteRequest.java    # 批量执行请求
│   │   ├── ExecuteRequest.java         # 通用执行请求（含 input/inputDataUrl）
│   │   ├── ExecutionResult.java        # 单测试用例执行结果
│   │   ├── ExecutionTimeStats.java     # 执行时间统计
│   │   └── InputDataSet.java           # 输入数据集
│   └── vo/
│       ├── SingleExecuteResponse.java  # 单次执行响应
│       ├── BatchExecuteResponse.java   # 批量执行响应
│       └── ExecuteResponse.java        # 通用执行响应
│
├── exception/                           # 异常处理
│   ├── SandboxException.java           # 沙箱基础异常（含 ExecutionStatus + requestId）
│   ├── CompileException.java           # 编译异常
│   ├── DangerousCodeException.java     # 危险代码异常
│   ├── RuntimeErrorException.java      # 运行时错误异常
│   ├── TimeLimitException.java         # 超时异常
│   ├── MemoryLimitException.java       # 内存超限异常
│   └── GlobalExceptionHandler.java     # 全局异常处理器
│
└── util/                                # 工具类
    └── OssUrlParser.java               # 预签名 URL 解析（提取 ObjectKey）
```

### 3.2 模块职责说明

| 模块 | 职责 | 关键类 |
|------|------|--------|
| **common** | 枚举定义、统一响应 | `ExecutionStatus`, `LanguageEnum`, `Result` |
| **config** | 配置管理，Bean 初始化 | `DockerConfig`, `ExecutionConfig` |
| **controller** | HTTP 接口，参数校验 | `ExecuteController`, `HealthController` |
| **service** | 业务编排，流程控制 | `ExecutionServiceImpl`, `InputDataServiceImpl` |
| **executor** | Docker 命令执行 | `DockerCodeExecutor`, `ContainerManager` |
| **pool** | 容器池化复用 | `ContainerPool`, `PooledContainer` |
| **strategy** | 语言编译/运行策略 | `LanguageStrategy`, `LanguageStrategyFactory` |
| **model** | 请求/响应/DTO | `SingleExecuteRequest`, `BatchExecuteResponse` |
| **exception** | 异常定义与处理 | `SandboxException`, `GlobalExceptionHandler` |
| **util** | 通用工具 | `OssUrlParser` |

---

## 4. 核心依赖

### 4.1 Maven 依赖

```xml
<!-- Docker Java Client (zerodep transport) -->
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java-core</artifactId>
    <version>3.3.4</version>
</dependency>
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java-transport-zerodep</artifactId>
    <version>3.3.4</version>
</dependency>

<!-- Hutool（含 HttpRequest，用于预签名 URL 下载） -->
<dependency>
  <groupId>cn.hutool</groupId>
  <artifactId>hutool-all</artifactId>
  <version>5.8.38</version>
</dependency>

<!-- Apache Commons Compress（ZIP 解压） -->
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-compress</artifactId>
  <version>1.26.0</version>
</dependency>

<!-- Apache Commons IO -->
<dependency>
    <groupId>commons-io</groupId>
    <artifactId>commons-io</artifactId>
    <version>2.15.1</version>
</dependency>
```

---

## 5. 配置文件结构

### 5.1 application.yml

```yaml
spring:
  application:
    name: code-sand-box

server:
  port: 6060

# 沙箱配置
sandbox:
  # Docker 配置
  docker:
    host: ${DOCKER_HOST:unix:///var/run/docker.sock}
    connect-timeout: 30          # 连接超时（秒）
    response-timeout: 60         # 读取超时（秒）
    max-connections: 100

  # 容器池配置
  pool:
    enabled: true
    min-size: 1                  # 每种语言最小池大小
    max-size: 4                  # 每种语言最大池大小
    max-idle-minutes: 10         # 空闲超时（分钟）
    max-use-count: 100           # 单容器最大使用次数

  # 执行限制
  execution:
    compile-timeout: 30          # 编译超时（秒）
    run-timeout: 10              # 运行超时（秒）
    total-timeout: 300           # 总超时（秒）
    memory-limit: 256            # 默认内存限制（MB）
    cpu-limit: 1.0               # CPU 限制
    output-limit: 65536          # 最大输出（字节 = 64KB）
    max-processes: 1024          # 最大进程数
    max-open-files: 256          # 最大打开文件数
    max-test-cases: 100          # 最大测试用例数
    work-dir: /var/lib/sandbox-work
    enable-code-scan: true       # 是否启用危险代码扫描
    max-concurrent-containers: 50

  # 输入数据缓存
  input-data:
    storage-dir: /var/lib/sandbox-inputs
    download-timeout: 30000      # 下载超时（毫秒）
    max-file-size: 10485760      # ZIP 最大大小（字节 = 10MB）
```

---

## 6. 下一步文档

本文档是系统架构设计的第一部分，后续文档将详细展开：

1. **[02-API-DESIGN.md](02-API-DESIGN.md)** - API 设计与数据模型
2. **[03-DOCKER-EXECUTOR.md](03-DOCKER-EXECUTOR.md)** - Docker 执行器核心实现
3. **[04-LANGUAGE-SUPPORT.md](04-LANGUAGE-SUPPORT.md)** - 多语言支持配置
4. **[05-SECURITY.md](05-SECURITY.md)** - 安全机制实现
5. **[06-CACHE-OSS.md](06-CACHE-OSS.md)** - 输入数据缓存体系
6. **[07-CORE-IMPLEMENTATION.md](07-CORE-IMPLEMENTATION.md)** - 核心代码实现
7. **[08-TASK-ISOLATION.md](08-TASK-ISOLATION.md)** - 任务隔离与文件系统架构
