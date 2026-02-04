# ============================================================
# Golang 1.20 沙箱运行时镜像
# ============================================================
# 基于 golang:1.20-alpine，注入沙箱用户模型
# 用于编译和执行 Go 代码
# ============================================================

FROM golang:1.20-alpine

LABEL maintainer="ezzziy"
LABEL description="Golang 1.20 Sandbox Runtime for OJ Code Execution"
LABEL version="1.0"

# 安装必要工具
# 注意: Alpine 的 time 命令由 busybox 内置提供，无需单独安装
RUN apk add --no-cache \
    bash \
    coreutils

# 创建沙箱用户和用户组 (uid=1000, gid=1000)
RUN addgroup -g 1000 sandbox && \
    adduser -D -u 1000 -G sandbox sandbox

# 创建沙箱工作目录结构
# 权限 1777 (sticky bit + rwxrwxrwx) 确保任何用户都能创建子目录
RUN mkdir -p /sandbox/workspace && \
    chown sandbox:sandbox /sandbox && \
    chmod 1777 /sandbox/workspace

# 设置 Go 环境变量，允许 sandbox 用户编译
# GOCACHE 和 GOPATH 设置在 sandbox 可写目录内
ENV GOCACHE=/sandbox/.cache/go-build
ENV GOPATH=/sandbox/go
ENV CGO_ENABLED=0

# 预创建 Go 缓存目录
RUN mkdir -p /sandbox/.cache/go-build /sandbox/go && \
    chown -R sandbox:sandbox /sandbox

# 设置默认工作目录
WORKDIR /sandbox/workspace

# 重要：保持 root 身份启动，确保容器稳定运行
# 实际的沙箱隔离在 Java 执行 docker exec 时通过 --user sandbox 指定
USER root

# 保持容器运行（Alpine busybox 的 sleep 不支持 infinity）
CMD ["tail", "-f", "/dev/null"]
