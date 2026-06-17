# Shared config for the two-machine LAN multi-file test.
# COPY THIS WHOLE FOLDER to BOTH machines unchanged — SESSION and PSK must match.
#
# Each value can be overridden via the environment if needed.

# Must be identical on both machines:
export SESSION="${SESSION:-815900c8142ae3e25f1dc838c5ad32d9}"
export PSK="${PSK:-lan-test-424a62}"

# Machine A = the host running coord.sh (coordination server). Set to its LAN IP.
# IMPORTANT: if machine A is multi-homed (more than one IP on the subnet), use the
# IP of its DEFAULT-ROUTE interface — the address A actually sends from — otherwise
# the peer rejects A's hole-punch packets as coming from an "unexpected IP".
#   Find it on A:  route -n get default | awk '/interface/{print $2}'   then  ipconfig getifaddr <iface>
export SERVER_HOST="${SERVER_HOST:-192.168.1.243}"
export PORT="${PORT:-9000}"            # TCP relay uses PORT+1

# Per-machine paths:
export SRC_DIR="${SRC_DIR:-$HOME/p2p-src}"      # sender (machine B): folder to send
export RECV_DIR="${RECV_DIR:-/tmp/p2p-recv}"    # receiver (machine A): output dir

# Manifests (written by send.sh on B; scp'd to A and read by verify.sh):
export SRC_FILES_MANIFEST="${SRC_FILES_MANIFEST:-/tmp/p2p-lan-src-files.txt}"
export SRC_DIRS_MANIFEST="${SRC_DIRS_MANIFEST:-/tmp/p2p-lan-src-dirs.txt}"
