# ============================================================
# GCC 沙箱运行时镜像 (C/C++)
# ============================================================
# 基于 alpine，安装 GCC 编译工具链，注入沙箱用户模型
# 用于编译和执行 C/C++ 代码
# ============================================================

FROM alpine:3.18

LABEL maintainer="ezzziy"
LABEL description="GCC Sandbox Runtime for OJ Code Execution"
LABEL version="1.0"

# 安装 GCC 工具链和必要工具
# 注意: Alpine 的 time 命令由 busybox 内置提供，无需单独安装
RUN apk add --no-cache \
    gcc \
    g++ \
    musl-dev \
    libstdc++ \
    bash \
    coreutils

# 创建沙箱用户和用户组 (uid=1000, gid=1000)
# 使用固定 UID/GID 确保容器间一致性
RUN addgroup -g 1000 sandbox && \
    adduser -D -u 1000 -G sandbox sandbox

# 创建沙箱工作目录结构
# /sandbox/workspace : 任务工作目录，用户代码和编译产物存放于此
# 权限 1777 (sticky bit + rwxrwxrwx) 确保任何用户都能创建子目录
RUN mkdir -p /sandbox/workspace && \
    chown sandbox:sandbox /sandbox && \
    chmod 1777 /sandbox/workspace

# 设置默认工作目录
WORKDIR /sandbox/workspace

# 重要：保持 root 身份启动，确保容器稳定运行
# 实际的沙箱隔离在 Java 执行 docker exec 时通过 --user sandbox 指定
USER root

# 保持容器运行（由容器池管理器控制生命周期）
# 使用 tail -f /dev/null 因为 Alpine busybox 的 sleep 不支持 infinity
CMD ["tail", "-f", "/dev/null"]
