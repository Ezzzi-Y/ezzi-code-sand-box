#!/bin/bash
# ============================================================
# 沙箱运行时镜像构建脚本
# ============================================================
# 使用方法:
#   ./build.sh           # 构建所有镜像
#   ./build.sh gcc       # 仅构建 GCC 镜像
#   ./build.sh java      # 构建 Java 8 和 Java 17 镜像
#   ./build.sh python    # 仅构建 Python 镜像
#   ./build.sh golang    # 仅构建 Golang 镜像
# ============================================================

set -e

# 镜像名称前缀
PREFIX=${PREFIX:-"sandbox"}
TAG=${TAG:-"latest"}

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 构建单个镜像
build_image() {
    local name=$1
    local dockerfile=$2
    local context=$3
    
    local full_name="${PREFIX}-${name}:${TAG}"
    
    log_info "构建镜像: $full_name"
    log_info "Dockerfile: $dockerfile"
    
    docker build -t "$full_name" -f "$dockerfile" "$context"
    
    if [ $? -eq 0 ]; then
        log_info "✓ 镜像构建成功: $full_name"
    else
        log_error "✗ 镜像构建失败: $full_name"
        exit 1
    fi
    echo ""
}

# 验证镜像
verify_image() {
    local name=$1
    local full_name="${PREFIX}-${name}:${TAG}"
    
    log_info "验证镜像: $full_name"
    
    # 检查用户
    local user=$(docker run --rm "$full_name" whoami 2>/dev/null)
    if [ "$user" = "sandbox" ]; then
        log_info "✓ 用户验证通过: $user"
    else
        log_error "✗ 用户验证失败: 期望 sandbox, 实际 $user"
        return 1
    fi
    
    # 检查工作目录
    local pwd=$(docker run --rm "$full_name" pwd 2>/dev/null)
    if [ "$pwd" = "/sandbox/workspace" ]; then
        log_info "✓ 工作目录验证通过: $pwd"
    else
        log_error "✗ 工作目录验证失败: 期望 /sandbox/workspace, 实际 $pwd"
        return 1
    fi
    
    # 检查写权限
    docker run --rm "$full_name" sh -c "touch /sandbox/workspace/test.txt && rm /sandbox/workspace/test.txt" 2>/dev/null
    if [ $? -eq 0 ]; then
        log_info "✓ 写权限验证通过"
    else
        log_error "✗ 写权限验证失败"
        return 1
    fi
    
    log_info "✓ 镜像验证全部通过: $full_name"
    echo ""
}

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 构建函数
build_gcc() {
    build_image "gcc" "$SCRIPT_DIR/gcc/Dockerfile" "$SCRIPT_DIR/gcc"
    verify_image "gcc"
}

build_java() {
    build_image "java8" "$SCRIPT_DIR/java/Dockerfile.java8" "$SCRIPT_DIR/java"
    verify_image "java8"
    
    build_image "java17" "$SCRIPT_DIR/java/Dockerfile.java17" "$SCRIPT_DIR/java"
    verify_image "java17"
}

build_python() {
    build_image "python" "$SCRIPT_DIR/python/Dockerfile" "$SCRIPT_DIR/python"
    verify_image "python"
}

build_golang() {
    build_image "golang" "$SCRIPT_DIR/golang/Dockerfile" "$SCRIPT_DIR/golang"
    verify_image "golang"
}

build_all() {
    log_info "========== 构建所有沙箱镜像 =========="
    echo ""
    build_gcc
    build_java
    build_python
    build_golang
    log_info "========== 所有镜像构建完成 =========="
}

# 主逻辑
case "${1:-all}" in
    gcc)
        build_gcc
        ;;
    java)
        build_java
        ;;
    python)
        build_python
        ;;
    golang|go)
        build_golang
        ;;
    all)
        build_all
        ;;
    verify)
        log_info "验证所有镜像..."
        verify_image "gcc"
        verify_image "java8"
        verify_image "java17"
        verify_image "python"
        verify_image "golang"
        ;;
    *)
        echo "用法: $0 [gcc|java|python|golang|all|verify]"
        echo ""
        echo "命令:"
        echo "  gcc      构建 GCC 11 (C/C++) 镜像"
        echo "  java     构建 Java 8 和 Java 17 镜像"
        echo "  python   构建 Python 3.10 镜像"
        echo "  golang   构建 Golang 1.20 镜像"
        echo "  all      构建所有镜像 (默认)"
        echo "  verify   验证所有镜像"
        echo ""
        echo "环境变量:"
        echo "  PREFIX   镜像名称前缀 (默认: sandbox)"
        echo "  TAG      镜像标签 (默认: latest)"
        exit 1
        ;;
esac
