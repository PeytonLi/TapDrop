#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
RELAY_PID=""
LISTENER_PID=""

cleanup() {
  if [[ -n "$LISTENER_PID" ]] && kill -0 "$LISTENER_PID" 2>/dev/null; then
    kill -TERM "$LISTENER_PID" 2>/dev/null || true
    wait "$LISTENER_PID" 2>/dev/null || true
  fi
  if [[ -n "$RELAY_PID" ]] && kill -0 "$RELAY_PID" 2>/dev/null; then
    kill "$RELAY_PID" 2>/dev/null || true
    wait "$RELAY_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

cd "$ROOT"
dotnet build windows/TapDrop.Cli/TapDrop.Cli.csproj --configuration Release --verbosity quiet
CLI="$ROOT/windows/TapDrop.Cli/bin/Release/net8.0/TapDrop.Cli.dll"

cd "$ROOT/relay"
./node_modules/.bin/wrangler dev --local --port 8787 --ip 127.0.0.1 --persist-to "$TMP/wrangler" >"$TMP/relay.log" 2>&1 &
RELAY_PID=$!
cd "$ROOT"

python3 - "$TMP/relay.log" <<'PY'
import sys, time, urllib.request
for _ in range(100):
    try:
        with urllib.request.urlopen("http://127.0.0.1:8787/v1/health", timeout=1) as response:
            if response.status == 200:
                raise SystemExit(0)
    except Exception:
        pass
    time.sleep(0.2)
print(open(sys.argv[1], errors="replace").read())
raise SystemExit("relay did not become ready")
PY

dotnet "$CLI" pair-new --relay http://127.0.0.1:8787 --name "TapDrop Laptop" --out "$TMP/laptop.json" >"$TMP/qr.txt"
QR="$(<"$TMP/qr.txt")"
dotnet "$CLI" join --qr "$QR" --name "TapDrop Phone" --out "$TMP/phone.json"

start_listener() {
  local role="$1" config="$2" save_dir="$3" out="$4" err="$5"
  local ping_interval="${6:-}" pong_timeout="${7:-}"
  mkdir -p "$save_dir"
  if [[ -n "$ping_interval" && -n "$pong_timeout" ]]; then
    TAPDROP_PING_INTERVAL_SECONDS="$ping_interval" TAPDROP_PONG_TIMEOUT_SECONDS="$pong_timeout" \
      dotnet "$CLI" listen --config "$config" --role "$role" --save-dir "$save_dir" >"$out" 2>"$err" &
  else
    dotnet "$CLI" listen --config "$config" --role "$role" --save-dir "$save_dir" >"$out" 2>"$err" &
  fi
  LISTENER_PID=$!
  python3 - "$err" <<'PY'
import sys, time
path = sys.argv[1]
for _ in range(100):
    try:
        text = open(path, errors="replace").read()
    except FileNotFoundError:
        text = ""
    if "Connected to relay" in text:
        raise SystemExit(0)
    time.sleep(0.2)
print(open(path, errors="replace").read() if __import__("os").path.exists(path) else "")
raise SystemExit("listener did not connect")
PY
}

wait_received() {
  local output="$1" kind="$2" value="$3"
  python3 - "$output" "$kind" "$value" <<'PY'
import json, sys, time
path, kind, value = sys.argv[1:]
for _ in range(150):
    try:
        lines = open(path, errors="replace").read().splitlines()
    except FileNotFoundError:
        lines = []
    for line in lines:
        try:
            item = json.loads(line)["item"]
        except (ValueError, KeyError):
            continue
        if item.get("kind") != kind:
            continue
        if kind == "text" and item.get("text") == value:
            raise SystemExit(0)
        if kind == "url" and item.get("url") == value:
            raise SystemExit(0)
        if kind in ("file", "image") and item.get("blob", {}).get("name") == value:
            raise SystemExit(0)
    time.sleep(0.2)
print(open(path, errors="replace").read() if __import__("os").path.exists(path) else "")
raise SystemExit(f"did not receive {kind}: {value}")
PY
}

start_listener laptop "$TMP/laptop.json" "$TMP/laptop-files" "$TMP/laptop.out" "$TMP/laptop.err"
dotnet "$CLI" send-text --config "$TMP/phone.json" --role phone --text "phase-one-text"
wait_received "$TMP/laptop.out" text "phase-one-text"
dotnet "$CLI" send-url --config "$TMP/phone.json" --role phone --url "https://example.com/tapdrop"
wait_received "$TMP/laptop.out" url "https://example.com/tapdrop"

dd if=/dev/urandom of="$TMP/ten-meg.bin" bs=1M count=10 status=none
dotnet "$CLI" send-file --config "$TMP/phone.json" --role phone --path "$TMP/ten-meg.bin" --mime application/octet-stream
wait_received "$TMP/laptop.out" file "ten-meg.bin"
cmp "$TMP/ten-meg.bin" "$TMP/laptop-files/ten-meg.bin"

kill -TERM "$LISTENER_PID"
wait "$LISTENER_PID" || true
LISTENER_PID=""
dotnet "$CLI" send-text --config "$TMP/phone.json" --role phone --text "offline-queue-item"
start_listener laptop "$TMP/laptop.json" "$TMP/laptop-files" "$TMP/laptop.out" "$TMP/laptop.err"
wait_received "$TMP/laptop.out" text "offline-queue-item"

kill -TERM "$LISTENER_PID"
wait "$LISTENER_PID" || true
LISTENER_PID=""
start_listener phone "$TMP/phone.json" "$TMP/phone-files" "$TMP/phone.out" "$TMP/phone.err" 1 1
dotnet "$CLI" send-text --config "$TMP/laptop.json" --role laptop --text "laptop-to-phone"
wait_received "$TMP/phone.out" text "laptop-to-phone"

dd if=/dev/urandom of="$TMP/ping-regression.bin" bs=1M count=40 status=none
dotnet "$CLI" send-file --config "$TMP/laptop.json" --role laptop --path "$TMP/ping-regression.bin" --mime application/octet-stream
wait_received "$TMP/phone.out" file "ping-regression.bin"
cmp "$TMP/ping-regression.bin" "$TMP/phone-files/ping-regression.bin"

echo "E2E PASS: text, URL, 10 MiB byte-exact file, offline queue, laptop-to-phone, 40 MiB receive with 1s ping timeout"
