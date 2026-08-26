#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/.runtime"
LOG_FILE="$LOG_DIR/vfxlab-server.log"
PID_FILE="$LOG_DIR/vfxlab-server.pid"
WORKER_PID_FILE="$LOG_DIR/vfxlab-worker.pid"
WATCHDOG_SCRIPT="$ROOT_DIR/run_vfxlab_server_worker.sh"
RESTART_DELAY_SECONDS=2
STARTUP_CHECK_SECONDS=8
SERVER_URL="http://127.0.0.1:4173/"

mkdir -p "$LOG_DIR"

log() {
  printf "[%s] %s\n" "$(date '+%F_%H:%M:%S')" "$1" | tee -a "$LOG_FILE"
}

is_running() {
  local pid="${1:-}"
  if [[ -z "$pid" ]]; then
    return 1
  fi
  if kill -0 "$pid" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

stop_watchdog_pid() {
  local pid="$1"
  if ! is_running "$pid"; then
    return
  fi
  kill "$pid" >/dev/null 2>&1 || true
  for _ in {1..6}; do
    sleep 1
    if ! is_running "$pid"; then
      return
    fi
  done
  kill -9 "$pid" >/dev/null 2>&1 || true
}

server_ready() {
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS --max-time 1 "$SERVER_URL" >/dev/null 2>&1; then
      return 0
    fi
    return 1
  fi
  return 1
}

launch_worker_detached() {
  local watchdog_script="$1"
  local root_dir="$2"
  local log_file="$3"
  local restart_delay="$4"

  python3 - "$watchdog_script" "$root_dir" "$log_file" "$restart_delay" <<'PY'
import os
import sys

watchdog_script, root_dir, log_file, restart_delay = sys.argv[1:5]

pid = os.fork()
if pid > 0:
    sys.exit(0)

os.setsid()
pid = os.fork()
if pid > 0:
    print(pid)
    sys.exit(0)

os.chdir(root_dir)
os.umask(0o022)

devnull = os.open("/dev/null", os.O_RDONLY)
with open(log_file, "a", buffering=1) as out:
    os.dup2(devnull, 0)
    os.dup2(out.fileno(), 1)
    os.dup2(out.fileno(), 2)
    os.execl(watchdog_script, watchdog_script, root_dir, log_file, restart_delay)
PY
}

start_server() {
  if [[ -f "$PID_FILE" ]]; then
    local existing_pid
    existing_pid="$(cat "$PID_FILE")"
    if is_running "$existing_pid"; then
      echo "vfx-lab server watchdog is already running (pid=$existing_pid)."
      return 0
    fi
    log "stale pid file removed (pid=$existing_pid)"
    rm -f "$PID_FILE"
  fi

  if command -v setsid >/dev/null 2>&1; then
    nohup setsid "$WATCHDOG_SCRIPT" \
      "$ROOT_DIR" \
      "$LOG_FILE" \
      "$RESTART_DELAY_SECONDS" \
      < /dev/null >> "$LOG_FILE" 2>&1 &
    local watchdog_pid="$!"
  else
    log "setsid is unavailable; using python-based daemon launcher"
    watchdog_pid="$(launch_worker_detached "$WATCHDOG_SCRIPT" "$ROOT_DIR" "$LOG_FILE" "$RESTART_DELAY_SECONDS" | tail -n 1)"
  fi
  watchdog_pid="${watchdog_pid//[$'\n\r ']/}"
  echo "$watchdog_pid" > "$PID_FILE"
  log "started watchdog loop (pid=$watchdog_pid)"

  for _ in $(seq 1 "$STARTUP_CHECK_SECONDS"); do
    if server_ready; then
      log "server is alive: $SERVER_URL"
      return 0
    fi
    sleep 1
  done
  log "watchdog started, but server not reachable after ${STARTUP_CHECK_SECONDS}s: $SERVER_URL"
}

stop_server() {
  local pid=""
  if [[ -f "$PID_FILE" ]]; then
    pid="$(cat "$PID_FILE")"
    stop_watchdog_pid "$pid"
    rm -f "$PID_FILE" "$WORKER_PID_FILE"
    echo "stopped vfx-lab watchdog"
    return
  fi

  if pgrep -f "run_vfxlab_server_worker.sh" >/dev/null 2>&1; then
    pkill -f "run_vfxlab_server_worker.sh" || true
    echo "attempted to stop orphaned vfx-lab watchdog process"
    return
  fi

  echo "vfx-lab watchdog not running"
}

status_server() {
  if [[ -f "$PID_FILE" ]]; then
    local watchdog_pid="$(cat "$PID_FILE")"
    if is_running "$watchdog_pid"; then
      if server_ready; then
        echo "running and responsive (watchdog pid=$watchdog_pid, $SERVER_URL)"
      else
        echo "running but not yet ready (watchdog pid=$watchdog_pid)"
      fi
    else
      echo "stale pid file detected (pid=$watchdog_pid)"
      rm -f "$PID_FILE" "$WORKER_PID_FILE"
    fi
  else
    echo "not running"
  fi
}

case "${1:-start}" in
  start)
    start_server
    ;;
  stop)
    stop_server
    ;;
  restart)
    stop_server
    start_server
    ;;
  status)
    status_server
    ;;
  *)
    echo "Usage: $0 [start|stop|restart|status]"
    exit 1
    ;;
esac
