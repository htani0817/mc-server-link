package jp.mc.serverlink.logic;

/**
 * 「host:port」の形のアドレス。ポートを省いたら 25565。IPv6 は「[::1]:25565」のように角かっこで囲む。
 */
public record HostPort(String host, int port) {
    public static final int DEFAULT_PORT = 25565;

    public HostPort {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("ホスト名が空です");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("ポートは 1〜65535 です: " + port);
        }
    }

    /** 読めない形なら IllegalArgumentException */
    public static HostPort parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("アドレスが空です");
        }
        String s = text.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("アドレスが空です");
        }
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("「]」がありません: " + text);
            }
            String host = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (rest.isEmpty()) {
                return new HostPort(host, DEFAULT_PORT);
            }
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("「]」のあとは「:ポート」です: " + text);
            }
            return new HostPort(host, parsePort(rest.substring(1), text));
        }
        int first = s.indexOf(':');
        if (first < 0) {
            return new HostPort(s, DEFAULT_PORT);
        }
        if (first != s.lastIndexOf(':')) {
            // 角かっこの無い IPv6（ポートは付けられない）
            return new HostPort(s, DEFAULT_PORT);
        }
        return new HostPort(s.substring(0, first), parsePort(s.substring(first + 1), text));
    }

    private static int parsePort(String p, String whole) {
        try {
            return Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ポートが数字ではありません: " + whole);
        }
    }

    @Override
    public String toString() {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }
}
