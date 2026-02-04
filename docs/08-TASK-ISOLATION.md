# 任务隔离与文件系统架构设计

## 1. 概述

本文档描述代码沙箱服务的任务隔离机制和文件系统架构设计，确保：
- 任务间相互隔离，互不干扰
- 公有输入数据可被多任务复用
- 权限天然正确，无需 Java 层权限修补逻辑

## 2. 沙箱镜像架构

### 2.1 镜像命名规范

| 语言 | 镜像名称 | 基础镜像 |
|------|----------|----------|
| C/C++ | `sandbox-gcc:latest` | `gcc:11-alpine` |
| Java 8 | `sandbox-java8:latest` | `eclipse-temurin:8-jdk-alpine` |
| Java 17 | `sandbox-java17:latest` | `eclipse-temurin:17-jdk-alpine` |
| Python 3 | `sandbox-python:latest` | `python:3.10-alpine` |
| Go | `sandbox-golang:latest` | `golang:1.20-alpine` |

### 2.2 沙箱镜像通用特性

所有沙箱镜像都包含：
1. `sandbox` 用户 (uid=1000, gid=1000)
2. `/sandbox/workspace` 可写工作目录
3. 默认 `USER sandbox`
4. 默认 `WORKDIR /sandbox/workspace`

## 3. 文件系统架构

### 3.1 语义区域划分

```
容器内文件系统
├── /sandbox/                     # 沙箱根目录
│   └── workspace/                # 统一工作目录（由 sandbox 用户拥有）
│       ├── job-{requestId1}/     # 任务1独立工作目录
│       │   ├── Main.java         # 用户源代码
│       │   ├── Main.class        # 编译产物
│       │   └── ...               # 运行时临时文件
│       └── job-{requestId2}/     # 任务2独立工作目录
│           └── ...
│
└── /sandbox/inputs/              # 公有输入数据目录（只读挂载）
    ├── problem_1/
    │   ├── 1.in
    │   └── 2.in
    └── problem_2/
        └── ...
```

### 3.2 目录职责

| 路径 | 权限 | 生命周期 | 用途 |
|------|------|----------|------|
| `/sandbox/workspace/` | sandbox 用户拥有 | 容器生命周期 | 统一工作目录 |
| `/sandbox/workspace/{jobId}/` | sandbox 用户创建 | 单次任务 | 独立任务执行空间 |
| `/sandbox/inputs/` | 只读挂载 | 长期存在 | 公有输入数据源 |

## 4. 用户模型

### 4.1 统一沙箱用户

所有沙箱镜像内置 `sandbox` 用户（uid=1000），容器默认以此用户运行：

- 目录创建
- 文件写入
- 代码编译
- 程序运行
- 资源清理

这避免了：
- 跨用户权限问题
- 需要使用 `chmod 777` 等宽松权限
- 在 Java 代码中做权限修补

### 4.2 镜像构建示例

```dockerfile
# 示例：Java 17 沙箱镜像
FROM eclipse-temurin:17-jdk-alpine

# 创建沙箱用户 (uid=1000, gid=1000)
RUN addgroup -g 1000 sandbox && \
    adduser -D -u 1000 -G sandbox sandbox

# 创建沙箱目录结构
RUN mkdir -p /sandbox/workspace && \
    chown -R sandbox:sandbox /sandbox

WORKDIR /sandbox/workspace
USER sandbox
CMD ["sleep", "infinity"]
```

## 5. 任务执行流程

### 5.1 容器池模式

```
1. 从容器池获取容器（已处于 running 状态）
       ↓
2. 获取任务工作目录路径：/sandbox/workspace/job-{requestId}/
       ↓
3. 写入源代码（自动创建目录）
       ↓
4. 在任务目录内编译（如需要）
       ↓
5. 在任务目录内运行测试用例
       ↓
6. 清理任务目录
       ↓
7. 归还容器到池中
```

