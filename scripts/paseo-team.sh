#!/usr/bin/env bash
# Paseo 团队运行时（Termux 适配版）
# 基线：chatgpt/paseo-native-team:scripts/paseo-team.sh（189 行）
# 改动要点：
#   1. 核心编排与终端页签（node-pty）分级 —— PTY 失败不再一票否决安装/启动
#   2. 新增 doctor / update / rollback / logs / providers / profiles
#   3. password 绕过 set-password 的 TTY 依赖（官方哈希函数直接写入）
#   4. 新增 patch-terminal：缩短 socket 文件名，使终端在 Termux 上可用（可回滚）
# 不改 AntiGem 代码；除 patch-terminal 外不改上游 JS。
set -euo pipefail
umask 077

root="${PASEO_TEAM_ROOT:-$HOME/.local/share/antigem-paseo-team}"
team_home="$root/state"
version="${PASEO_TEAM_VERSION:-0.17.0-beta.1}"
pty_version="${PASEO_TEAM_PTY_VERSION:-1.1.2}"
port="${PASEO_TEAM_PORT:-6799}"
tmpd="${TMPDIR:-$root}"
unset PASEO_HOST 2>/dev/null || true
export PASEO_HOME="$team_home"

fail() { printf '%s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }

mkdir -p -m 700 "$root"
mkdir -p "$tmpd" 2>/dev/null || tmpd="$root"
diagnostics="$(mktemp -d "$tmpd/paseo-team.XXXXXXXX")"
trap 'rm -rf "$diagnostics"' EXIT
pty_out="$diagnostics/pty-out"
pty_reason="$diagnostics/pty-reason"

resolve_runtime() {
  if [[ -L "$root/current" ]]; then
    local t
    t="$(readlink "$root/current")"
    if [[ "$t" != /* ]]; then t="$root/$t"; fi
    printf '%s' "$t"
  elif [[ -d "$root/runtime-arm64-v2" ]]; then
    printf '%s' "$root/runtime-arm64-v2"
  else
    printf '%s' "$root/runtimes/$version"
  fi
}
runtime="$(resolve_runtime)"
paseo="$runtime/node_modules/@getpaseo/cli/bin/paseo"

usage() {
  cat <<EOF
Paseo 团队运行时（Termux 适配版）

  安装与切换
    install [版本]      安装固定版本（默认 $version），PTY 失败不阻塞
    update  [版本]      新版本独立安装并切换；已装版本不覆盖
    rollback            切回上一次正在使用的运行时
    versions            列出已安装运行时

  运行
    password            设置访问密码（非 TTY 也可）
    start / stop / restart / status
    verify              验证 健康检查 / Web UI / 认证
    logs [-f]           查看或跟踪日志
    url                 打印访问地址

  诊断与适配
    doctor              环境体检（平台/原生库/socket 预算/provider/系统命令）
    check               核心自检（PTY 单独分级）
    providers [--write] 探测 claude/codex/opencode/agy 并选择性写入路径覆盖
    profiles [--write 文件] 显示或导入真实岗位配置（不填假模型）
    patch-terminal      实验性：缩短终端 socket 文件名（可回滚）
    unpatch-terminal    撤销补丁
    cli <args>          透传原版 Paseo 命令
EOF
}

require_node() {
  command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 \
    || fail "缺少 Node.js/npm；请在 Termux 执行 pkg install nodejs"
}
require_paseo() {
  [[ -f "$paseo" && -f "$runtime/.verified-install" ]] || fail "运行时未完成安装：请执行 paseo-team install。"
}
run_paseo() { node --disable-warning=DEP0040 "$paseo" "$@"; }

core_check() {
  node - "$runtime" <<\JS
const { createRequire } = require("node:module");
const path = require("node:path");
const fs = require("node:fs");
const runtime = process.argv[2];
const cliPkg = path.join(runtime, "node_modules", "@getpaseo", "cli", "package.json");
if (!fs.existsSync(cliPkg)) { console.error("缺少 @getpaseo/cli"); process.exit(1); }
const cliRequire = createRequire(cliPkg);
const serverEntry = cliRequire.resolve("@getpaseo/server");
const load = createRequire(serverEntry);
const meta = load("node-pty/package.json");
console.log("  server     : " + path.relative(runtime, serverEntry));
console.log("  node-pty   : " + meta.name + "@" + meta.version);
console.log("  platform   : " + process.platform + "/" + process.arch);
JS
}

pty_check() {
  node - "$runtime" >"$pty_out" 2>&1 <<\JS
const { createRequire } = require("node:module");
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const runtime = process.argv[2];
const cliPkg = path.join(runtime, "node_modules", "@getpaseo", "cli", "package.json");
const cliRequire = createRequire(cliPkg);
const server = cliRequire.resolve("@getpaseo/server");
const load = createRequire(server);
const meta = load("node-pty/package.json");
const source = path.dirname(load.resolve("node-pty/package.json"));
const platformDir = "prebuilds/" + process.platform + "-" + process.arch;
const native = ["build/Release", platformDir].filter(p => fs.existsSync(path.join(source, p)));
if (!native.length) {
  const have = fs.existsSync(path.join(source, "prebuilds"))
    ? fs.readdirSync(path.join(source, "prebuilds")).join(",") : "(none)";
  console.error("NO_NATIVE_BUILD: 缺少 " + platformDir + "；现有: " + have);
  process.exit(1);
}
const temp = fs.mkdtempSync(path.join(os.tmpdir(), "paseo-pty-"));
process.on("exit", () => fs.rmSync(temp, { recursive: true, force: true }));
const dest = path.join(temp, "node_modules", "node-pty");
fs.mkdirSync(dest, { recursive: true });
for (const part of ["package.json", "lib"]) {
  fs.cpSync(path.join(source, part), path.join(dest, part), { recursive: true });
}
for (const part of native) {
  fs.mkdirSync(path.dirname(path.join(dest, part)), { recursive: true });
  fs.cpSync(path.join(source, part), path.join(dest, part), { recursive: true });
}
const copied = createRequire(path.join(temp, "worker.cjs"))("node-pty");
const shell = path.join(process.env.PREFIX || "/usr", "bin", "bash");
const child = copied.spawn(shell, ["-c", "printf PASEO_PTY_OK"],
  { name: "xterm", cols: 80, rows: 24, cwd: process.env.HOME, env: process.env });
let out = "";
const timer = setTimeout(() => {
  try { child.kill(); } catch {}
  console.error("PTY_TIMEOUT");
  process.exit(1);
}, 10000);
child.onData(d => { out += d; });
child.onExit(r => {
  clearTimeout(timer);
  if (r.exitCode !== 0 || out.indexOf("PASEO_PTY_OK") === -1) {
    console.error("PTY_FAILED code=" + r.exitCode);
    process.exit(1);
  }
  console.log("PTY_OK " + meta.name + "@" + meta.version);
});
JS
  if [[ $? -ne 0 ]]; then
    local msg
    msg="$(tr '\n' ' ' <"$pty_out" 2>/dev/null || true)"
    printf '%s\n' "${msg:-PTY_UNKNOWN}" >"$pty_reason"
    return 1
  fi
  return 0
}

socket_budget_report() {
  node - "$team_home" "$runtime" <<\JS
const path = require("node:path");
const os = require("node:os");
const fs = require("node:fs");
const budget = process.platform === "darwin" ? 103 : process.platform === "linux" ? 107 : 103;
const uid = process.getuid ? process.getuid() : 0;
const home = process.argv[2];
// 与 terminal-worker-socket-path.ts 一致：tw-<16hex>-<pid>-<uuid36>.sock
const src = path.join(process.argv[3] || "", "node_modules/@getpaseo/server/dist/server/terminal/terminal-worker-socket-path.js");
const patched = fs.existsSync(src) && fs.readFileSync(src, "utf8").includes("PASEO_TEAM_SHORT_SOCKET");
const fileName = patched ? "tw-" + "0".repeat(16) + ".sock"
  : "tw-" + "0".repeat(16) + "-12345-" + "0".repeat(8) + "-" + "0".repeat(4) + "-" + "0".repeat(4) + "-" + "0".repeat(12) + ".sock";
console.log("  终端路径补丁 : " + (patched ? "已启用" : "未启用"));
const rows = [
  ["$PASEO_HOME", path.join(home, "terminal-worker", "sock", fileName)],
  ["$TMPDIR", path.join(os.tmpdir(), "paseo-" + uid, fileName)],
  ["/tmp", path.join("/tmp", "paseo-" + uid, fileName)],
];
let pass = 0;
console.log("  预算 " + budget + " 字节（platform=" + process.platform + "）");
for (const row of rows) {
  const label = row[0], p = row[1];
  const n = Buffer.byteLength(p);
  const fits = n <= budget;
  const rootExists = label === "/tmp" ? fs.existsSync("/tmp") : true;
  if (fits && rootExists) pass++;
  const tag = !fits ? "FAIL 超长" : (rootExists ? "PASS" : "FAIL 目录不存在");
  console.log("  " + label.padEnd(11) + String(n).padStart(4) + "B  " + tag);
}
if (pass === 0) {
  console.log("  => 终端 worker 无法绑定 socket，终端页签不可用（agent 编排不受影响）");
  console.log("  => 可尝试：paseo-team patch-terminal");
} else {
  console.log("  => 存在可用候选；实际能否 bind 仍需在设备上确认");
}
JS
}

detect_providers() {
  for pair in claude:claude codex:codex opencode:opencode antigravity:agy; do
    id="${pair%%:*}"
    bin="${pair##*:}"
    if resolved="$(command -v "$bin" 2>/dev/null)" && [[ -f "$resolved" && -x "$resolved" ]]; then
      printf '%s\t%s\tfound\n' "$id" "$resolved"
    else
      printf '%s\t%s\tmissing\n' "$id" "-"
    fi
  done
}

# 判断可执行文件的类型。Termux 上这一步很关键：
#   npm 全局安装的 CLI 是 `#!/usr/bin/env node` 的 shim，而 Termux 没有 /usr/bin/env，
#   必须靠 termux-exec（LD_PRELOAD）在 execve 时改写 shebang。非登录会话可能没有该预载。
probe_binary() {
  local p="$1" head4 sb
  head4="$(head -c 4 "$p" 2>/dev/null || true)"
  if [[ "$head4" == *ELF* ]]; then
    printf 'ELF'
    return
  fi
  sb="$(head -n 1 "$p" 2>/dev/null | cut -c1-60 || true)"
  case "$sb" in
    '#!'*'/usr/bin/env'*)
      if [[ "$(uname -o 2>/dev/null)" != "Android" ]]; then
        printf 'env-shim'
      elif [[ "${LD_PRELOAD:-}" == *termux-exec* ]]; then
        printf 'env-shim(termux-exec ok)'
      else
        printf 'env-shim(!!termux-exec 未激活)'
      fi
      ;;
    '#!'*) printf 'script-shim' ;;
    *) printf 'binary' ;;
  esac
}

print_providers() {
  local id p st kind
  while IFS=$'\t' read -r id p st; do
    if [[ "$st" == "found" ]]; then
      kind="$(probe_binary "$p")"
      printf '  %-12s %-8s %-28s %s\n' "$id" "$st" "$kind" "$p"
    else
      printf '  %-12s %-8s %-28s %s\n' "$id" "$st" "-" "-"
    fi
  done < <(detect_providers)
}

init_config() {
  mkdir -p "$team_home"
  node - "$team_home/config.json" "$port" <<\JS
const fs = require("node:fs");
const file = process.argv[2];
const listen = "127.0.0.1:" + process.argv[3];
const config = {
  version: 1,
  daemon: { listen, relay: { enabled: false }, mcp: { enabled: true, injectIntoAgents: true } },
  features: { webUi: { enabled: true }, voiceMode: { enabled: false } },
};
try {
  fs.writeFileSync(file, JSON.stringify(config, null, 2) + "\n", { flag: "wx", mode: 0o600 });
} catch (e) {
  if (e.code !== "EEXIST") throw e;
}
const actual = JSON.parse(fs.readFileSync(file, "utf8"));
if (actual.daemon?.listen !== listen || actual.daemon?.relay?.enabled !== false || actual.features?.webUi?.enabled !== true) {
  console.error("监听地址、Relay 或 Web UI 配置不符合独立本地模式；请检查 " + file);
  process.exit(1);
}
JS
}

has_password() {
  node - "$team_home/config.json" <<\JS
const fs = require("node:fs");
let ok = false;
try {
  const c = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
  ok = /^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$/.test(c?.daemon?.auth?.password ?? "");
} catch {}
process.exit(ok ? 0 : 1);
JS
}

daemon_running() {
  run_paseo daemon status --home "$team_home" 2>/dev/null | grep -Fxq 'localDaemon: running'
}

stop_quietly() {
  if daemon_running; then
    run_paseo daemon stop --home "$team_home" >/dev/null 2>&1 || true
    info "已停止运行中的守护进程，以便安全修改配置"
  fi
}

activate_runtime() {
  local target="$1" old link
  old="$(resolve_runtime)"
  if [[ "$old" != "$target" ]]; then
    link="$root/.current-$$"
    ln -s "$target" "$link"
    mv -Tf "$link" "$root/current" || fail "切换运行时失败"
    if [[ -f "$old/.verified-install" ]]; then
      link="$root/.previous-$$"
      ln -s "$old" "$link"
      mv -Tf "$link" "$root/previous" || fail "保存回滚目标失败"
    fi
  fi
  runtime="$target"
  paseo="$runtime/node_modules/@getpaseo/cli/bin/paseo"
  info "当前运行时：$runtime"
}
do_install() {
  local target="${1:-$version}" dest stage
  [[ "$target" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9][a-zA-Z0-9.-]*)?$ ]] || fail "非法 Paseo 版本：$target"
  require_node
  dest="$root/runtimes/$target"
  mkdir -p -m 700 "$root/runtimes"
  if [[ -f "$dest/.verified-install" ]]; then
    runtime="$dest"
    paseo="$runtime/node_modules/@getpaseo/cli/bin/paseo"
    core_check || fail "现有版本核心检查未通过，不会覆盖，请先查看 doctor 日志"
    run_paseo --version || fail "现有版本 CLI 已损坏"
    info "已安装 $target，复用现有目录"
  else
    [[ ! -e "$dest" ]] || fail "$dest 已有未完成目录，拒绝覆盖。请先检查并手工处理。"
    stage="$root/runtimes/.stage-$target-$$"
    mkdir -p -m 700 "$stage"
    node - "$stage/package.json" "$target" "$pty_version" <<\JS
const fs = require("node:fs");
const [file, version, ptyVersion] = process.argv.slice(2);
const manifest = { name: "antigem-paseo-runtime", private: true, version: "1.0.0", dependencies: { "@getpaseo/cli": version } };
if (process.platform === "android") {
  if (process.arch !== "arm64") throw Error("Android PTY port supports ARM64 only");
  manifest.overrides = { "node-pty": "npm:@mmmbuto/node-pty-android-arm64@" + ptyVersion };
}
fs.writeFileSync(file, JSON.stringify(manifest, null, 2) + "\n", { mode: 0o600 });
JS
    info "安装 Paseo $target → 临时独立目录"
    npm install --prefix "$stage" --no-audit --no-fund || fail "npm 安装失败，旧运行时未更改；请检查原始 npm 错误"
    runtime="$stage"
    paseo="$runtime/node_modules/@getpaseo/cli/bin/paseo"
    core_check || fail "核心模块检查失败，旧运行时未更改"
    run_paseo --version || fail "CLI 启动检查失败，旧运行时未更改"
    if pty_check; then
      printf 'PTY_OK %s\n' "$(cat "$pty_out")" > "$stage/.pty-status"
      info "PTY 可用"
    else
      cp "$pty_reason" "$stage/.pty-status" 2>/dev/null || true
      warn "PTY 不可用，不阻断团队服务：$(cat "$pty_reason" 2>/dev/null)"
    fi
    touch "$stage/.verified-install"
    chmod 600 "$stage/.verified-install"
    mv "$stage" "$dest" || fail "运行时安装移动失败"
  fi
  activate_runtime "$dest"
}
cmd_update() {
  local target="${1:-$version}"
  if daemon_running; then fail "正在运行的 daemon 必须先 stop，再执行 update"; fi
  do_install "$target"
}
cmd_rollback() {
  if daemon_running; then fail "请先 stop 再回滚"; fi
  [[ -L "$root/previous" ]] || fail "没有可用的上一运行时"
  local target
  target="$(readlink -f "$root/previous")"
  [[ -f "$target/.verified-install" ]] || fail "上一运行时已损坏或不存在"
  activate_runtime "$target"
  run_paseo --version
}

cmd_password() {
  local pw="${PASEO_PASSWORD:-}"
  require_node
  require_paseo
  init_config
  if [[ -z "$pw" ]]; then
    if [[ -t 0 ]]; then
      read -r -s -p "Paseo 团队访问密码: " pw
      printf '\n' >&2
    else
      fail "非交互环境请用：PASEO_PASSWORD='...' paseo-team password"
    fi
  fi
  if [[ -z "$pw" ]]; then
    fail "密码不能为空"
  fi
  ( cd "$runtime" && PASEO_TEAM_PW="$pw" node --input-type=module -e '
import fs from "node:fs";
const auth = await import("@getpaseo/server/auth");
const file = process.env.PASEO_HOME + "/config.json";
const cfg = JSON.parse(fs.readFileSync(file, "utf8"));
cfg.daemon = cfg.daemon || {};
cfg.daemon.auth = Object.assign({}, cfg.daemon.auth, { password: await auth.hashDaemonPassword(process.env.PASEO_TEAM_PW) });
const tmp = file + "." + process.pid + ".tmp";
fs.writeFileSync(tmp, JSON.stringify(cfg, null, 2) + "\n", { mode: 0o600 });
fs.renameSync(tmp, file);
console.log("密码哈希已写入");
' ) || fail "写入密码失败"
  info "密码已设置（绕过 set-password 的 TTY 要求）。若服务在跑，执行 paseo-team restart 生效。"
}

cmd_verify() {
  local pw="${PASEO_PASSWORD:-}"
  local rc=0
  if [[ -z "$pw" && -t 0 ]]; then
    read -r -s -p "Paseo 团队连接密码: " pw
    printf '\n' >&2
  fi
  [[ -n "$pw" ]] || fail "verify 要求输入连接密码，非交互环境请使用 PASEO_PASSWORD 环境变量"
  export PASEO_PASSWORD="$pw"
  init_config
  PASEO_PASSWORD="$pw" node - "$port" <<\JS
const port = process.argv[2];
const password = process.env.PASEO_PASSWORD;
const base = "http://127.0.0.1:" + port;
(async () => {
  const health = await fetch(base + "/api/health", { redirect: "error", signal: AbortSignal.timeout(5000) });
  const body = await health.json();
  if (!health.ok || body.status !== "ok") throw Error("健康检查失败");
  console.log("  健康检查     ok");
  const web = await fetch(base + "/", { signal: AbortSignal.timeout(5000) });
  const html = await web.text();
  if (!web.ok || (web.headers.get("content-type") || "").indexOf("text/html") === -1 || !/<html[\s>]/i.test(html)) {
    throw Error("Web UI 不可用");
  }
  console.log("  Web UI       ok (" + html.length + " 字节)");
  const anon = await fetch(base + "/api/status", { redirect: "error", signal: AbortSignal.timeout(5000) });
  if (anon.status !== 401) throw Error("匿名 /api/status 返回 " + anon.status + "，认证未生效");
  console.log("  匿名拦截     ok (401)");
  if (!password) {
    console.log("  认证通过     跳过（未提供 PASEO_PASSWORD）");
    return;
  }
  const auth = await fetch(base + "/api/status", {
    headers: { Authorization: "Bearer " + password },
    signal: AbortSignal.timeout(5000),
  });
  if (!auth.ok) throw Error("带密码访问失败：" + auth.status);
  console.log("  认证通过     ok");
})().catch(e => { console.error("  " + e.message); process.exitCode = 1; });
JS
  rc=$?
  local st
  st="$(run_paseo daemon status --home "$team_home" 2>&1 || true)"
  if grep -Fxq 'connectedDaemon: reachable' <<<"$st"; then
    info "  RPC 连接     ok"
  else
    warn "connectedDaemon 未就绪"
    rc=1
  fi
  if ! grep -Fq '"provider":"claude","available":true' <<<"$st"; then
    warn "claude provider 当前不可用；执行 paseo-team doctor 查看原因"
  fi
  return $rc
}

cmd_providers() {
  local write=0
  if [[ "${1:-}" == "--write" ]]; then write=1; fi
  info "provider 探测（Paseo 内部 id ← 命令名）"
  print_providers
  if [[ $write -eq 1 ]]; then
    if daemon_running; then fail "写入 provider 前请先 paseo-team stop"; fi
    init_config
    detect_providers >"$diagnostics/providers"
    node - "$team_home/config.json" "$diagnostics/providers" <<\JS
const fs = require("node:fs");
const file = process.argv[2];
const table = fs.readFileSync(process.argv[3], "utf8");
const cfg = JSON.parse(fs.readFileSync(file, "utf8"));
cfg.agents = cfg.agents || {};
cfg.agents.providers = cfg.agents.providers || {};
let n = 0;
for (const line of table.split("\n")) {
  const cols = line.split("\t");
  if (cols.length < 3) continue;
  if (cols[2] !== "found") continue;
  cfg.agents.providers[cols[0]] = Object.assign({}, cfg.agents.providers[cols[0]], { command: [cols[1]] });
  n++;
}
fs.writeFileSync(file, JSON.stringify(cfg, null, 2) + "\n");
console.log("已写入 " + n + " 条路径覆盖 → agents.providers.<id>.command");
JS
    info "执行 paseo-team start 后生效"
  else
    info "未写入。加 --write 可固定写入 agents.providers.<id>.command"
    info "  作用：绕过 Paseo 的 /usr/bin/which 与 2 秒 --version 探测（手机冷启动常超 2s）"
  fi
}

cmd_profiles() {
  init_config
  if [[ "${1:-}" != "--write" ]]; then
    node - "$team_home/config.json" <<\JS
const fs = require("node:fs");
const config = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
for (const p of config.daemon?.agentProfiles || []) {
  console.log([p.id, p.name, p.provider, p.model || "(default)"].join("  |  "));
}
JS
    info "导入岗位：paseo-team profiles --write /path/to/profiles.json（JSON 数组，模型须真实存在）"
    return
  fi
  [[ $# -eq 2 && -f "$2" ]] || fail "请指定 JSON 文件：paseo-team profiles --write 文件.json"
  if daemon_running; then fail "导入岗位前请先 paseo-team stop"; fi
  node - "$team_home/config.json" "$2" <<\JS
const fs = require("node:fs");
const [file, source] = process.argv.slice(2);
const data = JSON.parse(fs.readFileSync(source, "utf8"));
if (!Array.isArray(data) || !data.length || data.length > 100) throw Error("Expected a nonempty JSON array (max 100)");
const config = JSON.parse(fs.readFileSync(file, "utf8"));
const existing = config.daemon?.agentProfiles || [];
const used = new Set(existing.map(p=>p.id));
for (const p of data) {
 if (!p || typeof p !== "object" || !["id", "name", "provider"].every(k=>typeof p[k]==="string" && p[k].trim()) || used.has(p.id)) throw Error("Invalid/duplicate profile: " + p?.id);
 if (p.model !== undefined && typeof p.model !== "string") throw Error("model must be a string");
 used.add(p.id);
}
config.daemon = config.daemon || {};
config.daemon.agentProfiles = existing.concat(data);
const tmp = file + "." + process.pid + ".tmp";
fs.writeFileSync(tmp, JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
fs.renameSync(tmp, file);
console.log("已添加岗位数：" + data.length);
JS
}

cmd_patch_terminal() {
  local f="$runtime/node_modules/@getpaseo/server/dist/server/terminal/terminal-worker-socket-path.js"
  require_paseo
  if daemon_running; then fail "修改终端补丁前，请先 paseo-team stop"; fi
  if [[ ! -f "$f" ]]; then fail "找不到 $f"; fi
  if grep -q 'PASEO_TEAM_SHORT_SOCKET' "$f"; then
    info "补丁已存在"
    return 0
  fi
  cp -p "$f" "$f.orig"
  node - "$f" <<\JS
const fs = require("node:fs");
const f = process.argv[2];
const src = fs.readFileSync(f, "utf8");
const before = "const fileName = `tw-${socketFileId(input)}.sock`;";
const after = 'const fileName = `tw-${createHash("sha256").update(socketFileId(input)).digest("hex").slice(0, 16)}.sock`; /* PASEO_TEAM_SHORT_SOCKET */';
if (src.indexOf(before) === -1) { console.error("未找到预期代码行，补丁中止（上游可能已变更）"); process.exit(1); }
if (src.indexOf('from "node:crypto"') === -1) { console.error("缺少 createHash 导入，补丁中止"); process.exit(1); }
fs.writeFileSync(f, src.replace(before, after));
console.log("已替换 socket 文件名生成逻辑（67B → 23B）");
JS
  info "原文件备份：$f.orig"
  info "验证：paseo-team doctor → 查看 socket 预算"
  info "回滚：paseo-team unpatch-terminal"
}

cmd_unpatch_terminal() {
  require_paseo
  if daemon_running; then fail "撤销终端补丁前，请先 paseo-team stop"; fi
  local f="$runtime/node_modules/@getpaseo/server/dist/server/terminal/terminal-worker-socket-path.js"
  if [[ -f "$f.orig" ]]; then
    mv -f "$f.orig" "$f"
    info "已还原 $f"
  else
    info "没有备份文件，未做改动"
  fi
}

cmd_doctor() {
  require_node
  info "=== 环境 ==="
  info "  platform/arch : $(node -p 'process.platform+"/"+process.arch')"
  info "  node/npm      : $(node -v) / $(npm -v 2>/dev/null || echo '?')"
  info "  runtime       : $(resolve_runtime)"
  info "  PASEO_HOME    : $team_home"
  info "  PREFIX        : ${PREFIX:-(未设置)}"
  info "  SHELL         : ${SHELL:-(未设置)}"
  info "  TMPDIR        : $tmpd"
  info ""
  if [[ -f "$paseo" ]]; then
    info "=== 核心 ==="
    if ! core_check; then warn "  核心自检失败"; fi
    info ""
    info "=== PTY ==="
    if pty_check; then
      info "  可用：$(cat "$pty_out")"
    else
      warn "  不可用：$(cat "$pty_reason" 2>/dev/null)"
      warn "  （只影响终端页签，不影响 agent 编排）"
    fi
    info ""
    info "=== 终端 socket 预算 ==="
    socket_budget_report
    info ""
  else
    warn "运行时未安装，跳过核心 / PTY / socket 检查"
    info ""
  fi
  info "=== Provider ==="
  print_providers
  info ""
  info "=== 系统命令 ==="
  for c in git ps which; do
    if command -v "$c" >/dev/null 2>&1; then
      info "  $c : $(command -v "$c")"
    else
      warn "  $c : 缺失"
    fi
  done
  if ps -o pid --no-headers --ppid $$ >/dev/null 2>&1; then
    info "  ps 长选项 : 支持（tree-kill 可回收子孙进程）"
  else
    warn "  ps 长选项 : 不支持 → tree-kill 只能杀直接子进程，CLI 孙进程可能残留"
  fi
}

case "${1:-help}" in
  install)
    shift
    do_install "${1:-$version}"
    ;;
  update)
    shift
    cmd_update "${1:-$version}"
    ;;
  rollback)
    cmd_rollback
    ;;
  versions)
    info "运行时目录："
    ls -1d "$root"/runtimes/*/ "$root"/runtime-arm64-v2/ 2>/dev/null | sed 's:/$::' || true
    info "current → $(resolve_runtime)"
    ;;
  password)
    cmd_password
    ;;
  start)
    require_node
    require_paseo
    init_config
    if ! has_password; then
      fail "请先执行 paseo-team password 设置访问密码"
    fi
    if [[ -f "$runtime/.pty-status" ]] && ! grep -q '^PTY_OK' "$runtime/.pty-status" 2>/dev/null; then
      warn "已知 PTY 不可用；终端页签将不可用，编排不受影响"
    fi
    run_paseo daemon start --home "$team_home" --timeout 90
    info "地址：http://127.0.0.1:$port"
    ;;
  stop|status|restart)
    require_paseo
    if [[ "$1" == status && -z "${PASEO_PASSWORD:-}" && -t 0 ]]; then
      read -r -s -p "Paseo 团队连接密码: " PASEO_PASSWORD
      printf '\n' >&2
      export PASEO_PASSWORD
    fi
    if [[ "$1" == status ]]; then
      run_paseo daemon status --home "$team_home"
      exit $?
    fi
    run_paseo daemon "$1" --home "$team_home"
    ;;
  verify)
    require_paseo
    cmd_verify
    ;;
  logs)
    if [[ "${2:-}" == "-f" ]]; then
      exec tail -f "$team_home/daemon.log"
    fi
    tail -n 80 "$team_home/daemon.log"
    ;;
  url)
    info "http://127.0.0.1:$port"
    ;;
  doctor)
    cmd_doctor
    ;;
  check)
    require_paseo
    core_check
    if pty_check; then
      info "PTY: 可用（$(cat "$pty_out")）"
    else
      info "PTY: 不可用（$(cat "$pty_reason" 2>/dev/null)）—— 仅影响终端页签"
    fi
    ;;
  providers)
    shift
    cmd_providers "${1:-}"
    ;;
  profiles)
    shift
    cmd_profiles "$@"
    ;;
  patch-terminal)
    cmd_patch_terminal
    ;;
  unpatch-terminal)
    cmd_unpatch_terminal
    ;;
  cli)
    shift
    require_paseo
    [[ $# -gt 0 ]] || fail "用法：paseo-team cli <args>"
    exec node --disable-warning=DEP0040 "$paseo" --home "$team_home" "$@"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
