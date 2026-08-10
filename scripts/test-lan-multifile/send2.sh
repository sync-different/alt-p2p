#export JARPATH="../alt-p2p-0.6.0-SNAPSHOT.jar"
export JARPATH="../../target/alt-p2p-0.6.0-SNAPSHOT.jar"
export SRC_DIR=~/Downloads/alt-p2p-test-01
export SESSION=test123456.18
export PORT=9000
export SERVER_HOST=192.168.1.243
export PSK=DaleDale
echo "JARPATH=$JARPATH"
echo "SRCDIR=$SRC_DIR"
echo "SESSION=$SESSION"
echo "PSK=$PSK"
echo "HOST=$SERVER_HOST"
echo "PORT=$PORT"
java -jar "$JARPATH" send \
    -f "$SRC_DIR" -s "$SESSION" --psk "$PSK" --server "${SERVER_HOST}:${PORT}" --force-relay "$@"

