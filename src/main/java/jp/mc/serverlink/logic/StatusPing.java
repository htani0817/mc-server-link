package jp.mc.serverlink.logic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * サーバーが動いているかを、マルチプレイのサーバー一覧と同じ「ステータス」の問い合わせで確かめる。
 * Minecraft に依存しないので単体テストできる。
 * <p>
 * つながらない（拒否・時間切れ・名前が引けない）なら OFFLINE。つながったが応答が読めない
 * （server.properties の enable-status=false など）なら UNKNOWN。応答が読めたら ONLINE。
 */
public final class StatusPing {
    public enum State { ONLINE, OFFLINE, UNKNOWN }

    /**
     * @param state   状態
     * @param online  いまの人数（分からなければ -1）
     * @param max     最大人数（分からなければ -1）
     * @param version サーバーの版の名前（分からなければ空）
     * @param millis  応答までの時間
     * @param error   OFFLINE / UNKNOWN の理由（ONLINE なら空）
     */
    public record Result(State state, int online, int max, String version, long millis, String error) {
        public static Result offline(String error, long millis) {
            return new Result(State.OFFLINE, -1, -1, "", millis, error);
        }

        public static Result unknown(String error, long millis) {
            return new Result(State.UNKNOWN, -1, -1, "", millis, error);
        }

        /** 移ってよい状態か（UNKNOWN は応答を止めているだけかもしれないので、移るのは止めない） */
        public boolean reachable() {
            return state != State.OFFLINE;
        }
    }

    /** ハンドシェイクに入れる版の番号。ステータスの問い合わせでは何でもよい（-1 は「分からない」の意味） */
    private static final int PROTOCOL = -1;
    private static final int MAX_RESPONSE = 1 << 20;

    private StatusPing() {
    }

    public static Result ping(HostPort address, int timeoutMillis) {
        long start = System.nanoTime();
        Socket socket = new Socket();
        try {
            try {
                InetSocketAddress target = new InetSocketAddress(address.host(), address.port());
                if (target.isUnresolved()) {
                    return Result.offline("名前を引けません: " + address.host(), elapsed(start));
                }
                socket.connect(target, timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
            } catch (IOException e) {
                return Result.offline(describe(e), elapsed(start));
            }
            try {
                OutputStream out = socket.getOutputStream();
                out.write(handshake(address));
                // ステータスの要求（中身なし、id=0）
                out.write(new byte[] {1, 0});
                out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream());
                int length = readVarInt(in);
                if (length <= 0 || length > MAX_RESPONSE) {
                    return Result.unknown("応答の長さがおかしい: " + length, elapsed(start));
                }
                byte[] body = new byte[length];
                in.readFully(body);
                return parse(body, elapsed(start));
            } catch (IOException | RuntimeException e) {
                return Result.unknown(describe(e), elapsed(start));
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 閉じられなくても結果は変わらない
            }
        }
    }

    /** 応答のパケット（id + JSON の文字列）を読む */
    static Result parse(byte[] body, long millis) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body));
        int id = readVarInt(in);
        if (id != 0) {
            return Result.unknown("応答のパケットが違う: " + id, millis);
        }
        int len = readVarInt(in);
        if (len < 0 || len > body.length) {
            return Result.unknown("応答の文字列の長さがおかしい: " + len, millis);
        }
        byte[] json = new byte[len];
        in.readFully(json);
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        int online = -1;
        int max = -1;
        if (root.get("players") instanceof JsonObject players) {
            online = intOr(players.get("online"));
            max = intOr(players.get("max"));
        }
        String version = "";
        if (root.get("version") instanceof JsonObject v && v.get("name") != null && v.get("name").isJsonPrimitive()) {
            version = v.get("name").getAsString();
        }
        return new Result(State.ONLINE, online, max, version, millis, "");
    }

    private static int intOr(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : -1;
    }

    static byte[] handshake(HostPort address) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0); // パケット id
        writeVarInt(body, PROTOCOL);
        byte[] host = address.host().getBytes(StandardCharsets.UTF_8);
        writeVarInt(body, host.length);
        body.writeBytes(host);
        body.write((address.port() >> 8) & 0xFF);
        body.write(address.port() & 0xFF);
        writeVarInt(body, 1); // 次は「ステータス」
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, body.size());
        packet.writeBytes(body.toByteArray());
        return packet.toByteArray();
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("途中で切れました");
            }
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt が長すぎます");
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }
}
