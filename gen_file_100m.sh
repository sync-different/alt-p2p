# Create a test file
dd if=/dev/urandom of=/tmp/alt-p2p-test-100mb.bin bs=1048576 count=100 2>&1
ls -la /tmp/alt-p2p-test-100mb.bin
sha256sum /tmp/alt-p2p-test-100mb.bin 2>/dev/null || shasum -a 256 /tmp/alt-p2p-test-100mb.bin

