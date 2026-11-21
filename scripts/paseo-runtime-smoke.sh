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
"${team[@]}" doctor
"${team[@]}" install
"${team[@]}" install  # Reuse installed version
"${team[@]}" check
"${team[@]}" providers --write
printf '[{"id":"ci-reader","name":"CI reader","provider":"claude","notes":"Read-only test"}]\n' > "$temp/profiles.json"
"${team[@]}" profiles --write "$temp/profiles.json"
"${team[@]}" password
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
