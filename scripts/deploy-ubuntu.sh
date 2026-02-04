#!/bin/bash
# OJ Code Sandbox 部署脚本 - Ubuntu 22.04
# 使用方法: chmod +x deploy-ubuntu.sh && ./deploy-ubuntu.sh

set -e

echo "=========================================="
echo "   OJ Code Sandbox 部署脚本 - Ubuntu 22.04"
echo "=========================================="

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查是否以 root 运行
check_root() {
    if [ "$EUID" -ne 0 ]; then
        echo -e "${YELLOW}建议使用 root 用户或 sudo 运行此脚本${NC}"
    fi
}

# 检查 Docker 是否安装
check_docker() {
    echo -e "\n${GREEN}[1/6] 检查 Docker 安装状态...${NC}"
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}Docker 未安装，开始安装...${NC}"
        install_docker
    else
        echo -e "${GREEN}Docker 已安装: $(docker --version)${NC}"
    fi
    
    # 检查 Docker 服务
    if ! systemctl is-active --quiet docker; then
        echo "启动 Docker 服务..."
        sudo systemctl start docker
    fi
}

# 安装 Docker
install_docker() {
    sudo apt update
    sudo apt install -y apt-transport-https ca-certificates curl gnupg lsb-release
    
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
    
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    
    sudo apt update
    sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
    
    sudo systemctl enable docker
    sudo systemctl start docker
    
    # 将当前用户加入 docker 组
    sudo usermod -aG docker $USER
    
    echo -e "${GREEN}Docker 安装完成${NC}"
}

# 拉取所需镜像
pull_images() {
    echo -e "\n${GREEN}[2/6] 拉取编程语言镜像...${NC}"
    
    images=(
        "gcc:11"
        "openjdk:8-jdk-slim"
        "openjdk:11-jdk-slim"
        "python:3.10-slim"
        "golang:1.20-alpine"
        "minio/minio:latest"
    )
    
    for image in "${images[@]}"; do
        echo -e "拉取 ${YELLOW}$image${NC}..."
        docker pull $image
    done
    
    echo -e "${GREEN}所有镜像拉取完成${NC}"
}

# 创建工作目录
create_directories() {
    echo -e "\n${GREEN}[3/6] 创建工作目录...${NC}"
    
    sudo mkdir -p /opt/sandbox/data
    sudo mkdir -p /opt/sandbox/logs
    sudo mkdir -p /tmp/sandbox
    sudo chmod 777 /tmp/sandbox
    
    echo -e "${GREEN}目录创建完成${NC}"
}

# 配置系统限制
configure_system() {
    echo -e "\n${GREEN}[4/6] 配置系统参数...${NC}"
    
    # 增加文件描述符限制
    if ! grep -q "sandbox" /etc/security/limits.conf; then
        echo "* soft nofile 65536" | sudo tee -a /etc/security/limits.conf
        echo "* hard nofile 65536" | sudo tee -a /etc/security/limits.conf
    fi
    
    # 配置 Docker daemon
    sudo mkdir -p /etc/docker
    cat <<EOF | sudo tee /etc/docker/daemon.json
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
EOF
    
    # 重启 Docker 应用配置
    sudo systemctl restart docker
    
    echo -e "${GREEN}系统配置完成${NC}"
}

# 启动 MinIO
start_minio() {
    echo -e "\n${GREEN}[5/6] 启动 MinIO 服务...${NC}"
    
    # 检查是否已运行
    if docker ps | grep -q sandbox-minio; then
        echo -e "${YELLOW}MinIO 已在运行${NC}"
        return
    fi
    
    # 删除旧容器（如果存在）
    docker rm -f sandbox-minio 2>/dev/null || true
    
    # 启动 MinIO
    docker run -d \
        --name sandbox-minio \
        --restart unless-stopped \
        -p 9000:9000 \
        -p 9001:9001 \
        -v /opt/sandbox/data:/data \
        -e MINIO_ROOT_USER=minioadmin \
        -e MINIO_ROOT_PASSWORD=minioadmin \
        minio/minio:latest server /data --console-address ":9001"
    
    echo -e "${GREEN}MinIO 启动完成${NC}"
    echo -e "  API: http://localhost:9000"
    echo -e "  Console: http://localhost:9001"
    echo -e "  用户名: minioadmin"
    echo -e "  密码: minioadmin"
}

# 显示部署信息
show_info() {
    echo -e "\n${GREEN}[6/6] 部署完成！${NC}"
    echo ""
    echo "=========================================="
    echo "  部署信息"
    echo "=========================================="
    echo ""
    echo "Docker 状态:"
    docker info --format '  版本: {{.ServerVersion}}'
    docker info --format '  容器数: {{.Containers}}'
    docker info --format '  镜像数: {{.Images}}'
    echo ""
    echo "已拉取的镜像:"
    docker images --format "  {{.Repository}}:{{.Tag}}" | grep -E "gcc|openjdk|python|golang|minio"
    echo ""
    echo "MinIO 服务:"
    echo "  API 地址: http://localhost:9000"
    echo "  控制台: http://localhost:9001"
    echo ""
    echo "下一步操作:"
    echo "  1. 构建沙箱服务: docker-compose build"
    echo "  2. 启动沙箱服务: docker-compose up -d"
    echo "  3. 查看日志: docker-compose logs -f sandbox"
    echo "  4. 健康检查: curl http://localhost:6060/sandbox/api/health/ping"
    echo ""
    echo "=========================================="
}

# 主函数
main() {
    check_root
    check_docker
    pull_images
    create_directories
    configure_system
    start_minio
    show_info
}

main "$@"
