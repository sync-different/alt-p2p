package com.alterante.p2p.net.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.Executor;

/**
 * Splices a local TCP {@link Socket} to a {@link StreamMux.MuxStream}, copying bytes
 * both ways until either side closes, then tearing both down. The building block of
 * the forwarders.
 */
final class Bridge {

    private Bridge() {}

    static void bridge(Socket sock, StreamMux.MuxStream stream, Executor exec) throws IOException {
        final InputStream sockIn = sock.getInputStream();
        final OutputStream sockOut = sock.getOutputStream();
        exec.execute(() -> { copy(sockIn, stream.out()); closeBoth(sock, stream); });
        exec.execute(() -> { copy(stream.in(), sockOut); closeBoth(sock, stream); });
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // one side closed; the finally in bridge() tears the other down
        }
    }

    private static void closeBoth(Socket sock, StreamMux.MuxStream stream) {
        try { sock.close(); } catch (IOException ignored) {}
        stream.close();
    }
}
