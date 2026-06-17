#!/bin/bash
# loopback.sh — single-machine dev loop for alt-p2p transfers.
#
# Spawns coordination server + receiver + sender on 127.0.0.1, runs one
# transfer, verifies the result, and tears everything down. This is the
# FIRST-LEVEL test for each multi-file phase (internal/PLAN_MULTIFILE.md §11.0).
#
# Works today in single-file mode against the current JAR; once Phase 2 lands
# (folder send), point SRC at a directory and it verifies the full tree.
#
# Usage:
#   scripts/loopback.sh [SRC]
#     SRC   file or directory to send (default: a freshly generated corpus dir)
#
# Env knobs:
#   JAR=...            override the JAR (default: newest target/alt-p2p-*-SNAPSHOT.jar)
#   OUTDIR=...         receiver output dir (default: /tmp/p2p-recv, wiped each run)
#   PORT=9000          coord UDP port (TCP relay = PORT+1)
#   PSK=secret         pre-shared key
#   SESSION=...        session id (default: random 128-bit)
#   RELAY=1            add --force-relay to both peers (Phase 5+)
#   JSON=1             add --json to both peers
#   BUILD=1            run `mvn -q package` first
#   TIMEOUT=180        per-transfer wall-clock limit (seconds)
#   SEND_ARGS / RECV_ARGS   extra args appended to sender / receiver
#
# Exit: 0 = transfer + verification PASS, non-zero otherwise.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PORT="${PORT:-9000}"
PSK="${PSK:-secret}"
SESSION="${SESSION:-$(openssl rand -hex 16)}"
OUTDIR="${OUTDIR:-/tmp/p2p-recv}"
TIMEOUT="${TIMEOUT:-180}"
LOGDIR="$(mktemp -d /tmp/p2p-loopback.XXXX)"

if [ "${BUILD:-0}" = "1" ]; then
  echo "==> mvn -q package"; mvn -q package || { echo "build failed"; exit 1; }
fi

JAR="${JAR:-$(ls -t "$ROOT"/target/alt-p2p-*-SNAPSHOT.jar 2>/dev/null | grep -v original- | head -1)}"
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || { echo "no JAR found in target/ (run with BUILD=1)"; exit 1; }

# SRC: default to a generated corpus.
SRC="${1:-}"
if [ -z "$SRC" ]; then
  SRC=/tmp/p2p-src
  "$ROOT/scripts/gen-corpus.sh" "$SRC" >/dev/null
fi
[ -e "$SRC" ] || { echo "SRC not found: $SRC"; exit 1; }

EXTRA=()
[ "${RELAY:-0}" = "1" ] && EXTRA+=(--force-relay)
[ "${JSON:-0}" = "1" ]  && EXTRA+=(--json)

echo "JAR     : $JAR"
echo "SRC     : $SRC ($([ -d "$SRC" ] && echo directory || echo file))"
echo "OUTDIR  : $OUTDIR"
echo "SESSION : $SESSION   PORT: $PORT   RELAY: ${RELAY:-0}"
echo "LOGS    : $LOGDIR"
echo

rm -rf "$OUTDIR"; mkdir -p "$OUTDIR"

COORD_PID=""; RECV_PID=""; SEND_PID=""
cleanup() {
  for p in "$SEND_PID" "$RECV_PID" "$COORD_PID"; do
    [ -n "$p" ] && kill "$p" 2>/dev/null
  done
}
trap cleanup EXIT

# Free a stale process holding the port, if any.
lsof -ti :"$PORT" 2>/dev/null | xargs kill 2>/dev/null || true

echo "==> coord server"
java -jar "$JAR" server --psk "$PSK" --port "$PORT" >"$LOGDIR/coord.log" 2>&1 &
COORD_PID=$!
sleep 1.5
kill -0 "$COORD_PID" 2>/dev/null || { echo "coord failed to start:"; cat "$LOGDIR/coord.log"; exit 1; }

echo "==> receiver"
java -jar "$JAR" receive -o "$OUTDIR" -s "$SESSION" --psk "$PSK" \
  --server "127.0.0.1:$PORT" ${EXTRA[@]+"${EXTRA[@]}"} ${RECV_ARGS:-} >"$LOGDIR/recv.log" 2>&1 &
RECV_PID=$!
sleep 0.5

echo "==> sender"
java -jar "$JAR" send -f "$SRC" -s "$SESSION" --psk "$PSK" \
  --server "127.0.0.1:$PORT" ${EXTRA[@]+"${EXTRA[@]}"} ${SEND_ARGS:-} >"$LOGDIR/send.log" 2>&1 &
SEND_PID=$!

# Wait for both peers (or timeout).
echo "==> transferring (timeout ${TIMEOUT}s)…"
elapsed=0
while kill -0 "$SEND_PID" 2>/dev/null || kill -0 "$RECV_PID" 2>/dev/null; do
  sleep 1; elapsed=$((elapsed+1))
  if [ "$elapsed" -ge "$TIMEOUT" ]; then
    echo "TIMEOUT after ${TIMEOUT}s"; echo "--- send.log ---"; tail -20 "$LOGDIR/send.log"
    echo "--- recv.log ---"; tail -20 "$LOGDIR/recv.log"; exit 2
  fi
done
wait "$SEND_PID"; SEND_RC=$?
wait "$RECV_PID"; RECV_RC=$?
echo "sender exit=$SEND_RC  receiver exit=$RECV_RC"

if [ "$SEND_RC" != "0" ] || [ "$RECV_RC" != "0" ]; then
  echo "--- send.log (tail) ---"; tail -25 "$LOGDIR/send.log"
  echo "--- recv.log (tail) ---"; tail -25 "$LOGDIR/recv.log"
  exit 1
fi

echo
echo "==> verify"
if [ -d "$SRC" ]; then
  "$ROOT/scripts/verify-tree.sh" "$SRC" "$OUTDIR"
else
  # single-file mode: compare the one file by hash
  base="$(basename "$SRC")"
  a="$(shasum -a 256 "$SRC" | awk '{print $1}')"
  b="$(shasum -a 256 "$OUTDIR/$base" 2>/dev/null | awk '{print $1}')"
  if [ -n "$b" ] && [ "$a" = "$b" ]; then echo "FILE: identical ($base)  RESULT: PASS";
  else echo "FILE: MISMATCH ($base)  src=$a dst=$b  RESULT: FAIL"; exit 1; fi
fi
