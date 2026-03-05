<div align="center">
  <h1>Ezzi Code Sandbox</h1>
  <p><strong>基于Docker容器的代码执行沙箱服务</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Java-21-orange?logo=java" alt="Java 21">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.5.x-green?logo=springboot" alt="Spring Boot">
    <img src="https://img.shields.io/badge/Docker-20.10+-blue?logo=docker" alt="Docker">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue" alt="Apache 2.0">
  </p>
</div>

---

## 项目简介

`ezzi-code-sand-box` 是一个独立部署的代码执行服务，负责接收代码和输入数据，在隔离容器中编译/运行，并返回结构化执行结果。

项目定位为“可被上游业务系统调用的执行引擎”，典型场景包括：

- 在线评测（OJ）
- 编程教学平台
- AI生成代码执行
- 代码练习与面试系统

## 核心特性

- **多语言执行**：`c`、`cpp11`、`java8`、`java17`、`python3`
- **容器隔离**：每次请求在受限 Docker 容器中执行，执行容器网络默认禁用
- **资源限制**：内存/CPU/超时/进程数/输出大小均可配置
- **安全防护**：危险代码模式扫描、Capabilities 全量裁剪、非 root 用户执行
- **高吞吐优化**：支持容器池（预热、复用、回收）
- **输入缓存**：批量执行支持 URL + 版本号缓存，命中时可零下载

## 技术栈

- Java 21
- Spring Boot 3.5.7
- docker-java 3.3.4（zerodep transport）
- Apache Commons Compress / Commons IO / Hutool
- Docker Compose（部署）

## 快速开始

### 1) 环境要求

- Linux 主机（推荐）
- Docker 20.10+
- Docker Compose v2

### 2) 克隆仓库

```bash
git clone https://github.com/ezzzi-y/ezzi-code-sand-box.git
cd ezzi-code-sand-box
```

### 3) 构建沙箱运行时镜像

```bash
docker compose build sandbox-gcc sandbox-java8 sandbox-java17 sandbox-python
```

### 4) 启动服务

```bash
docker compose up -d sandbox
```

### 5) 健康检查

```bash
curl http://localhost:6060/health/ping
```

返回体为统一 `Result` 结构，`code=1` 表示成功。

## API 概览

> 所有接口默认无额外前缀，直接以 `/execute/*`、`/health/*` 访问。

| 接口 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 单次执行 | POST | `/execute/single` | 单输入执行 |
| 批量执行 | POST | `/execute/batch` | 多测试用例执行 |
| 语言列表 | GET | `/execute/languages` | 当前支持语言 |
| 健康检查 | GET | `/health` | 详细健康信息 |
| 存活探针 | GET | `/health/ping` | 基础可用性检查 |
| Liveness | GET | `/health/liveness` | K8s 存活探针 |
| Readiness | GET | `/health/readiness` | K8s 就绪探针 |

### 单次执行示例

```bash
curl -X POST 'http://localhost:6060/execute/single' \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId":"demo-single-001",
    "language":"python3",
    "code":"print(sum(map(int, input().split())))",
    "input":"10 20",
    "timeLimit":1000,
    "memoryLimit":256
  }'
```

### 批量执行示例

```bash
curl -X POST 'http://localhost:6060/execute/batch' \
  -H 'Content-Type: application/json' \
  -d '{
    "requestId":"demo-batch-001",
    "language":"java17",
    "code":"import java.util.*; public class Main { public static void main(String[] a){ Scanner s=new Scanner(System.in); int x=s.nextInt(), y=s.nextInt(); System.out.println(x+y);} }",
    "inputDataUrl":"https://example.com/inputs.zip?signature=xxx",
    "inputDataVersion":"sha256:abcd1234",
    "timeLimit":1000,
    "memoryLimit":256
  }'
```

## 配置说明

核心配置文件：`src/main/resources/application.yml`

重点配置项：

- `sandbox.docker.host`：Docker socket 地址（默认 `unix:///var/run/docker.sock`）
- `sandbox.pool.*`：容器池开关与容量
- `sandbox.execution.*`：编译/运行超时、CPU/内存、输出限制、并发限制
- `sandbox.input-data.*`：输入数据本地缓存目录、下载超时、文件大小限制

## 项目文档

仓库内提供了完整设计文档：

- [系统架构](docs/01-ARCHITECTURE.md)
- [API 设计](docs/02-API-DESIGN.md)
- [Docker 执行器](docs/03-DOCKER-EXECUTOR.md)
- [语言支持](docs/04-LANGUAGE-SUPPORT.md)
- [安全机制](docs/05-SECURITY.md)
- [缓存与版本方案](docs/06-CACHE-OSS.md)

## 开发与测试

本地运行：

```bash
./mvnw spring-boot:run
```

运行测试：

```bash
./mvnw test
```

## 贡献指南

欢迎提交 Issue / PR。建议流程：

1. Fork 仓库
2. 创建分支（如 `feature/xxx` 或 `fix/xxx`）
3. 提交变更并补充必要文档
4. 发起 Pull Request

## License

本项目采用 [Apache License 2.0](LICENSE)。
