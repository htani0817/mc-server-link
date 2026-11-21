package jp.mc.serverlink.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 手元に偽のサーバーを立てて、ステータスの問い合わせを確かめる */
class StatusPingTest {
    private static final String JSON = "{\"version\":{\"name\":\"Youer 26.2\",\"protocol\":0},"
            + "\"players\":{\"max\":20,\"online\":3},\"description\":{\"text\":\"ロビー\"}}";

    /** 1 回だけ受けて、handler に渡す偽のサーバー */
    private interface Handler {
        void handle(Socket s) throws IOException;
    }

    private static int serveOnce(ServerSocket ss, Handler h) {
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                h.handle(s);
            } catch (IOException ignored) {
                // テストの終わりに閉じられる
            }
        });
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    @Test
    void 応答が読めれば動いている() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = serveOnce(ss, s -> {
                DataInputStream in = new DataInputStream(s.getInputStream());
                // ハンドシェイク
                int len = StatusPing.readVarInt(in);
                byte[] hs = new byte[len];
                in.readFully(hs);
                DataInputStream h = new DataInputStream(new java.io.ByteArrayInputStream(hs));
                assertEquals(0, StatusPing.readVarInt(h));
                StatusPing.readVarInt(h); // 版
                int hostLen = StatusPing.readVarInt(h);
                byte[] host = new byte[hostLen];
                h.readFully(host);
                assertEquals("127.0.0.1", new String(host, StandardCharsets.UTF_8));
                assertEquals(s.getLocalPort(), h.readUnsignedShort());
                assertEquals(1, StatusPing.readVarInt(h));
                // ステータスの要求
                assertEquals(1, StatusPing.readVarInt(in));
                assertEquals(0, StatusPing.readVarInt(in));
                byte[] json = JSON.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                StatusPing.writeVarInt(body, 0);
                StatusPing.writeVarInt(body, json.length);
                body.writeBytes(json);
                ByteArrayOutputStream pkt = new ByteArrayOutputStream();
                StatusPing.writeVarInt(pkt, body.size());
                pkt.writeBytes(body.toByteArray());
                OutputStream out = s.getOutputStream();
                out.write(pkt.toByteArray());
                out.flush();
            });
            StatusPing.Result r = StatusPing.ping(new HostPort("127.0.0.1", port), 2000);
            assertEquals(StatusPing.State.ONLINE, r.state(), r.error());
            assertEquals(3, r.online());
            assertEquals(20, r.max());
            assertEquals("Youer 26.2", r.version());
            assertTrue(r.reachable());
        }
    }

    @Test
    void つながらなければ止まっている() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = ss.getLocalPort();
        }
        StatusPing.Result r = StatusPing.ping(new HostPort("127.0.0.1", port), 1000);
        assertEquals(StatusPing.State.OFFLINE, r.state());
        assertFalse(r.reachable());
    }

    @Test
    void つながるが何も返さなければ不明で_移るのは止めない() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = serveOnce(ss, s -> s.getInputStream().read(new byte[64]));
            StatusPing.Result r = StatusPing.ping(new HostPort("127.0.0.1", port), 500);
            assertEquals(StatusPing.State.UNKNOWN, r.state());
            assertTrue(r.reachable());
        }
    }

    @Test
    void 名前が引けなければ止まっている() {
        StatusPing.Result r = StatusPing.ping(new HostPort("no-such-host.invalid", 25565), 1000);
        assertEquals(StatusPing.State.OFFLINE, r.state());
    }
}
