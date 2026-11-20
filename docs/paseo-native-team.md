# 原版 Paseo 团队接入（试验分支）

## 设计

保留 AntiGem / Jarvis 的既有多 Agent 功能，不修改其运行器。通过本地 Bash 新增一个独立的团队入口，安装并运行官方 npm 包 @getpaseo/cli，而不是重写 Paseo 的编排机制。

- npm 独立安装前缀：~/.local/share/antigem-paseo-team/runtime
- Paseo 独立数据目录：~/.local/share/antigem-paseo-team/state
- 本地监听：127.0.0.1:6799；Relay 默认关闭
- 初次启动启用原版编排工具注入与原版 Web UI；已有配置不覆盖
- 只有用户执行 install 才联网安装依赖；APK 只打包轻量 Bash 启动器
- 试验目标版本：0.17.0-beta.1（与审核时上游源码一致）；npm 发布可用性尚未核验

## 使用

汉化版聊天侧栏新增「Paseo 团队」入口。首次打开显示安装及启动命令，点击「本地终端」进入既有终端；服务实际就绪后点击「打开团队」，复用 AntiGem 内置浏览器。没有后台自动安装、没有替换 Jarvis，也没有重新开发 Paseo 的 Web UI。

在新版 AntiGem 本地 Bash 中执行（不是 SSH 终端）：

```bash
paseo-team install
paseo-team start
paseo-team status
paseo-team url
paseo-team cli --help
paseo-team stop
```

从 AntiGem 原有浏览器打开 http://127.0.0.1:6799（服务实际就绪且 Web UI 可用后）。也可直接在已签出的源码里运行 bash scripts/paseo-team.sh，避免先重新打 APK。没有自动登录、没有原 Jarvis 会话导入、没有将小说资料写入仓库。

## 并存边界

两者拥有不同的编排状态、会话管理权、命令入口和服务端口。Paseo 应创建自己的会话，Jarvis 创建自己的会话；不要把同一个现有会话同时交给两者管理。不需要禁用 Jarvis。

这不等于进程、安全或资源隔离。它们运行在同一 Android 应用环境，可能使用相同 CLI、认证文件、全局 MCP 配置、项目文件及模型额度。不要同时让两个编排器修改同一工作树。若 CLI 争用或全局 MCP 互相影响，优先复用独立工作区、使用提供方支持的独立配置，或串行运行。仅绑定 loopback 也不是认证：在正式使用前检查 Paseo 的密码设置及 Android 本地网络可访问性，不能把端口转发或公开。

## 验收

1. 真机验证 Node/npm、npm 包发布版本及 native 依赖（特别是 node-pty、sherpa-onnx-node）；安装失败保留原始错误，不使用 --ignore-scripts 假装成功。必要时评估 Termux 原生构建、glibc 或 Ubuntu proot。
2. 原版 Daemon 必须实际启动，状态确认 RPC 可达，Web UI 可访问。
3. 确认 provider discovery 和认证是否能够复用已有 CLI；从 Paseo 新建总控及下级组长，组长自行创建两名真实专家；核对实际会话 ID、模型及完成通知。
4. 验证 Jarvis 独立启动任务，双方不能互相接管会话；检查并发 CLI、MCP、工作目录、手机休眠与额度。
5. 用用户原有岗位配置做导入兼容测试。未通过以上步骤前，不宣称具备完整手机团队运行能力。

本分支提供原版运行入口、APK 打包接线及侧栏入口。尚未进行 Android 编译、npm 实际安装或 Agent 调用。参考：[Paseo CLI](https://paseo.sh/docs/cli)、[配置](https://paseo.sh/docs/configuration)、[编排](https://paseo.sh/docs/orchestration)。
