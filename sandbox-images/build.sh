#!/bin/bash

# ==============================================================================
# 沙箱镜像构建脚本
# ==============================================================================
#
# 功能描述:
#   该脚本用于构建本项目所需的所有沙箱运行时环境镜像。
#   包括: GCC (C/C++), Java 8, Java 17, Python 3.10
#
# 使用前提:
#   1. 必须在 Ubuntu/Linux 环境下运行
#   2. 必须已安装 Docker 且当前用户有权限执行 docker 命令
#   3. 网络连接正常（需要从 Docker Hub 拉取基础镜像）
#
# 镜像列表:
#   - sandbox-gcc:latest    (用于 C/C++ 代码编译与运行)
#   - sandbox-java8:latest  (用于 Java 8 代码编译与运行)
#   - sandbox-java17:latest (用于 Java 17 代码编译与运行)
#   - sandbox-python:latest (用于 Python 3 代码运行)
#
# ==============================================================================

# 设置脚本在执行过程中遇到错误即退出
set -e

# 获取脚本所在目录的绝对路径
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# 切换到脚本所在目录，确保相对路径正确
cd "$SCRIPT_DIR"

echo "========================================================"
echo "开始构建沙箱环境镜像..."
echo "当前工作目录: $(pwd)"
echo "========================================================"

# 检查 Docker 是否安装
if ! command -v docker &> /dev/null; then
    echo "错误: 未找到 docker 命令。请先安装 Docker。"
    exit 1
fi

# ------------------------------------------------------------------------------
# 1. 构建 GCC 镜像
# ------------------------------------------------------------------------------
echo ""
echo "[1/4] 正在构建 GCC 镜像 (sandbox-gcc:latest)..."
echo "上下文路径: ./gcc"
echo "Dockerfile: ./gcc/Dockerfile"

if docker build -t sandbox-gcc:latest -f gcc/Dockerfile gcc/; then
    echo "SUCCESS: GCC 镜像构建成功"
else
    echo "ERROR: GCC 镜像构建失败"
    exit 1
fi

# ------------------------------------------------------------------------------
# 2. 构建 Java 8 镜像
# ------------------------------------------------------------------------------
echo ""
echo "[2/4] 正在构建 Java 8 镜像 (sandbox-java8:latest)..."
echo "上下文路径: ./java"
echo "Dockerfile: ./java/Dockerfile.java8"

if docker build -t sandbox-java8:latest -f java/Dockerfile.java8 java/; then
    echo "SUCCESS: Java 8 镜像构建成功"
else
    echo "ERROR: Java 8 镜像构建失败"
    exit 1
fi

# ------------------------------------------------------------------------------
# 3. 构建 Java 17 镜像
# ------------------------------------------------------------------------------
echo ""
echo "[3/4] 正在构建 Java 17 镜像 (sandbox-java17:latest)..."
echo "上下文路径: ./java"
echo "Dockerfile: ./java/Dockerfile.java17"

if docker build -t sandbox-java17:latest -f java/Dockerfile.java17 java/; then
    echo "SUCCESS: Java 17 镜像构建成功"
else
    echo "ERROR: Java 17 镜像构建失败"
    exit 1
fi

# ------------------------------------------------------------------------------
# 4. 构建 Python 镜像
# ------------------------------------------------------------------------------
echo ""
echo "[4/4] 正在构建 Python 镜像 (sandbox-python:latest)..."
echo "上下文路径: ./python"
echo "Dockerfile: ./python/Dockerfile"

if docker build -t sandbox-python:latest -f python/Dockerfile python/; then
    echo "SUCCESS: Python 镜像构建成功"
else
    echo "ERROR: Python 镜像构建失败"
    exit 1
fi

# ------------------------------------------------------------------------------
# 总结
# ------------------------------------------------------------------------------
echo ""
echo "========================================================"
echo "所有沙箱镜像构建完成！"
echo "========================================================"
echo "当前存在的沙箱镜像列表:"
docker images --format "table {{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.CreatedSince}}" | grep sandbox-

echo ""
echo "您可以运行 'docker images' 查看详细信息。"
