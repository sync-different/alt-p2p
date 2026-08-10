# LAN two-machine multi-file test

Ready-to-run scripts for validating folder transfer between two hosts on the same
subnet. **Machine A** runs the coordination server and receives; **Machine B** sends.

## 0. One-time setup

On **both** machines you need JDK 17+ and the **same** fat JAR.

- **Machine A** (this repo): the JAR is already at `target/alt-p2p-0.6.0-SNAPSHOT.jar`.
- **Machine B**: copy the JAR over, plus this whole `scripts/test-lan-multifile/` folder:
  ```bash
  scp target/alt-p2p-0.6.0-SNAPSHOT.jar  <userB>@<B-ip>:~/
  scp -r scripts/test-lan-multifile      <userB>@<B-ip>:~/
  ```
  On B, tell the scripts where the JAR is:  `export JAR=~/alt-p2p-0.6.0-SNAPSHOT.jar`
  (or just drop the JAR inside the `test-lan-multifile/` folder — it's auto-detected).

`config.sh` holds the shared `SESSION`, `PSK`, and `SERVER_HOST` (machine A's LAN IP,
preset to `192.168.1.243`). **Keep `config.sh` identical on both machines.** Edit
`SERVER_HOST` if A's IP differs, and re-copy. If A is multi-homed (more than one IP on
the subnet), use the IP of its **default-route** interface (`route -n get default`).

## 1. Machine A — start the coordination server (terminal 1)
```bash
./coord.sh
```
Allow the macOS firewall prompt for `java` if it appears. Leave running.

## 2. Machine A — start the receiver (terminal 2)
```bash
./recv.sh
```

## 3. Machine B — send (builds a sample corpus on first run)
```bash
./send.sh
```
`send.sh` prints an `scp` line — run it to copy the source manifests to A.

## 4. Machine A — verify the mirror is exact
```bash
./verify.sh
```
`RESULT: PASS` means the received tree matches the source byte-for-byte, empty
subfolders were recreated, and the symlink was correctly skipped.

## Variations to try

- **Conflicts (R4):** run `./recv.sh --on-conflict overwrite` (or `skip`, `keep-both`,
  or `ask`) with files already present in `RECV_DIR`.
- **Resume (R5):** Ctrl-C the receiver mid-transfer, then re-run `./recv.sh` and
  `./send.sh` — completed files are skipped, the partial one resumes.
- **JSON IPC:** `./recv.sh --json` / `./send.sh --json`.
- **TCP relay:** add `--allow-relay --relay-mode tcp` (or `--force-relay` once Phase 5
  lands) to both `send.sh` and `recv.sh`.

## Notes

- Send and receive can start in either order; they rendezvous at the coord server.
- Same subnet ⇒ UDP hole punching resolves directly; the relay isn't exercised unless
  you force it (Phase 5).
- To use your own folder instead of the sample corpus: `SRC_DIR=/path/to/folder ./send.sh`.
