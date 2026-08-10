#./sethost.sh
export SERVER_HOST=192.168.1.243
export JARPATH="../../target/alt-p2p-0.6.0-SNAPSHOT.jar"
echo JARPATH=$JARPATH
export RECV_DIR=~/Downloads/alt-p2p-test-00
export SESSION=test123456.16
export PORT=9000
export PSK=DaleDale
echo "RECV_DIR=$RECV_DIR"
echo "SESSION=$SESSION"
echo "PSK=$PSK"
echo "SERVER_HOST=$SERVER_HOST"
echo "PORT=$PORT"
java -jar "$JARPATH" receive \
    -o "$RECV_DIR" -s "$SESSION" --psk "$PSK" --server "${SERVER_HOST}:${PORT}" "$@"

