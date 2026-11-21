# 团队编排：默认原生模式与可选 Paseo 模式

## 默认：直接使用 AntiGem 已安装的环境

汉化版侧栏和聊天顶部的「团队编排」打开 **Jarvis 原生团队管理会话**，优先返回已有岗位的管理会话。首次使用会创建 Jarvis 文本会话（需要在设置中启用 Jarvis 和至少一个 Agent）。

原生模式使用 AntiGem 已有的 Claude Code、Codex、OpenCode、agy 后端、账户和项目环境。**不要求执行 `paseo-team install`、安装第二套 Node.js、重新登录模型、打开额外 Web UI 或运行额外 Paseo Daemon。** 这是将原先需要的分层岗位、子 Agent 委派、消息回传等能力接入 Jarvis 的实现；不是完整重写或逐行移植所有上游 Paseo 功能。

岗位在 Jarvis 对话内通过 `jarvis_team_create` 配置：可设置 Agent、模型、推理等级、项目和职责。使用 `child_roles` 将已有岗位授权给管理岗，再用 `jarvis_task_start(team=...,goal=...)` 创建独立任务。所有委派都由 AntiGem 原生会话管理和原有 MCP 工具运行。支持多级角色配置；真实设备上的多级委派、权限请求与结果回传仍需验证。

详见 [原生分层编排设计](hierarchical-agent-orchestration.md)。

## 可选：独立的原版 Paseo 服务端（非默认入口）

原先集成的 `scripts/paseo-team.sh` 作为兼容/实验工具保留。只有明确希望使用 **Paseo 原版 Web UI、原版 Daemon、Paseo 特有功能** 的用户，才需在 AntiGem 本地终端执行：

```bash
paseo-team doctor
paseo-team install
paseo-team providers --write
paseo-team password
paseo-team start
paseo-team verify
```

这条实验路线会通过已有 Node/npm 单独安装官方 `@getpaseo/cli@0.17.0-beta.1` 及其 npm 依赖（包括 server 和 Web UI），但不会重新安装 Claude、Codex、OpenCode 或 agy。安装位置 `~/.local/share/antigem-paseo-team/runtimes/<版本>`，`current` 链接指向已选版本，数据独立存放在 `state`。独立 Web 界面监听 `127.0.0.1:6799`，Relay 关闭、需密码。

管理命令：`paseo-team status`、`stop`、`logs`、`versions`、`update <版本>`、`rollback`、`check`。切换版本前先停服。PTY 和原版 Unix socket 对 Android 的兼容性仍未实机验收；PTY 失败不应拦住核心编排，但终端页签可能不可用。仅在确实需要时、停服后执行可回滚的 `patch-terminal`。

## 共存原则与验证边界

原生 Jarvis 团队与可选原版 Paseo 服务分别管理会话与任务，不应让两个调度器并发修改同一工作目录。它们可共享本机 CLI 与账户，却**不共享任务数据库或 Agent 会话所有权**。无需把 Jarvis 和 Paseo 两套编排器同时启动。

Linux CI 的原版 Paseo 安装、认证和终端测试，不能代替 Android 真机测试。原生团队编排的单元测试也不能代替真实模型端到端测试。正式验收须确认同一手机上由总控创建组长、组长创建执行者、完整收集结果、处理中断与重启。