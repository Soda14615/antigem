#!/usr/bin/env bash
# Real upstream install, authenticated daemon and terminal worker, in disposable CI state.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
team=(bash "$repo/scripts/paseo-team.sh")
temp="$(mktemp -d)"
export HOME="$temp/home"
mkdir -p "$HOME" "$temp/project"
export PASEO_PASSWORD="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
export PASEO_HOME="$HOME/.local/share/antigem-paseo-team/state"
runtime="$HOME/.local/share/antigem-paseo-team/runtime-arm64-v2"
cleanup() {
  "${team[@]}" cli terminal kill ci-worker >/dev/null 2>&1 || true
  "${team[@]}" stop >/dev/null 2>&1 || true
  if [[ "$1" != 0 ]]; then
    find "$PASEO_HOME" -maxdepth 3 -type f -name '*.log' -exec tail -n 30 {} \; 2>/dev/null | sed "s/$PASEO_PASSWORD/[redacted]/g" || true
  fi
  rm -rf "$temp"
}
trap 'cleanup "$?"' EXIT
git -C "$temp/project" init -q
"${team[@]}" install
# Use the original Paseo auth implementation for noninteractive isolated test setup.
mkdir -p "$PASEO_HOME"
node - "$runtime" "$PASEO_HOME" <<'JS'
const fs = require("node:fs");
const path = require("node:path");
const {createRequire} = require("node:module");
const {pathToFileURL} = require("node:url");
const [root, home] = process.argv.slice(2);
const req = createRequire(path.join(root,"node_modules","@getpaseo","cli","package.json"));
(async () => {
  const {hashDaemonPassword} = await import(pathToFileURL(req.resolve("@getpaseo/server/auth")).href);
  const config = {version:1,daemon:{listen:"127.0.0.1:6799",relay:{enabled:false},auth:{password:hashDaemonPassword(process.env.PASEO_PASSWORD)},mcp:{enabled:true,injectIntoAgents:true}},features:{webUi:{enabled:true},dictation:{enabled:false},voiceMode:{enabled:false}}};
  fs.writeFileSync(path.join(home,"config.json"),JSON.stringify(config,null,2),{mode:0o600,flag:"wx"});
})().catch(e => {console.error(e);process.exitCode=1;});
JS
"${team[@]}" start
"${team[@]}" verify
"${team[@]}" cli terminal create --cwd "$temp/project" --name ci-worker
# Splitting the sentinel prevents shell command echo from satisfying the test.
"${team[@]}" cli terminal send-keys ci-worker --literal $'printf "%s%s\\n" PASEO_ WORKER_OK\r'
for i in $(seq 1 20); do
  captured="$("${team[@]}" cli terminal capture ci-worker)"
  if grep -Fxq PASEO_WORKER_OK <<< "$captured"; then
    echo "Actual terminal worker command passed"
    exit 0
  fi
  sleep 1
done
echo "Actual terminal worker did not return the expected line" >&2
exit 1
