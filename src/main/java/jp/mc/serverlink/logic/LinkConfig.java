package jp.mc.serverlink.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * サーバー側の設定（config/mc_server_link/servers.json）の中身。
 * つなぐサーバー全部に同じ一覧を置き、{@link #thisServer} だけをサーバーごとに変える。
 */
public final class LinkConfig {
    /** このサーバーの id（servers の中のどれか） */
    public String thisServer = "lobby";
    /** ロビーの id（servers の中のどれか）。最初につなぐ先で、行き先が落ちたときの戻り先 */
    public String lobby = "lobby";
    /** つなぐサーバーの一覧。並び順がメニュー画面の並び順になる */
    public List<Entry> servers = new ArrayList<>(List.of(
            new Entry("lobby", "ロビー", "最初に入る待機所", "127.0.0.1:25565"),
            new Entry("survival", "サバイバル", "メインのワールド", "127.0.0.1:25566")));
    /** ほかのサーバーが動いているかを確かめる間隔（秒） */
    public int statusIntervalSeconds = 10;
    /** 確かめるときの待ち時間（ミリ秒） */
    public int pingTimeoutMillis = 2000;
    /** ロビー以外のサーバーを止めるとき（/stop）に、入っている人をロビーへ送る */
    public boolean sendToLobbyOnStop = true;
    /** ロビー以外のサーバーへ直接入ってきた人（transfer で来ていない人）をロビーへ送る */
    public boolean sendDirectJoinsToLobby = true;
    /** 上の「直接入ってきた人をロビーへ送る」から OP（権限 2 以上）を外す */
    public boolean directJoinOpsBypass = true;
    /** 1 人が続けて移動を頼めない間（秒） */
    public int moveCooldownSeconds = 3;

    public static final class Entry {
        /** コマンドで使う名前（英小文字・数字・_ と -） */
        public String id;
        /** 画面に出す名前 */
        public String name;
        /** 画面に出す説明 */
        public String description = "";
        /** プレイヤーがつなぐアドレス（host:port）。transfer で送る先 */
        public String address;
        /** 動いているかを確かめるアドレス。空なら address。サーバーから見た内向きのアドレスが違うときに使う */
        public String statusAddress = "";

        public Entry() {
        }

        public Entry(String id, String name, String description, String address) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.address = address;
        }

        public String displayName() {
            return name == null || name.isBlank() ? id : name;
        }

        public HostPort joinAddress() {
            return HostPort.parse(address);
        }

        public HostPort pingAddress() {
            return statusAddress == null || statusAddress.isBlank() ? joinAddress() : HostPort.parse(statusAddress);
        }
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public Optional<Entry> find(String id) {
        if (id == null || servers == null) {
            return Optional.empty();
        }
        return servers.stream().filter(e -> e != null && id.equals(e.id)).findFirst();
    }

    public Entry self() {
        return find(thisServer).orElseThrow();
    }

    public Entry lobbyEntry() {
        return find(lobby).orElseThrow();
    }

    public boolean isLobby() {
        return lobby != null && lobby.equals(thisServer);
    }

    /** 間違いの一覧。空なら使える */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (servers == null || servers.isEmpty()) {
            errors.add("servers が空です");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < servers.size(); i++) {
            Entry e = servers.get(i);
            String where = "servers[" + i + "]";
            if (e == null) {
                errors.add(where + " が空です");
                continue;
            }
            if (e.id == null || !ID.matcher(e.id).matches()) {
                errors.add(where + ".id は英小文字・数字・_・- の 1〜32 文字にしてください: " + e.id);
            } else if (!ids.add(e.id)) {
                errors.add(where + ".id が重複しています: " + e.id);
            }
            try {
                e.joinAddress();
            } catch (IllegalArgumentException ex) {
                errors.add(where + ".address: " + ex.getMessage());
            }
            if (e.statusAddress != null && !e.statusAddress.isBlank()) {
                try {
                    HostPort.parse(e.statusAddress);
                } catch (IllegalArgumentException ex) {
                    errors.add(where + ".statusAddress: " + ex.getMessage());
                }
            }
        }
        if (find(thisServer).isEmpty()) {
            errors.add("thisServer「" + thisServer + "」が servers にありません");
        }
        if (find(lobby).isEmpty()) {
            errors.add("lobby「" + lobby + "」が servers にありません");
        }
        if (statusIntervalSeconds < 2) {
            errors.add("statusIntervalSeconds は 2 以上にしてください");
        }
        if (pingTimeoutMillis < 200 || pingTimeoutMillis > 10000) {
            errors.add("pingTimeoutMillis は 200〜10000 にしてください");
        }
        if (moveCooldownSeconds < 0) {
            errors.add("moveCooldownSeconds は 0 以上にしてください");
        }
        return errors;
    }
}
