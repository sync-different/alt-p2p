#export SERVER_HOST=89.144.2.81
export JARPATH="../../target/alt-p2p-0.6.0-SNAPSHOT.jar"
export PSK="DaleDale"
export RECV_DIR=~/Downloads/tester0616k
export SESSION=test123456.14
export PORT=9000
echo "JARPATH=$JARPATH"
echo "RECV_DIR=$RECV_DIR"
echo "SESSION=$SESSION"
echo "PSK=$PSK"
echo "SERVER_HOST=$SERVER_HOST"
echo "PORT=$PORT"
java -jar "$JARPATH" server --psk "$PSK" --port $PORT

