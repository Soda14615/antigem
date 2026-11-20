#!/usr/bin/env bash
# Opt-in upstream Paseo runtime. No Jarvis hooks or provider session imports.
set -euo pipefail
umask 077
root="$HOME/.local/share/antigem-paseo-team"
team_home="$root/state"
runtime="$root/runtime-arm64-v2"
paseo="$runtime/node_modules/.bin/paseo"
export PATH="$runtime/node_modules/.bin:$PATH"
unset PASEO_HOST
export PASEO_HOME="$team_home"
port=6799
version=0.17.0-beta.1
pty_version=1.1.2
fail() { printf "%s\n" "$*" >&2; exit 1; }
usage() {
  cat <<EOF
Paseo 团队（独立运行，不接管 Jarvis 会话）
  paseo-team install        安装固定版本的原版 Paseo（需要联网）
  paseo-team password       设置团队服务访问密码
  paseo-team start          启动本地独立团队服务
  paseo-team status         查看团队服务状态
  paseo-team check          检查真实 CLI 和 PTY 运行能力
  paseo-team stop           停止团队服务
  paseo-team cli <args>     使用原版 Paseo 命令
  paseo-team url            显示 Web 团队管理地址
EOF
}
require_node() {
  command -v node >/dev/null && command -v npm >/dev/null || fail "缺少 Node.js/npm；请先在 AntiGem 终端安装 nodejs。"
}
require_paseo() {
  [[ -x "$paseo" && -f "$runtime/.verified-install" ]] || fail "尚未通过安装检查：请运行 paseo-team install。"
}
check_runtime() {
  node - "$runtime" <<\JS
const { createRequire } = require("node:module");
const path = require("node:path");
const cli = path.join(process.argv[2], "node_modules", "@getpaseo", "cli", "package.json");
const cliRequire = createRequire(cli);
const server = cliRequire.resolve("@getpaseo/server");
const load = createRequire(server);
const metadata = load("node-pty/package.json");
if (process.platform === "android" && metadata.name !== "@mmmbuto/node-pty-android-arm64") throw Error("Android PTY override not resolved: " + metadata.name);
const pty = load("node-pty");
let output = "";
const shell = path.join(process.env.PREFIX || "/usr", "bin", "bash");
const child = pty.spawn(shell, ["-c", "printf PASEO_PTY_OK"], { name: "xterm", cols: 80, rows: 24, cwd: process.env.HOME, env: process.env });
const timer = setTimeout(() => { child.kill(); console.error("Native PTY timed out"); process.exit(1); }, 10000);
child.onData(data => { output += data; });
child.onExit(({ exitCode }) => { clearTimeout(timer); if (exitCode !== 0 || !output.includes("PASEO_PTY_OK")) { console.error("Native PTY failed", exitCode, output); process.exit(1); } console.log("Native PTY passed:", metadata.name, metadata.version); });
JS
  "$paseo" --version
}
connection_password() {
  if [[ -z "${PASEO_PASSWORD:-}" && -t 0 ]]; then
    read -r -s -p "Paseo 团队访问密码: " PASEO_PASSWORD
    printf "\n" >&2
    export PASEO_PASSWORD
  fi
}
init_config() {
  mkdir -p "$team_home"
  node - "$team_home/config.json" "$port" <<\JS
const fs = require("node:fs");
const [file, port] = process.argv.slice(2);
const listen = `127.0.0.1:${port}`;
const config = { version: 1, daemon: { listen, relay: { enabled: false }, mcp: { enabled: true, injectIntoAgents: true } }, features: { webUi: { enabled: true }, dictation: { enabled: false }, voiceMode: { enabled: false } } };
try { fs.writeFileSync(file, JSON.stringify(config, null, 2) + "\n", { flag: "wx", mode: 0o600 }); }
catch (e) { if (e.code !== "EEXIST") throw e; }
const actual = JSON.parse(fs.readFileSync(file, "utf8"));
if (actual.daemon?.listen !== listen || actual.daemon?.relay?.enabled !== false) {
  console.error("团队配置已改变监听地址或 Relay；为避免意外暴露，拒绝自动启动。请检查 " + file);
  process.exit(1);
}
JS
}
case "${1:-help}" in
  install)
    [[ $# -eq 1 ]] || fail "用法：paseo-team install"
    require_node
    if [[ -f "$runtime/.verified-install" ]]; then check_runtime; exit 0; fi
    mkdir -p "$runtime"
    node - "$runtime/package.json" "$version" "$pty_version" <<\JS
const fs = require("node:fs");
const [file, version, ptyVersion] = process.argv.slice(2);
const manifest = { name: "antigem-paseo-runtime", private: true, version: "1.0.0", dependencies: { "@getpaseo/cli": version } };
if (process.platform === "android") {
  if (process.arch !== "arm64") throw Error("This Android PTY port supports ARM64 only.");
  manifest.overrides = { "node-pty": `npm:@mmmbuto/node-pty-android-arm64@${ptyVersion}` };
}
fs.writeFileSync(file, JSON.stringify(manifest, null, 2) + "\n");
JS
    npm install --prefix "$runtime" --no-audit --no-fund || fail "Paseo 安装失败；请保留 npm 原始错误，不要跳过安装脚本。"
    check_runtime
    touch "$runtime/.verified-install"
    ;;
  check)
    [[ $# -eq 1 ]] || fail "用法：paseo-team check"
    require_node; require_paseo; check_runtime
    ;;
  password)
    [[ $# -eq 1 ]] || fail "用法：paseo-team password"
    require_node; require_paseo; init_config
    "$paseo" daemon set-password --home "$team_home"
    ;;
  start)
    [[ $# -eq 1 ]] || fail "用法：paseo-team start"
    require_node; require_paseo; init_config
    node - "$team_home/config.json" <<\JS
const fs = require("node:fs");
const config = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
if (!/^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$/.test(config.daemon?.auth?.password ?? "")) {
  console.error("请先执行 paseo-team password 设置团队服务访问密码。");
  process.exit(1);
}
JS
    connection_password
    "$paseo" daemon start --home "$team_home"
    ;;
  status|stop)
    [[ $# -eq 1 ]] || fail "用法：paseo-team $1"
    require_paseo
    if [[ "$1" == status ]]; then connection_password; fi
    "$paseo" daemon "$1" --home "$team_home"
    ;;
  cli)
    shift; require_paseo
    [[ $# -gt 0 ]] || fail "用法：paseo-team cli <args>"
    if [[ "$1" != --help && "$1" != -h && "$1" != --version ]]; then connection_password; fi
    exec "$paseo" --home "$team_home" "$@"
    ;;
  url) printf "http://127.0.0.1:%s\n" "$port" ;;
  help|-h|--help) usage ;;
  *) usage >&2; exit 2 ;;
esac
