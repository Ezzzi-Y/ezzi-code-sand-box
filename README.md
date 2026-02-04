# Code Sandbox - OJ 代码沙箱服务

> 安全、高效的在线代码执行沙箱，支持多种编程语言

## 📋 概述

Code Sandbox 是一个基于 Docker 的代码执行沙箱服务，为在线评测系统（OJ）提供安全隔离的代码编译和运行环境。

### 支持的语言

| 语言 | 镜像 | 编译器/解释器 |
|------|------|---------------|
| C | gcc:11 | GCC 11 |
| C++ | gcc:11 | G++ 11 |
| Java 8 | eclipse-temurin:8-jdk-alpine | OpenJDK 8 |
| Java 17 | eclipse-temurin:17-jdk-alpine | OpenJDK 17 |
| Python 3 | python:3.10 | Python 3.10 |
| Go | golang:1.20 | Go 1.20 |

## 🚀 快速部署

### 系统要求

- **操作系统**: Linux (推荐 Ubuntu 22.04 LTS)
- **Docker**: 20.10+
- **内存**: 最低 4GB，推荐 8GB+
- **磁盘**: 最低 20GB 可用空间

---

## 🔧 部署方式

### 方式一：Docker Compose 部署（推荐）

**优点**: 一键部署，自动管理依赖，配置清晰

**部署步骤:**

#### 1. 安装 Docker（如已安装可跳过）

```bash
# Ubuntu/Debian
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker
```

#### 2. 创建必需目录

```bash
# 沙箱工作目录（存放临时编译文件）
sudo mkdir -p /var/lib/sandbox-work
sudo chmod 777 /var/lib/sandbox-work

# 输入数据目录（存放测试用例缓存）
sudo mkdir -p /var/lib/sandbox-inputs
sudo chmod 755 /var/lib/sandbox-inputs
```

> ⚠️ **重要**: 这两个目录路径必须与 `application.yml` 中的配置一致，且容器内外使用相同的绝对路径！

#### 3. 预拉取语言镜像

```bash
# 拉取所有语言运行时镜像（首次部署必须执行）
docker pull gcc:11
docker pull eclipse-temurin:8-jdk-alpine
docker pull eclipse-temurin:17-jdk-alpine
docker pull python:3.10
docker pull golang:1.20
```

#### 4. 启动服务

```bash
# 进入项目目录
cd code-sand-box

# 构建并启动
docker compose up -d --build

# 查看启动日志
docker compose logs -f sandbox
```

#### 5. 验证部署

```bash
# 健康检查
curl http://localhost:6060/sandbox/api/health/ping
# 预期返回: pong

# 查看支持的语言
curl http://localhost:6060/sandbox/api/execute/languages
```

---

### 方式二：Docker 手动部署

**适用场景**: 不想使用 Docker Compose，或需要更灵活的配置

**部署步骤:**

#### 1-3. 同上（安装 Docker、创建目录、拉取镜像）

#### 4. 构建镜像

```bash
cd code-sand-box
docker build -t code-sandbox:latest .
```

#### 5. 运行容器

```bash
docker run -d \
  --name code-sandbox \
  --user root \
  -p 6060:6060 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /var/lib/sandbox-work:/var/lib/sandbox-work \
  -v /var/lib/sandbox-inputs:/var/lib/sandbox-inputs \
  --restart unless-stopped \
  code-sandbox:latest
```

#### 6. 查看日志

```bash
docker logs -f code-sandbox
```

---

### 方式三：宿主机直接部署（不推荐）

**适用场景**: 开发环境测试，或无法使用 Docker 的情况

**前置要求:**
- JDK 21+
- Maven 3.9+
- Docker（用于执行用户代码）

**部署步骤:**

#### 1. 编译项目

```bash
cd code-sand-box
mvn clean package -DskipTests
```

#### 2. 创建目录

```bash
sudo mkdir -p /var/lib/sandbox-work
sudo chmod 777 /var/lib/sandbox-work

sudo mkdir -p /var/lib/sandbox-inputs
sudo chmod 755 /var/lib/sandbox-inputs
```

#### 3. 预拉取语言镜像

```bash
docker pull gcc:11
docker pull eclipse-temurin:8-jdk-alpine
docker pull eclipse-temurin:17-jdk-alpine
docker pull python:3.10
docker pull golang:1.20
```

#### 4. 启动应用

```bash
java -jar target/code-sand-box-*.jar \
  --server.port=6060 \
  --sandbox.docker.host=unix:///var/run/docker.sock
```

或使用 systemd 管理：

```bash
# 创建 systemd 服务文件
sudo tee /etc/systemd/system/code-sandbox.service > /dev/null <<EOF
[Unit]
Description=Code Sandbox Service
After=docker.service
Requires=docker.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/code-sand-box
ExecStart=/usr/bin/java -jar /opt/code-sand-box/target/code-sand-box-*.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# 启动服务
sudo systemctl daemon-reload
sudo systemctl enable code-sandbox
sudo systemctl start code-sandbox

# 查看状态
sudo systemctl status code-sandbox
```

**注意事项:**
- ⚠️ 宿主机部署需要确保 Java 进程有权限访问 Docker Socket
- ⚠️ 建议使用 root 用户或将用户加入 docker 组：`sudo usermod -aG docker $USER`

---

### 部署方式对比

