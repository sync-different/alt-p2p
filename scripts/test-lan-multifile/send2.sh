export JARPATH="../alt-p2p-0.4.0-SNAPSHOT.jar"
export SRC_DIR=~/Downloads/hivebot-design
export SESSION=test123456.1
export PORT=9000
echo "JARPATH=$JARPATH"
echo "SRCDIR=$SRC_DIR"
echo "SESSION=$SESSION"
echo "PSK=$PSK"
echo "HOST=$SERVER_HOST"
echo "PORT=$PORT"
java -jar "$JARPATH" send \
    -f "$SRC_DIR" -s "$SESSION" --psk "$PSK" --server "${SERVER_HOST}:${PORT}" --force-relay "$@"

