# ============================================================
# 沙箱基础镜像 - 提供通用的沙箱用户模型
# ============================================================
# 所有语言运行时镜像都基于此模型：
# 1. 创建 sandbox 用户 (uid=1000)
# 2. 创建 /sandbox/workspace 工作目录
# 3. 默认以 sandbox 用户运行
# ============================================================

# 此文件仅作为文档参考，各语言镜像直接继承官方镜像并注入沙箱模型

# 通用沙箱模型模板（各语言 Dockerfile 需复制以下内容）：
#
# # 创建沙箱用户和用户组 (uid=1000, gid=1000)
# RUN addgroup -g 1000 sandbox && \
#     adduser -D -u 1000 -G sandbox sandbox
#
# # 创建沙箱工作目录结构
# RUN mkdir -p /sandbox/workspace && \
#     chown -R sandbox:sandbox /sandbox
#
# # 设置默认工作目录
# WORKDIR /sandbox/workspace
#
# # 默认以 sandbox 用户运行
# USER sandbox
#
# # 保持容器运行
# CMD ["sleep", "infinity"]
