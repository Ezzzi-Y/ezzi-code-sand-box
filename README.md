<div align="center">
  <h1>🛡️ Ezzi Code Sandbox</h1>
  <p>
    <strong>为在线评测系统 (OJ) 设计的安全、高效、可扩展的 Docker 代码执行沙箱</strong>
  </p>

  <p>
    <a href="#-核心特性">核心特性</a> •
    <a href="#-快速开始">快速开始</a> •
    <a href="#-系统架构">系统架构</a> •
    <a href="#-api-使用">API 使用</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Java-21-orange?logo=java" alt="Java 21">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green?logo=springboot" alt="Spring Boot 3">
    <img src="https://img.shields.io/badge/Docker-20.10+-blue?logo=docker" alt="Docker">
    <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
  </p>
</div>

---

## 📖 项目简介

**Ezzi Code Sandbox** 是一个专为在线评测系统 (Online Judge) 打造的高性能、安全的代码执行服务。本项目基于 Spring Boot 和 Docker 构建，旨在为多种编程语言提供安全、隔离的编译和运行环境。

与传统的进程级沙箱不同，本项目利用 **Docker-in-Docker (Socket 挂载)** 技术实现了更强的隔离性，同时保持了接近原生的执行性能。它能够自动管理容器生命周期，严格控制资源使用（CPU、内存、时间），并实施主要的安全策略。

## ✨ 核心特性

- **🔐 强效隔离**: 每次代码执行都在独立的、临时的 Docker 容器中运行，互不干扰。
- **⚡ 高性能**: 直接利用 Docker API 进行容器的秒级创建与销毁；支持容器复用策略（可选）。
- **🌍 多语言支持**:
  - **C** (GCC 11)
  - **C++** (G++ 11)
  - **Java** (JDK 8 / 17)
  - **Python** (3.10)

- **🛡️ 安全优先**:
  - 严格的内存与执行时间限制。
  - 输出内容大小限制（防止内存溢出）。
  - 只读文件系统（除特定的工作目录外）。
  - 网络访问隔离。
- **🚀 极简部署**: 支持 Docker Compose 一键部署。

## 🛠 技术栈

- **核心框架**: Spring Boot 3.5.7
- **容器化**: Docker & Docker Java Client (3.3.4)
- **工具库**: Hutool, Lombok, Caffeine (本地缓存)
- **存储**: MinIO / Aliyun OSS (用于测试用例管理)

## 🚀 快速开始

推荐使用 Docker Compose 进行快速部署。

### 环境要求
- Linux / macOS / Windows (推荐 WSL2)
- **Docker** 20.10+
- **Docker Compose**

### 安装步骤

1.  **克隆项目**
    ```bash
    git clone https://github.com/ezzzi-y/ezzi-code-sand-box.git
    cd ezzi-code-sand-box
    ```

2.  **拉取语言镜像** (仅首次需要在宿主机执行)
    ```bash
    # 这些是代码执行所必需的基础镜像
    docker pull gcc:11
    docker pull eclipse-temurin:8-jdk-alpine
    docker pull eclipse-temurin:17-jdk-alpine
    docker pull python:3.10
    docker pull golang:1.20
    ```

3.  **启动服务**
    ```bash
    docker-compose up -d
    ```

4.  **验证服务状态**
    ```bash
    curl http://localhost:6060/sandbox/api/health/ping
    # 预期响应: "pong"
    ```

## ⚙️ 配置说明

核心配置位于 `application.yml` 文件中：

```yaml
sandbox:
  execution:
    work-dir: /var/lib/sandbox-work      # 宿主机上用于存放临时文件的路径
    compile-timeout: 30                   # 编译超时时间 (秒)
    run-timeout: 10                       # 运行超时时间 (秒)
    memory-limit: 256                     # 内存限制 (MB)
  docker:
    host: unix:///var/run/docker.sock     # Docker Socket 连接地址
```

## 🔌 API 使用

### 单次执行代码 (HTTP)

**接口地址**: `POST /sandbox/api/execute/single`

**请求示例**:
```json
{
  "requestId": "test-req-001",
  "language": "python3",
  "code": "print(sum(map(int, input().split())))",
  "input": "10 20",
  "timeLimit": 1000,
  "memoryLimit": 256
}
```

**响应示例**:
```json
{
  "code": 1,
  "message": "success",
  "data": {
    "status": "SUCCESS",
    "compileOutput": null,
    "errorMessage": null,
    "result": {
      "index": 1,
      "status": "SUCCESS",
      "output": "30",
      "errorOutput": "",
      "time": 35,
      "memory": 6204,
      "exitCode": 0
    },
    "totalTime": 45
  }
}
```

### 批量执行代码 (HTTP)

**接口地址**: `POST /sandbox/api/execute/batch`

**请求示例**:
```json
{
  "requestId": "test-req-002",
  "language": "python3",
  "code": "print(sum(map(int, input().split())))",
  "inputDataUrl": "https://example.com/question-1001-inputs.zip?signature=xxx",
  "timeLimit": 1000,
  "memoryLimit": 256
}
```

**响应示例**:
```json
{
  "code": 1,
  "message": "success",
  "data": {
    "status": "SUCCESS",
    "compileOutput": null,
    "errorMessage": null,
    "results": [
      {
        "index": 1,
        "status": "SUCCESS",
        "output": "3",
        "errorOutput": "",
        "time": 12,
        "memory": 2000,
        "exitCode": 0
      }
    ],
    "summary": {
      "total": 3,
      "success": 3,
      "failed": 0
    },
    "totalTime": 82
  }
}
```

### 输入数据缓存策略

- 批量请求必须提供 `inputDataUrl`，且 URL 必须指向 zip 输入数据包。
- zip 中只包含输入数据文件，命名规则为 `1.in`、`2.in`、`3.in`...
- 服务在每次执行前先请求远端对象元数据。
- 以 `ETag` 与 `Last-Modified` 与本地缓存元数据比较：
  - 一致：直接使用本地缓存。
  - 不一致：重新下载并覆盖本地缓存。
- 不再提供手动触发的缓存更新接口。

## 🏗 系统架构

本项目采用分层架构设计，分离了 API 层、业务逻辑层和 Docker 执行器层。

> 如需了解详细的架构设计，请参阅 [架构设计文档](docs/01-ARCHITECTURE.md)。

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1.  Fork 本项目
2.  创建特性分支 (`git checkout -b feature/AmazingFeature`)
3.  提交改动 (`git commit -m 'Add some AmazingFeature'`)
4.  推送到分支 (`git push origin feature/AmazingFeature`)
5.  提交 Pull Request

## 📄 许可证

本项目基于 **MIT License** 开源。详情请参阅 [LICENSE](LICENSE) 文件。

---

<div align="center">
  <p>Made with ❤️ by <a href="https://github.com/ezzzi-y">Ezzzi-Y</a></p>
</div>
