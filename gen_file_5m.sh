# Create a test file
dd if=/dev/urandom of=/tmp/alt-p2p-test-10mb.bin bs=10485760 count=5 2>&1
ls -la /tmp/alt-p2p-test-10mb.bin
sha256sum /tmp/alt-p2p-test-10mb.bin 2>/dev/null || shasum -a 256 /tmp/alt-p2p-test-10mb.bin

