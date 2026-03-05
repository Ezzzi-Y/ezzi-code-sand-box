# Ezzi Code Sandbox 官网规划文档（Vercel 风格）

## 1. 文档目标

为 `ezzi-code-sand-box` 设计一个专门用于对外介绍的官方网站方案，强调：

- 项目定位清晰（独立部署的代码执行引擎）
- 技术可信度高（安全隔离、资源限制、容器池、输入缓存）
- 开发者易上手（API 清晰、部署简单、文档完整）
- 视觉风格现代简洁（参考 Vercel 的“极简 + 技术感”）

> 说明：本方案是“风格借鉴”，不是 UI 复制。保持品牌独立与内容原创。

---

## 2. 网站定位

### 2.1 网站一句话定位

一个面向开发者与技术团队的“代码执行沙箱引擎”官网，用最短路径说明：它是什么、为什么可靠、怎么接入。

### 2.2 目标用户

1. 平台开发者（在线评测、教学、练习系统）
2. 技术负责人 / 架构师（关注安全与稳定）
3. 贡献者与开源用户（关注部署、文档、贡献方式）

### 2.3 核心转化目标（CTA）

主目标：
- 查看文档（Architecture / API / Security）
- 快速部署（Docker Compose）

次目标：
- Star 项目
- 提交 Issue / PR

---

## 3. 信息架构（IA）

建议采用单页官网 + 外链文档的结构：

1. Home（首页）
2. Docs（跳转仓库 docs 目录）
3. API（跳转 API 设计文档）
4. Security（跳转安全文档）
5. GitHub（仓库）

页内导航锚点（首页）：
- Hero
- Why Ezzi Sandbox
- Core Features
- Architecture
- Security
- API Preview
- Deploy
- Use Cases
- Roadmap
- FAQ
- Final CTA

---

## 4. 页面内容规划（首页）

### 4.1 Hero（首屏）

目标：3 秒内传达项目价值。

建议文案：
- 标题：为你的平台提供安全、可控、可扩展的代码执行能力
- 副标题：基于 Docker 隔离的多语言代码沙箱，支持 C/C++/Java/Python，提供统一 API、资源限制与输入缓存能力。
- 按钮：
  - 立即部署
  - 阅读 API

首屏补充信息（短标签）：
- Java 21 / Spring Boot 3.5
- Docker Isolation
- Multi-language
- Apache-2.0

### 4.2 Why Ezzi Sandbox（价值主张）

用 3 张卡片表达差异化：

1. 安全优先
- 非 root 执行
- 禁网运行
- Capability 全裁剪
- 危险代码模式检测

2. 工程可用
- 统一 REST API
- 批量执行 + 输入数据 URL
- 健康检查与探针接口

3. 面向性能
- 容器池预热与复用
- 批量输入版本缓存
- 可配置并发与资源上限

### 4.3 Core Features（核心能力）

建议分组展示：

- 多语言支持：c / cpp11 / java8 / java17 / python3
- 执行模型：编译 + 运行分阶段、结构化结果输出
- 可观测性：执行状态、耗时、内存、退出码
- 配置化限制：timeLimit / memoryLimit / cpuLimit / processLimit

### 4.4 Architecture（架构）

用一张简化流程图（从请求到结果）：

Client -> Controller -> ExecutionService -> InputDataService/ContainerPool -> Docker Executor -> Result

并强调职责边界：
- 沙箱只负责“执行与隔离”，不负责判题逻辑和业务编排。

### 4.5 Security（安全机制）

建议作为重点模块（可深链到 Security 文档）：

- 容器隔离：网络禁用、资源限制、tmpfs、ulimit
- 权限控制：非 root 用户、no-new-privileges、cap drop all
- 代码检测：按语言策略定义危险模式
- 异常治理：编译/运行/超时/内存超限统一状态返回

### 4.6 API Preview（接口预览）

展示最小可用调用路径：

- POST /execute/single
- POST /execute/batch
- GET /execute/languages
- GET /health/ping

