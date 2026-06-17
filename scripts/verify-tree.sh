#!/bin/bash
# verify-tree.sh — assert a received tree mirrors the source exactly.
#
# Implements internal/PLAN_MULTIFILE.md §11.5: compares path+SHA-256 of every
# regular file and the full directory list between SRC and DST. Symlinks are
# excluded from SRC (Q5) and must be ABSENT in DST.
#
# Usage:  scripts/verify-tree.sh SRC_DIR DST_DIR
# Exit:   0 = identical (PASS), 1 = mismatch (FAIL)
set -euo pipefail

SRC="${1:?usage: verify-tree.sh SRC_DIR DST_DIR}"
DST="${2:?usage: verify-tree.sh SRC_DIR DST_DIR}"

manifest() { # manifest <dir> ; prints "<sha256>  <relpath>" per file, sorted
  ( cd "$1" && find . -type f -not -type l | LC_ALL=C sort | while IFS= read -r f; do
      shasum -a 256 "$f"
    done )
}
dirs() { ( cd "$1" && find . -type d | LC_ALL=C sort ); }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

manifest "$SRC" > "$tmp/src-files.txt"
manifest "$DST" > "$tmp/dst-files.txt"
dirs "$SRC" > "$tmp/src-dirs.txt"
dirs "$DST" > "$tmp/dst-dirs.txt"

rc=0

if diff -u "$tmp/src-files.txt" "$tmp/dst-files.txt" > "$tmp/files.diff"; then
  echo "FILES: identical (paths + SHA-256)  [$(wc -l < "$tmp/src-files.txt" | tr -d ' ') files]"
else
  echo "FILES: MISMATCH"; sed 's/^/  /' "$tmp/files.diff"; rc=1
fi

if diff -u "$tmp/src-dirs.txt" "$tmp/dst-dirs.txt" > "$tmp/dirs.diff"; then
  echo "DIRS:  identical (incl. empty)      [$(wc -l < "$tmp/src-dirs.txt" | tr -d ' ') dirs]"
else
  echo "DIRS:  MISMATCH"; sed 's/^/  /' "$tmp/dirs.diff"; rc=1
fi

# Symlinks must not have been recreated on the receiver.
links="$(cd "$DST" && find . -type l 2>/dev/null | wc -l | tr -d ' ')"
if [ "$links" = "0" ]; then
  echo "LINKS: none on receiver (correct — symlinks skipped)"
else
  echo "LINKS: FAIL — $links symlink(s) present on receiver"; rc=1
fi

echo
[ "$rc" = "0" ] && echo "RESULT: PASS" || echo "RESULT: FAIL"
exit $rc
