#java -Dorg.slf4j.simpleLogger.log.com.alterante.p2p.net.DtlsHandler=debug -jar target/alt-p2p-0.2.0-SNAPSHOT.jar receive -o /tmp/out --session session27 --psk secret --server 89.144.2.81:9000

/Applications/alt-p2p.app/Contents/MacOS/run-java \
  -jar /Applications/alt-p2p.app/Contents/Resources/alt-p2p.jar \
  receive --json -s session29 --psk secret --server 89.144.2.81:9000 -o /tmp/recv

