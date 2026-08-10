# Shared helpers — sourced by coord.sh / send.sh / recv.sh / verify.sh.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=config.sh
source "$HERE/config.sh"

require_java() {
    command -v java >/dev/null 2>&1 || { echo "ERROR: java (JDK 17+) not found on PATH" >&2; exit 1; }
}

# Locate the fat JAR. Order: $JAR env, repo target/, beside these scripts.
find_jar() {
    if [ -n "${JAR:-}" ] && [ -f "$JAR" ]; then echo "$JAR"; return; fi
    local root; root="$(cd "$HERE/../.." 2>/dev/null && pwd)"
    for pat in "$root"/target/alt-p2p-*-SNAPSHOT.jar "$HERE"/alt-p2p*.jar "$HERE"/../alt-p2p*.jar; do
        for f in $pat; do
            [ -f "$f" ] || continue
            case "$f" in *original*) continue;; esac
            echo "$f"; return
        done
    done
    echo ""
}

require_jar() {
    JARPATH="$(find_jar)"
    if [ -z "$JARPATH" ]; then
        echo "ERROR: alt-p2p JAR not found." >&2
        echo "  Set JAR=/path/to/alt-p2p-0.6.0-SNAPSHOT.jar, or drop the JAR beside these scripts." >&2
        exit 1
    fi
    echo "Using JAR: $JARPATH"
}
