#!/bin/bash
# Run on MACHINE B — builds a sample corpus (if SRC_DIR is missing), writes a
# source manifest, and sends the folder to machine A's coordination server.
# Extra args pass through, e.g.:  ./send.sh --allow-relay --relay-mode tcp
source "$(dirname "$0")/common.sh"
require_java; require_jar

if [ ! -d "$SRC_DIR" ]; then
    echo "Building sample corpus at $SRC_DIR ..."
    mkdir -p "$SRC_DIR"/{docs,media/sub,empty1,empty2/nested}
    echo "hello world"               > "$SRC_DIR/root.txt"
    : > "$SRC_DIR/docs/zero.bin"                                   # 0-byte
    head -c 1048576  /dev/urandom    > "$SRC_DIR/docs/a.bin"       # 1 MB
    head -c 5242880  /dev/urandom    > "$SRC_DIR/media/sub/big.bin" # 5 MB
    ln -s ../docs/a.bin "$SRC_DIR/media/link.bin" 2>/dev/null || true  # skipped
    # empty1 and empty2/nested intentionally have no files
fi

# Source manifest for verification (excludes symlinks).
( cd "$SRC_DIR" && find . -type f -not -type l | sort | while IFS= read -r f; do shasum -a256 "$f"; done ) > "$SRC_FILES_MANIFEST"
( cd "$SRC_DIR" && find . -type d | sort ) > "$SRC_DIRS_MANIFEST"

echo "Source: $SRC_DIR"
echo "  files: $(find "$SRC_DIR" -type f -not -type l | wc -l | tr -d ' ')   dirs: $(find "$SRC_DIR" -type d | wc -l | tr -d ' ')   links(skipped): $(find "$SRC_DIR" -type l | wc -l | tr -d ' ')"
echo "  manifests: $SRC_FILES_MANIFEST  $SRC_DIRS_MANIFEST"
echo
echo "After the transfer, copy the manifests to machine A so verify.sh can compare:"
echo "    scp $SRC_FILES_MANIFEST $SRC_DIRS_MANIFEST <userA>@${SERVER_HOST}:/tmp/"
echo
echo "Sending -> ${SERVER_HOST}:${PORT}  (session=$SESSION)"
echo
java -jar "$JARPATH" send \
    -f "$SRC_DIR" -s "$SESSION" --psk "$PSK" --server "${SERVER_HOST}:${PORT}" "$@"
