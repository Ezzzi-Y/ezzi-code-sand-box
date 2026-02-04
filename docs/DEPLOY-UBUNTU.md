# OJ Code Sandbox - Ubuntu 22.04 部署指南

## 📋 系统要求

- **操作系统**: Ubuntu 22.04 LTS
- **内存**: 最低 4GB，推荐 8GB+
- **磁盘**: 最低 20GB 可用空间
- **CPU**: 2 核心以上

## 🚀 快速部署

### 方式一：使用部署脚本（推荐）

```bash
# 1. 上传项目到服务器
scp -r code-sand-box user@server:/opt/

# 2. 进入项目目录
cd /opt/code-sand-box

# 3. 运行部署脚本
chmod +x scripts/deploy-ubuntu.sh
sudo ./scripts/deploy-ubuntu.sh

# 4. 启动沙箱服务
docker-compose up -d
```

### 方式二：手动部署

#### 1. 安装 Docker

```bash
# 更新系统
sudo apt update && sudo apt upgrade -y

# 安装依赖
sudo apt install -y apt-transport-https ca-certificates curl gnupg lsb-release

# 添加 Docker GPG 密钥
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

# 添加仓库
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# 安装
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 启动
sudo systemctl enable docker
sudo systemctl start docker

# 添加当前用户到 docker 组
sudo usermod -aG docker $USER
newgrp docker
```

#### 2. 预拉取镜像

```bash
docker pull gcc:11
docker pull openjdk:8-jdk-slim
docker pull openjdk:11-jdk-slim
docker pull python:3.10-slim
docker pull golang:1.20-alpine
docker pull minio/minio:latest
```

#### 3. 创建目录

```bash
sudo mkdir -p /opt/sandbox/data
sudo mkdir -p /opt/sandbox/logs
sudo mkdir -p /tmp/sandbox
sudo chmod 777 /tmp/sandbox
```

#### 4. 启动服务

```bash
cd /opt/code-sand-box
docker-compose up -d
```

## 🔧 配置说明

### Docker 配置优化

编辑 `/etc/docker/daemon.json`:

```json
{
    "log-driver": "json-file",
    "log-opts": {
        "max-size": "10m",
        "max-file": "3"
    },
    "storage-driver": "overlay2",
    "default-ulimits": {
        "nofile": {
            "Name": "nofile",
            "Hard": 65536,
            "Soft": 65536
        }
    }
}
```

重启 Docker: `sudo systemctl restart docker`

### 环境变量

可以通过环境变量覆盖默认配置：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker 连接地址 |
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO 地址 |
| `MINIO_ACCESS_KEY` | `minioadmin` | MinIO 访问密钥 |
| `MINIO_SECRET_KEY` | `minioadmin` | MinIO 密钥 |

## 📡 验证部署

```bash
# 检查服务状态
docker-compose ps

# 健康检查
curl http://localhost:6060/sandbox/api/health/ping

# 查看详细状态
curl http://localhost:6060/sandbox/api/health

# 查看支持的语言
curl http://localhost:6060/sandbox/api/execute/languages

# 查看日志
docker-compose logs -f sandbox
```

## 🧪 测试执行

```bash
# 测试 Python 代码执行
curl -X POST http://localhost:6060/sandbox/api/execute \
  -H "Content-Type: application/json" \
  -d '{
    "submitId": 1,
    "questionId": 1,
    "language": "python3",
    "code": "print(input())",
    "inputList": ["Hello World"],
    "expectedOutputList": ["Hello World"]
  }'
```

## 🔒 安全建议

1. **防火墙配置**: 仅开放必要端口
   ```bash
   sudo ufw allow 6060/tcp  # 沙箱 API
   sudo ufw allow 9000/tcp  # MinIO API (内网)
   sudo ufw allow 9001/tcp  # MinIO Console (可选)
   ```

2. **修改默认密码**: 生产环境务必修改 MinIO 密码

3. **限制 Docker**: 沙箱服务已内置安全限制，但建议定期清理僵尸容器
   ```bash
   # 清理停止的容器
   docker container prune -f
   
   # 清理未使用的镜像
   docker image prune -f
   ```

4. **日志监控**: 定期检查异常执行
   ```bash
   docker-compose logs sandbox | grep -i "error\|危险"
   ```

## 🛠 常见问题

### Q: Docker 权限问题
```bash
sudo usermod -aG docker $USER
# 重新登录或执行
newgrp docker
```

### Q: 端口被占用
```bash
# 查看端口占用
sudo lsof -i :6060
# 修改 docker-compose.yml 中的端口映射
```

### Q: 镜像拉取慢
配置 Docker 镜像加速：
```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<-'EOF'
{
    "registry-mirrors": ["https://mirror.ccs.tencentyun.com"]
}
EOF
sudo systemctl restart docker
```

### Q: 内存不足
```bash
# 增加 swap
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

## 📊 性能调优

生产环境建议修改 `application.yml`：

```yaml
sandbox:
  execution:
    max-concurrent-containers: 20  # 根据 CPU 核心数调整
    memory-limit: 512              # 增加内存限制
  cache:
    max-size: 5000                 # 增加缓存容量
    expire-minutes: 60             # 延长缓存时间
```