建议加入一个最简请求示例（只放核心字段）：
- language
- code
- input 或 inputDataUrl
- timeLimit / memoryLimit

### 4.7 Deploy（部署）

部署模块保持“3 步完成”：

1. 构建运行时镜像
2. docker compose 启动服务
3. health/ping 验证服务可用

并提供环境前置条件：
- Linux
- Docker 20.10+
- Docker Compose v2

### 4.8 Use Cases（场景）

建议列 4 个高频场景：

- 在线评测 OJ
- 编程教学平台
- AI 代码执行验证
- 面试与练习系统

### 4.9 Roadmap（路线图）

展示已实现与计划中能力：

- 已支持：多语言、容器池、输入缓存、安全扫描
- 规划中：
  - 更多语言（如 Go/Node.js）
  - 更细粒度的审计与监控
  - 集群化部署说明

### 4.10 FAQ

建议预置问题：

1. 为什么要独立部署沙箱服务？
2. 为什么默认禁网？
3. 如何控制超时和内存？
4. 批量输入缓存如何命中？
5. 如何扩展新语言？

### 4.11 Final CTA（页尾）

- 主按钮：5 分钟启动你的代码沙箱
- 次按钮：阅读完整文档
- 辅助链接：GitHub / License / Contribution

---

## 5. 视觉与交互规范（Vercel 风格借鉴）

### 5.1 视觉方向关键词

- 极简
- 高对比
- 大留白
- 网格化排版
- 轻量动效

### 5.2 UI 原则

- 首屏只保留一个核心信息和两个 CTA
- 每个区块只表达一个主题
- 文案短句化，避免大段叙述
- 所有技术点“可证据化”（可跳转到对应文档）

### 5.3 交互建议

- Header 吸顶 + 锚点高亮
- 首屏按钮与关键卡片支持轻微 hover 反馈
- 图表/流程图采用渐进显示（避免重动画）
- 首屏到部署区块滚动路径顺畅，减少认知跳转

---

## 6. 文案语气与品牌表达

建议语气：

- 面向工程师：专业、直接、少营销词
- 多用事实表达能力：
  - 支持语言列表
  - 限制项名称
  - API 名称
  - 安全策略关键词

避免：
- 绝对化承诺（如“100% 安全”）
- 无依据性能数字

---

## 7. SEO 与内容策略

### 7.1 页面基础 SEO

- title：Ezzi Code Sandbox | Docker-based Code Execution Engine
- description：Secure, isolated, multi-language code execution sandbox for OJ, education, and AI coding platforms.
- 结构化关键词：code sandbox, docker sandbox, online judge engine, secure code execution

### 7.2 推荐内容扩展（后续）

- Blog/Articles：
  - 如何设计安全代码沙箱
  - 批量执行缓存策略实践
  - 容器池对延迟的影响

---

## 8. 实施建议（MVP 优先）

### 8.1 第一阶段（1 周内）

- 完成单页官网（上述全部模块）
- 接入 GitHub 与 docs 链接
- 上线基础 SEO 与站点分析

### 8.2 第二阶段

- 加入英文版页面
- 增加案例详情页
- 增加版本发布与变更记录展示

---

## 9. 验收标准

上线时至少满足：

1. 访问首页 10 秒内可理解项目定位
2. 用户可在 3 次点击内进入部署或 API 文档
3. 关键能力（安全、性能、语言支持）都有对应证据入口
4. 移动端可读且 CTA 明确

---

## 10. 附录：建议首页文案骨架（可直接用于设计稿）

- Hero 标题：Secure Code Execution for Real Platforms
- Hero 副标题：Run untrusted code safely in isolated Docker containers with unified APIs and configurable limits.
- Section 标题：
  - Why Ezzi Sandbox
  - Built for Isolation and Control
  - API-first Integration
  - Deploy in Minutes
  - Start Building with Ezzi Code Sandbox

以上骨架可直接进入设计与前端开发，不依赖额外 PRD。
