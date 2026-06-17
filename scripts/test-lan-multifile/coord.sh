#!/bin/bash
# Run on MACHINE A — starts the coordination server (UDP PORT + TCP relay PORT+1).
# Leave it running in its own terminal. Extra args pass through to the server.
source "$(dirname "$0")/common.sh"
require_java; require_jar

echo "Coordination server:  UDP $PORT  (TCP relay $((PORT + 1)))"
echo "  session=$SESSION  advertise this host to senders as ${SERVER_HOST}:${PORT}"
echo "  (if macOS prompts to allow incoming connections for java, allow it)"
echo
exec java -jar "$JARPATH" server --psk "$PSK" --port "$PORT" "$@"
