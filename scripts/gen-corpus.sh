#!/bin/bash
# gen-corpus.sh — build a multi-file test tree for alt-p2p multi-file testing.
#
# Exercises every decision in internal/PLAN_MULTIFILE.md §11.3:
#   nested dirs, varied sizes, a 0-byte file, EMPTY subfolders (Q1), a SYMLINK (Q5).
#
# Usage:
#   scripts/gen-corpus.sh [TARGET_DIR] [--big]
#     TARGET_DIR  where to build the tree (default: /tmp/p2p-src)
#     --big       also add a ~1 GB file for the scale/throughput test (T14)
#
# Re-running wipes and rebuilds TARGET_DIR. Refuses unsafe paths.
set -euo pipefail

TARGET="${1:-/tmp/p2p-src}"
BIG=0
[ "${2:-}" = "--big" ] && BIG=1

# Safety: never operate on root, home, or empty.
case "$TARGET" in
  ""|"/"|"$HOME"|"$HOME/") echo "refusing unsafe target: '$TARGET'" >&2; exit 1 ;;
esac

rand() { # rand <outfile> <bytes>
  head -c "$2" /dev/urandom > "$1"
}

echo "Building corpus at: $TARGET"
rm -rf "$TARGET"
mkdir -p "$TARGET"/{docs,media/sub,bin,empty-a,empty-b/nested}

echo "hello world"                 > "$TARGET/root.txt"
printf 'a%.0s' $(seq 1 2048)       > "$TARGET/docs/notes.txt"   # 2 KB text
: > "$TARGET/docs/zero.bin"                                     # 0-byte file (T2)
rand "$TARGET/bin/small.bin"   1048576                          # 1 MB
rand "$TARGET/media/sub/big.bin" 8388608                        # 8 MB
rand "$TARGET/media/clip.bin"  524288                           # 512 KB
ln -s ../bin/small.bin "$TARGET/media/link.bin"                 # symlink -> should be SKIPPED (Q5)

if [ "$BIG" = "1" ]; then
  echo "  + adding ~1 GB file (this takes a moment)…"
  rand "$TARGET/media/sub/huge.bin" 1073741824
fi

# empty-a and empty-b/nested intentionally contain no files (Q1 — must be recreated on receiver)

echo
echo "Summary:"
echo "  files: $(find "$TARGET" -type f -not -type l | wc -l | tr -d ' ')"
echo "  dirs:  $(find "$TARGET" -type d | wc -l | tr -d ' ')"
echo "  links: $(find "$TARGET" -type l | wc -l | tr -d ' ') (expected absent on receiver)"
echo "  bytes: $(find "$TARGET" -type f -not -type l -exec cat {} + 2>/dev/null | wc -c | tr -d ' ')"
echo
echo "Tree:"
find "$TARGET" | sort | sed "s#^$TARGET#  .#"
