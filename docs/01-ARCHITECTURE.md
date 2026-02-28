# OJ 代码执行沙箱服务 - 系统架构设计

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
| 运行时 | Java | 17+ |
| 框架 | Spring Boot | 3.x |
| 容器化 | Docker | 20.10+ |
| Docker 交互 | docker-java | 3.3.x |
| HTTP 客户端 | Hutool HttpUtil | 5.8.x |
| 本地缓存 | 磁盘文件缓存（InputDataService） | - |
| 工具库 | Hutool | 5.8.x |

### 1.3 支持的编程语言

| 语言 | 版本 | 编译命令 | 执行命令 |
|------|------|---------|---------|
| C | GCC 11 | `gcc -O2 -std=c11 -o main main.c` | `./main` |
| C++ | G++ 11 | `g++ -O2 -std=c++11 -o main main.cpp` | `./main` |
| Java | 8 | `javac -encoding UTF-8 Main.java` | `java -Xmx{mem}m Main` |
| Java | 11 | `javac -encoding UTF-8 Main.java` | `java -Xmx{mem}m Main` |
| Python | 3.10 | - | `python3 main.py` |

---

## 2. 系统架构

### 2.1 整体架构图

> 2026-02 实现说明：输入数据缓存由 `InputDataService` 内聚实现，当前无独立 `CacheController` / `CacheService`。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              OJ 判题服务                                      │
│                         (backend-tyut-oj)                                   │
└─────────────────────────────────┬───────────────────────────────────────────┘
                                  │ HTTP/RPC
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           代码沙箱服务                                        │
│                         (code-sand-box)                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         API Layer                                    │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │ ExecuteController│                       │ HealthController │  │   │
│  │  └────────┬─────────┘                       └──────────────────┘  │   │
│  └───────────┼──────────────────────┼──────────────────────────────────┘   │
│              │                                                          │
│  ┌───────────▼──────────────────────────────────────────────────────────┐   │
│  │                        Service Layer                                 │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │ ExecutionService │  │ InputDataService │  │LanguageRegistry  │  │   │
│  │  └────────┬─────────┘  └────────┬─────────┘  └──────────────────┘  │   │
│  └───────────┼──────────────────────┼──────────────────────────────────┘   │
│              │                      │                                       │
│  ┌───────────▼──────────────────────▼──────────────────────────────────┐   │
│  │                       Core Layer                                     │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │ DockerExecutor   │  │ InputDataService │  │  TestCaseService │  │   │
│  │  └────────┬─────────┘  └──────────────────┘  └──────────────────┘  │   │
│  └───────────┼──────────────────────────────────────────────────────────┘   │
│              │                                                              │
│  ┌───────────▼──────────────────────────────────────────────────────────┐   │
│  │                      Infrastructure Layer                            │   │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │  Docker Engine   │  │  Local Disk Cache│  │  HTTP Download   │  │   │
│  │  │   (容器运行时)     │  │ (_meta+*.in 文件) │  │  (预签名URL)      │  │   │
│  │  └──────────────────┘  └──────────────────┘  └──────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 执行时序图

