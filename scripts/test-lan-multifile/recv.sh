#!/bin/bash
# Run on MACHINE A (in a second terminal, after coord.sh is up) — receives the folder.
# Extra args pass through, e.g.:  ./recv.sh --on-conflict overwrite   or   ./recv.sh --json
source "$(dirname "$0")/common.sh"
require_java; require_jar

mkdir -p "$RECV_DIR"
echo "Receiving into: $RECV_DIR"
echo "  session=$SESSION  server=${SERVER_HOST}:${PORT}"
echo
java -jar "$JARPATH" receive \
    -o "$RECV_DIR" -s "$SESSION" --psk "$PSK" --server "${SERVER_HOST}:${PORT}" "$@"
echo
echo "Done. Verify with:  ./verify.sh   (after scp'ing the source manifests from B)"
