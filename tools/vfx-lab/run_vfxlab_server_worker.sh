#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$1"
LOG_FILE="$2"
RESTART_DELAY_SECONDS="${3:-2}"
WORKER_PID_FILE="${ROOT_DIR}/.runtime/vfxlab-worker.pid"

echo "$$" > "$WORKER_PID_FILE"

log() {
  printf "[%s] %s\n" "$(date '+%F_%H:%M:%S')" "$1" | tee -a "$LOG_FILE"
}

cleanup_and_exit() {
  rm -f "$WORKER_PID_FILE"
  if [[ -n "${serve_pid:-}" ]] && kill -0 "$serve_pid" >/dev/null 2>&1; then
    kill "$serve_pid" >/dev/null 2>&1 || true
    wait "$serve_pid" >/dev/null 2>&1 || true
  fi
  exit 0
}

trap cleanup_and_exit INT TERM

while true; do
  log "start: python3 $ROOT_DIR/serve.py"
  cd "$ROOT_DIR"
  python3 "$ROOT_DIR/serve.py" &
  serve_pid=$!
  set +e
  wait "$serve_pid"
  code=$?
  set -e
  if [[ "$code" -eq 0 ]]; then
    log "serve.py exited cleanly (code=$code), stop watchdog"
    exit 0
  fi
  log "serve.py exit code=$code, restart in ${RESTART_DELAY_SECONDS}s"
  sleep "$RESTART_DELAY_SECONDS"
done