```
┌──────┐     ┌──────────┐    ┌───────────┐    ┌─────────────┐   ┌──────┐    ┌───────┐
│Client│     │Controller│    │ ExecService│    │InputDataService│ │Executor│  │Docker │
└──┬───┘     └────┬─────┘    └─────┬─────┘    └──────┬──────┘   └───┬───┘   └───┬───┘
   │              │                │               │             │           │
   │ POST /execute│                │               │             │           │
   │─────────────>│                │               │             │           │
   │              │                │               │             │           │
   │              │  execute(req)  │               │             │           │
   │              │───────────────>│               │             │           │
   │              │                │               │             │           │
  │              │                │ getInputDataSet()          │           │
   │              │                │──────────────>│             │           │
   │              │                │               │             │           │
  │              │                │               │─┐           │           │
  │              │                │               │ │ HEAD(预签名URL)       │
  │              │                │               │<┘           │           │
   │              │                │               │             │           │
  │              │                │               │ (缓存未命中) │           │
   │              │                │               │────────>HTTP Download   │
   │              │                │               │<────────(预签名URL)      │
   │              │                │               │             │           │
   │              │                │  inputData    │             │           │
   │              │                │<──────────────│             │           │
   │              │                │               │             │           │
   │              │                │       run(code, input)      │           │
   │              │                │─────────────────────────────>│           │
   │              │                │               │             │           │
   │              │                │               │             │ create    │
   │              │                │               │             │──────────>│
   │              │                │               │             │           │
   │              │                │               │             │ compile   │
   │              │                │               │             │──────────>│
   │              │                │               │             │           │
   │              │                │               │             │ execute   │
   │              │                │               │             │──────────>│
   │              │                │               │             │           │
   │              │                │               │             │<──────────│
   │              │                │               │             │  result   │
   │              │                │               │             │           │
   │              │                │               │             │ remove    │
   │              │                │               │             │──────────>│
   │              │                │               │             │           │
   │              │                │     ExecutionResult         │           │
   │              │                │<────────────────────────────│           │
   │              │                │               │             │           │
   │              │   result       │               │             │           │
   │              │<───────────────│               │             │           │
   │              │                │               │             │           │
   │   response   │                │               │             │           │
   │<─────────────│                │               │             │           │
   │              │                │               │             │           │
```

---

## 3. 模块划分

### 3.1 包结构

```
com.github.ezzziy.codesandbox
├── CodeSandBoxApplication.java          # 启动类
│
├── config/                              # 配置层
│   ├── DockerConfig.java               # Docker 客户端配置
│   ├── OSSConfig.java                  # OSS 配置
│   ├── CacheConfig.java                # 缓存配置
│   └── SecurityConfig.java             # 安全配置
│
├── controller/                          # API 层
│   ├── ExecuteController.java          # 代码执行接口
│   └── HealthController.java           # 健康检查接口
│
├── service/                             # 服务层
│   ├── ExecutionService.java           # 执行服务接口
│   ├── InputDataService.java           # 输入数据缓存与读取服务
│   ├── impl/
│   │   └── ExecutionServiceImpl.java   # 执行服务实现
│   ├── impl/
│   │   └── InputDataServiceImpl.java   # 输入数据缓存实现
│   └── HealthService.java              # 健康服务
│
├── executor/                            # 执行器层（核心）
│   ├── CodeExecutor.java               # 执行器接口
│   ├── DockerCodeExecutor.java         # Docker 执行器实现
│   ├── ExecutorFactory.java            # 执行器工厂
│   └── strategy/                       # 语言策略
│       ├── LanguageStrategy.java       # 语言策略接口
│       ├── CLanguageStrategy.java      # C 语言策略
│       ├── CppLanguageStrategy.java    # C++ 语言策略
│       ├── JavaLanguageStrategy.java   # Java 语言策略
│       ├── PythonLanguageStrategy.java # Python 语言策略
│       └── GoLanguageStrategy.java     # Go 语言策略
│
├── docker/                              # Docker 交互层
│   ├── DockerClientWrapper.java        # Docker 客户端封装
│   ├── ContainerManager.java           # 容器管理器
│   └── ResourceLimiter.java            # 资源限制器
│
├── oss/                                 # OSS 层
│   ├── OSSClient.java                  # OSS 客户端接口
│   ├── MinIOClient.java                # MinIO 实现
│   └── AliyunOSSClient.java            # 阿里云 OSS 实现
│
├── model/                               # 数据模型
│   ├── request/
│   │   ├── ExecuteRequest.java         # 执行请求
│   │   └── CacheRefreshRequest.java    # 缓存刷新请求
│   ├── response/
│   │   └── ExecutionResult.java        # 执行结果
│   ├── enums/
│   │   ├── LanguageEnum.java           # 语言枚举
│   │   └── ExecutionStatus.java        # 执行状态枚举
│   └── dto/
│       ├── CompileResult.java          # 编译结果
│       └── RunResult.java              # 运行结果
│
├── exception/                           # 异常处理
│   ├── SandboxException.java           # 沙箱基础异常
│   ├── CompileException.java           # 编译异常
│   ├── ExecutionException.java         # 执行异常
│   ├── TimeoutException.java           # 超时异常
│   ├── MemoryLimitException.java       # 内存超限异常
│   └── GlobalExceptionHandler.java     # 全局异常处理器
│
└── util/                                # 工具类
    ├── FileUtils.java                  # 文件工具
    ├── ProcessUtils.java               # 进程工具
    └── DockerCommandBuilder.java       # Docker 命令构建器
```

