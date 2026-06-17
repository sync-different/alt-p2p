export JARPATH="../../target/alt-p2p-0.4.0-SNAPSHOT.jar"
echo JARPATH=$JARPATH
export RECV_DIR=~/Downloads/tester0616j
export SESSION=test123456.11
export PORT=9000
echo "RECV_DIR=$RECV_DIR"
echo "SESSION=$SESSION"
echo "PSK=$PSK"
echo "SERVER_HOST=$SERVER_HOST"
echo "PORT=$PORT"
java -jar "$JARPATH" receive \
    -o "$RECV_DIR" -s "$SESSION" --psk "$PSK" --server "${SERVER_HOST}:${PORT}" "$@"

