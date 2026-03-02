# 沙箱镜像构建总结文档

## 1. 简介
本文档总结了本项目中代码沙箱运行时环境的构建脚本及相关镜像信息。该构建系统旨在为各类代码执行上游系统提供安全、隔离、一致的执行环境。

## 2. 构建脚本说明

### 2.1 脚本位置
路径：`sandbox-images/build.sh`

### 2.2 功能
该 Shell 脚本用于自动化构建所有支持编程语言的 Docker 镜像。脚本包含以下步骤：
1.  环境检查（Docker 是否安装）。
2.  依次构建 GCC (C/C++)、Java 8、Java 17、Python 3 镜像。
3.  输出构建结果及当前存在的镜像列表。

### 2.3 使用方法
**前提条件**：
*   操作系统：Ubuntu / Linux
*   依赖：Docker Engine
*   权限：当前用户需有执行 `docker` 命令的权限（通常需要 `sudo` 或在 `docker` 用户组中）。

**执行命令**：
```bash
# 进入脚本所在目录
cd sandbox-images

# 添加执行权限
chmod +x build.sh

# 运行脚本
./build.sh
```

## 3. 镜像清单

脚本将构建以下 Docker 镜像，所有镜像均包含 `sandbox` 用户（UID=1000）及标准化的 `/sandbox/workspace` 工作目录。

| 镜像名称 | 标签 (Tag) | 基础镜像 | 支持语言 | 关键工具 |
| :--- | :--- | :--- | :--- | :--- |
| `sandbox-gcc` | `latest` | `alpine:3.18` | C, C++ | `gcc`, `g++`, `make` |
| `sandbox-java8` | `latest` | `eclipse-temurin:8-jdk-alpine` | Java 8 | `javac`, `java` |
| `sandbox-java17` | `latest` | `eclipse-temurin:17-jdk-alpine` | Java 17 | `javac`, `java` |
| `sandbox-python` | `latest` | `python:3.10-alpine` | Python 3 | `python3` |

## 4. 目录结构参考

```text
sandbox-images/
├── base/                   # 基础文档（无镜像）
├── gcc/
│   └── Dockerfile          # C/C++ 镜像定义
├── java/
│   ├── Dockerfile.java8    # Java 8 镜像定义
│   └── Dockerfile.java17   # Java 17 镜像定义
├── python/
│   └── Dockerfile          # Python 镜像定义
├── build.sh                # 本构建脚本
└── BUILD_SUMMARY.md        # 本文档
```

## 5. 维护与扩展
*   **添加新语言**：
    1.  在 `sandbox-images/` 下创建新目录（如 `go/`）。
    2.  编写 `Dockerfile`，确保包含 `sandbox` 用户创建逻辑。
    3.  修改 `build.sh` 添加新的构建步骤。
    4.  更新 Java 代码中的 `LanguageStrategy` 以使用新镜像。

*   **更新基础镜像**：
    *   直接修改对应目录下的 `Dockerfile` 中的 `FROM` 指令。