### 3.2 模块职责说明

| 模块 | 职责 | 关键类 |
|------|------|--------|
| **config** | 配置管理，Bean 初始化 | `DockerConfig`, `OSSConfig` |
| **controller** | HTTP 接口，参数校验 | `ExecuteController` |
| **service** | 业务编排，流程控制 | `ExecutionService` |
| **executor** | 代码执行核心逻辑 | `DockerCodeExecutor` |
| **docker** | Docker API 交互封装 | `ContainerManager` |
| **cache** | 输入数据本地缓存 | `LocalFileCache` |
| **oss** | 对象存储交互 | `MinIOClient` |
| **model** | 数据传输对象 | `ExecuteRequest`, `ExecutionResult` |
| **exception** | 异常定义与处理 | `SandboxException` |
| **util** | 通用工具方法 | `FileUtils` |

---

## 4. 核心依赖

### 4.1 Maven 依赖

```xml
<!-- Docker Java Client -->
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java-core</artifactId>
    <version>3.3.4</version>
</dependency>
<dependency>
    <groupId>com.github.docker-java</groupId>
    <artifactId>docker-java-transport-httpclient5</artifactId>
    <version>3.3.4</version>
</dependency>

<!-- Hutool（含 HttpUtil，用于预签名 URL 下载） -->
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
server:
  port: 8090

spring:
  application:
    name: code-sandbox

# 沙箱配置
sandbox:
  # Docker 配置
  docker:
    host: unix:///var/run/docker.sock  # Linux
    # host: tcp://localhost:2375       # Windows
    api-version: "1.41"
    connection-timeout: 30000
    read-timeout: 60000
  
  # 执行限制
  execution:
    default-time-limit: 5000      # 默认时间限制 (ms)
    default-memory-limit: 256     # 默认内存限制 (MB)
    max-time-limit: 30000         # 最大时间限制 (ms)
    max-memory-limit: 512         # 最大内存限制 (MB)
    max-output-size: 65536        # 最大输出大小 (bytes)
    max-process-count: 10         # 最大进程数
  
  # 缓存配置
  cache:
    input-data:
      enabled: true
      base-path: /var/sandbox/cache/input
      max-size: 1000              # 最大缓存数量
      expire-hours: 24            # 过期时间（小时）
  
  # OSS 配置
  oss:
    type: minio                   # minio / aliyun
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket: oj-testcases
    
  # 语言镜像配置
  images:
    c: "gcc:11-bullseye"
    cpp: "gcc:11-bullseye"
    java8: "openjdk:8-jdk-slim"
    java11: "openjdk:11-jdk-slim"
    python3: "python:3.10-slim"
    golang: "golang:1.20-alpine"
```

---

## 6. 下一步文档

本文档是系统架构设计的第一部分，后续文档将详细展开：

1. **[02-API-DESIGN.md](02-API-DESIGN.md)** - API 设计与数据模型
2. **[03-DOCKER-EXECUTOR.md](03-DOCKER-EXECUTOR.md)** - Docker 执行器核心实现
3. **[04-LANGUAGE-SUPPORT.md](04-LANGUAGE-SUPPORT.md)** - 多语言支持配置
4. **[05-SECURITY.md](05-SECURITY.md)** - 安全机制实现
5. **[06-CACHE-OSS.md](06-CACHE-OSS.md)** - 缓存与 OSS 集成
6. **[07-CORE-IMPLEMENTATION.md](07-CORE-IMPLEMENTATION.md)** - 核心代码实现
