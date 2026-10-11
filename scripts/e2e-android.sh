#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
RELAY_PID=""
LISTENER_PID=""
PHONE_TEST_PID=""

cleanup() {
  local result=$?
  if [[ "$result" -ne 0 && -f "$TMP/android-test.log" ]]; then
    cat "$TMP/android-test.log"
  fi
  for pid in "$PHONE_TEST_PID" "$LISTENER_PID" "$RELAY_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
    fi
  done
  (cd "$ROOT/android" && ./gradlew --stop >/dev/null 2>&1) || true
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

dotnet "$CLI" pair-new --relay http://127.0.0.1:8787 --name "TapDrop Laptop E2E" --out "$TMP/laptop.json" >"$TMP/qr.txt"
QR="$(<"$TMP/qr.txt")"
mkdir -p "$TMP/laptop-files"
dotnet "$CLI" listen --config "$TMP/laptop.json" --role laptop --save-dir "$TMP/laptop-files" >"$TMP/laptop.out" 2>"$TMP/laptop.err" &
LISTENER_PID=$!
python3 - "$TMP/laptop.err" <<'PY'
import os, sys, time
path = sys.argv[1]
for _ in range(150):
    text = open(path, errors="replace").read() if os.path.exists(path) else ""
    if "Connected to relay" in text:
        raise SystemExit(0)
    time.sleep(0.2)
print(open(path, errors="replace").read() if os.path.exists(path) else "")
raise SystemExit("laptop listener did not connect")
PY

mkdir -p "$TMP/android-files"
dd if=/dev/urandom of="$TMP/laptop-to-android-5m.bin" bs=1M count=5 status=none
cd "$ROOT/android"
./gradlew :app:testDebugUnitTest \
  -PtapdropInteropE2E=true \
  -PinteropQr="$QR" \
  -PinteropDir="$TMP" \
  -PinteropLaptopFile="$TMP/laptop-to-android-5m.bin" \
  --tests dev.peytonli.tapdrop.core.InteropE2ETest >"$TMP/android-test.log" 2>&1 &
PHONE_TEST_PID=$!
cd "$ROOT"

python3 - "$TMP" "$PHONE_TEST_PID" <<'PY'
import os, subprocess, sys, time
directory, pid = sys.argv[1:]
marker = os.path.join(directory, "android-sent")
for _ in range(900):
    if os.path.exists(marker):
        raise SystemExit(0)
    try:
        os.kill(int(pid), 0)
    except ProcessLookupError:
        break
    time.sleep(0.2)
log = os.path.join(directory, "android-test.log")
print(open(log, errors="replace").read() if os.path.exists(log) else "")
raise SystemExit("Android interop test did not send its phone-to-laptop messages")
PY

python3 - "$TMP/laptop.out" <<'PY'
import json, os, sys, time
path = sys.argv[1]
expected = {
    ("text", "android-to-laptop-text"),
    ("url", "https://example.com/tapdrop-android-e2e"),
    ("file", "android-to-laptop-5m.bin"),
}
for _ in range(300):
    found = set()
    try:
        lines = open(path, errors="replace").read().splitlines()
    except FileNotFoundError:
        lines = []
    for line in lines:
        try:
            item = json.loads(line)["item"]
        except (ValueError, KeyError):
            continue
        if item.get("kind") == "text":
            found.add(("text", item.get("text")))
        elif item.get("kind") == "url":
            found.add(("url", item.get("url")))
        elif item.get("kind") == "file":
            found.add(("file", item.get("blob", {}).get("name")))
    if expected <= found:
        raise SystemExit(0)
    time.sleep(0.2)
print(open(path, errors="replace").read() if os.path.exists(path) else "")
raise SystemExit("laptop listener did not receive all Android items")
PY
cmp "$TMP/android-to-laptop-5m.bin" "$TMP/laptop-files/android-to-laptop-5m.bin"

dotnet "$CLI" send-text --config "$TMP/laptop.json" --role laptop --text "laptop-to-android-text"
dotnet "$CLI" send-file --config "$TMP/laptop.json" --role laptop --path "$TMP/laptop-to-android-5m.bin" --mime application/octet-stream
if ! wait "$PHONE_TEST_PID"; then
  cat "$TMP/android-test.log"
  exit 1
fi
PHONE_TEST_PID=""

echo "ANDROID E2E PASS: phone-to-laptop text, URL, 5 MiB byte-exact file; laptop-to-phone text and 5 MiB byte-exact file"
