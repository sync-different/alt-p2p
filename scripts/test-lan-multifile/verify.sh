#!/bin/bash
# Run on MACHINE A after recv.sh completes AND the source manifests have been
# scp'd from machine B. Compares the received tree to the source by path+SHA-256.
source "$(dirname "$0")/common.sh"

if [ ! -f "$SRC_FILES_MANIFEST" ] || [ ! -f "$SRC_DIRS_MANIFEST" ]; then
    echo "ERROR: source manifests not found:" >&2
    echo "  $SRC_FILES_MANIFEST" >&2
    echo "  $SRC_DIRS_MANIFEST" >&2
    echo "scp them from machine B first (see send.sh output)." >&2
    exit 1
fi

dstFiles=/tmp/p2p-lan-dst-files.txt
dstDirs=/tmp/p2p-lan-dst-dirs.txt
( cd "$RECV_DIR" && find . -type f | sort | while IFS= read -r f; do shasum -a256 "$f"; done ) > "$dstFiles"
( cd "$RECV_DIR" && find . -type d | sort ) > "$dstDirs"

rc=0
if diff -u "$SRC_FILES_MANIFEST" "$dstFiles" > /tmp/p2p-lan-files.diff; then
    echo "FILES: identical ($(wc -l < "$SRC_FILES_MANIFEST" | tr -d ' ') files, paths+SHA-256)"
else
    echo "FILES: MISMATCH"; sed 's/^/  /' /tmp/p2p-lan-files.diff; rc=1
fi
if diff -u "$SRC_DIRS_MANIFEST" "$dstDirs" > /tmp/p2p-lan-dirs.diff; then
    echo "DIRS:  identical (incl. empty subfolders)"
else
    echo "DIRS:  MISMATCH"; sed 's/^/  /' /tmp/p2p-lan-dirs.diff; rc=1
fi
links="$(cd "$RECV_DIR" && find . -type l 2>/dev/null | wc -l | tr -d ' ')"
if [ "$links" = "0" ]; then echo "LINKS: none on receiver (correct — symlinks skipped)";
else echo "LINKS: FAIL — $links symlink(s) present"; rc=1; fi

echo
[ "$rc" = "0" ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit $rc