**关键特性：**
- 容器创建后立即启动，纳入池中时已处于 running 状态
- 无运行时目录初始化步骤，镜像已内置完整环境
- 所有 exec 操作仅在 running 容器中执行

### 5.2 容器生命周期

```
create → start → [ready in pool] → acquire → execute → release → [ready in pool] → ...
                                                                       ↓
                                                            (使用次数达上限)
                                                                       ↓
                                                               remove → create
```

### 5.3 命令执行工作目录

所有编译和运行命令都在任务目录内执行：

```bash
# 编译命令
cd /sandbox/workspace/job-{requestId} && javac -encoding UTF-8 Main.java

# 运行命令
cd /sandbox/workspace/job-{requestId} && java Main
```

## 6. 权限模型

### 6.1 权限天然正确原则

| 操作 | 执行用户 | 目标路径 | 权限保证 |
|------|----------|----------|----------|
| 创建任务目录 | sandbox | /sandbox/workspace/{jobId} | 镜像预设，天然可写 |
| 写入源代码 | sandbox | /sandbox/workspace/{jobId}/*.java | 用户自己创建，天然可写 |
| 编译 | sandbox | /sandbox/workspace/{jobId}/ | 在自己的目录内操作 |
| 运行 | sandbox | /sandbox/workspace/{jobId}/ | 在自己的目录内操作 |
| 清理 | sandbox | /sandbox/workspace/{jobId} | 用户自己创建，天然可删 |

### 6.2 已移除的运行时初始化逻辑

重构后彻底删除了所有运行时环境准备代码：

```java
// ❌ 已删除 - 不再需要运行时初始化目录
initSandboxDirectory(containerId)
mkdir -p /sandbox/workspace/tasks

// ❌ 已删除 - 不再显式指定用户
.withUser("sandbox")

// ❌ 已删除 - 不再需要权限修补
chmod 777 /sandbox
dir.toFile().setReadable(true, false)
dir.toFile().setWritable(true, false)
```

**新架构原则：镜像即执行环境**
- 沙箱镜像构建时已内置完整环境（用户、目录、权限）
- 容器启动后立即可用，无需中间准备步骤
- 所有 exec 操作仅在 running 状态容器中执行

## 7. 安全性保证

### 7.1 公有输入数据保护

- `/sandbox/inputs/` 以只读模式挂载（`AccessMode.ro`）
- 任务无法写入或修改公有输入数据
- 任务只能从 `/sandbox/inputs/` 读取数据

### 7.2 任务隔离

- 每个任务在独立子目录中执行
- 任务间不共享工作空间
- 任务完成后立即清理，不留痕迹

### 7.3 容器安全

- 禁用网络访问
- 删除所有 Linux capabilities
- 设置资源限制（CPU、内存、进程数）
- 使用非特权 sandbox 用户运行

## 8. 部署说明

### 8.1 构建沙箱镜像

```bash
# 进入项目目录
cd code-sand-box

# 构建所有沙箱镜像
cd sandbox-images && ./build.sh all

# 或使用 docker-compose
docker-compose build
```

### 8.2 验证镜像

```bash
# 验证用户
docker run --rm sandbox-java17:latest whoami
# 输出: sandbox

# 验证工作目录
docker run --rm sandbox-java17:latest pwd
# 输出: /sandbox/workspace

# 验证写权限
docker run --rm sandbox-java17:latest sh -c "touch test.txt && ls test.txt && rm test.txt"
# 输出: test.txt
```

### 8.3 相关配置项

```yaml
sandbox:
  input-data:
    # 公有输入数据存储目录
    storage-dir: /var/lib/sandbox-inputs
```

## 9. 最佳实践

### 9.1 镜像构建建议

1. 使用 Alpine 基础镜像减小体积
2. 预创建 `sandbox` 用户和目录结构
3. 安装必要的运行时，不安装不需要的工具

### 9.2 运维建议

1. 定期清理公有输入数据中的过期数据
2. 监控容器池状态和使用率
3. 设置合理的容器重建阈值（默认 100 次使用后重建）