| 部署方式 | 难度 | 隔离性 | 维护性 | 推荐场景 |
|---------|------|--------|--------|----------|
| Docker Compose | ⭐ 简单 | ⭐⭐⭐ 高 | ⭐⭐⭐ 好 | 生产环境（推荐） |
| Docker 手动 | ⭐⭐ 中等 | ⭐⭐⭐ 高 | ⭐⭐ 中等 | 需要灵活配置 |
| 宿主机部署 | ⭐⭐⭐ 复杂 | ⭐ 低 | ⭐ 差 | 开发测试 |

## 📁 目录结构说明

```
宿主机目录结构:
├── /var/lib/sandbox-work/      # 沙箱工作目录
│   └── exec-{requestId}/       # 每次执行的临时目录（自动清理）
│       ├── Main.java           # 源代码文件
│       └── Main.class          # 编译产物
│
└── /var/lib/sandbox-inputs/    # 输入数据缓存目录
    └── {questionId}/           # 按题目 ID 组织
        └── {hash}.txt          # 缓存的输入文件
```

### 目录权限要求

| 目录 | 权限 | 说明 |
|------|------|------|
| `/var/lib/sandbox-work` | 777 | 容器内 nobody 用户需要写入编译产物 |
| `/var/lib/sandbox-inputs` | 755 | 仅沙箱服务需要读写 |

### Docker Socket 挂载原理

**容器如何访问宿主机 Docker？**

本项目采用 **Docker-in-Docker (DinD)** 的 **Socket 挂载方案**：

```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
```

**工作原理：**
1. 沙箱服务容器挂载宿主机的 Docker Socket（`/var/run/docker.sock`）
2. 容器内的应用通过这个 Socket 与**宿主机的 Docker 守护进程**通信
3. 创建的执行容器是**兄弟容器**（sibling containers），运行在宿主机而非嵌套容器内
4. 所有容器共享宿主机的内核，性能接近原生

**优点：**
- ✅ 无需真正的 Docker-in-Docker，性能更好
- ✅ 资源隔离更彻底（执行容器独立运行）
- ✅ 避免嵌套虚拟化的复杂性

**注意事项：**
- ⚠️ 沙箱容器拥有宿主机 Docker 的**完全控制权**
- ⚠️ 工作目录路径必须在容器内外保持一致（如 `/var/lib/sandbox-work`）
- ⚠️ 生产环境建议限制沙箱容器的权限和网络访问

## ⚙️ 配置说明

### 核心配置项 (`application.yml`)

```yaml
sandbox:
  execution:
    work-dir: /var/lib/sandbox-work      # 工作目录（必须与宿主机一致）
    compile-timeout: 30                   # 编译超时（秒）
    run-timeout: 10                       # 运行超时（秒）
    memory-limit: 256                     # 内存限制（MB）
    output-limit: 65536                   # 输出限制（字节）
    enable-code-scan: true                # 启用危险代码扫描

  input-data:
    storage-dir: /var/lib/sandbox-inputs  # 输入数据目录（必须与宿主机一致）
```

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker 连接地址 |
| `SERVER_PORT` | `6060` | 服务端口 |

## 🔍 常用命令

```bash
# 启动服务
docker-compose up -d

# 停止服务
docker-compose down

# 查看日志
docker-compose logs -f sandbox

# 重新构建
docker-compose up -d --build

# 清理悬空镜像
docker image prune -f

# 清理工作目录（如果有残留）
sudo rm -rf /var/lib/sandbox-work/exec-*
```

## 🧪 API 测试

### 执行代码

```bash
curl -X POST http://localhost:6060/sandbox/api/execute \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "test-001",
    "code": "print(sum(map(int, input().split())))",
    "language": "python3",
    "input": "10 20",
    "timeLimit": 1000,
    "memoryLimit": 256
}'
```

### 批量执行（多测试用例）

```bash
curl -X POST http://localhost:6060/sandbox/api/execute/batch \
  -H "Content-Type: application/json" \
  -d '{
    "requestId": "test-002",
    "code": "#include <stdio.h>\nint main() { int a,b; scanf(\"%d%d\",&a,&b); printf(\"%d\",a+b); return 0; }",
    "language": "c",
    "inputList": ["1 2", "10 20", "100 200"],
    "timeLimit": 1000,
    "memoryLimit": 256
}'
```

## 🔧 故障排查

### 常见问题

**1. 容器无法启动**
```bash
# 检查 Docker 是否运行
sudo systemctl status docker

# 检查端口占用
sudo lsof -i :6060
```

**2. 代码执行超时**
```bash
# 检查语言镜像是否存在
docker images | grep -E "gcc|temurin|python|golang"

# 如果没有，重新拉取
docker pull gcc:11
```

**3. 权限问题**
```bash
# 检查目录权限
ls -la /var/lib/sandbox-work
ls -la /var/lib/sandbox-inputs

# 修复权限
sudo chmod 777 /var/lib/sandbox-work
```

**4. 编译产物无法写入**
```bash
# 确保工作目录对所有用户可写
sudo chmod -R 777 /var/lib/sandbox-work
```

## 📖 更多文档

- [架构设计](docs/01-ARCHITECTURE.md)
- [API 设计](docs/02-API-DESIGN.md)
- [Docker 执行器](docs/03-DOCKER-EXECUTOR.md)
- [语言支持](docs/04-LANGUAGE-SUPPORT.md)
- [安全机制](docs/05-SECURITY.md)
- [Ubuntu 部署指南](docs/DEPLOY-UBUNTU.md)

## 📄 许可证

MIT License
