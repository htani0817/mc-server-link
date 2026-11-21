package jp.mc.serverlink.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import jp.mc.serverlink.McServerLink;
import jp.mc.serverlink.logic.HostPort;
import jp.mc.serverlink.logic.LinkSession;

/**
 * config/mc_server_link/client.json の中身。Mod パックで配るときに lobbyAddress を入れておく。
 */
public final class ClientConfig {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** 最初につなぐロビーのアドレス（host:port）。空ならタイトル画面のボタンを出さない */
    public String lobbyAddress = "";
    /** ロビーの表示名 */
    public String lobbyName = "ロビー";
    /** タイトル画面の「Realms」のボタンを「ロビーに参加」に置き換える */
    public boolean titleButton = true;
    /** ゲームを起動したら、そのままロビーにつなぐ */
    public boolean autoConnect = false;
    /** 行き先のサーバーとの接続が切れたら、ロビーへ 1 回だけつなぎ直す */
    public boolean failover = true;
    /** サーバーが教えてくれるロビーのアドレスを使う（false なら常に上の lobbyAddress） */
    public boolean useServerLobby = true;

    static ClientConfig load(Path file) {
        ClientConfig c = null;
        if (Files.exists(file)) {
            try {
                c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientConfig.class);
            } catch (IOException | JsonParseException e) {
                McServerLink.LOGGER.warn("[mc-server-link] {} を読めないので既定値で動きます: {}", file, e.getMessage());
            }
        }
        if (c == null) {
            c = new ClientConfig();
            if (!Files.exists(file)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, GSON.toJson(c) + "\n", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    McServerLink.LOGGER.warn("[mc-server-link] {} を書き出せません: {}", file, e.getMessage());
                }
            }
        }
        return c;
    }

    /** 設定にあるロビー。アドレスが空か読めなければ null */
    LinkSession.Lobby configLobby() {
        if (lobbyAddress == null || lobbyAddress.isBlank()) {
            return null;
        }
        try {
            HostPort.parse(lobbyAddress);
        } catch (IllegalArgumentException e) {
            McServerLink.LOGGER.warn("[mc-server-link] client.json の lobbyAddress を読めません: {}", e.getMessage());
            return null;
        }
        return new LinkSession.Lobby(lobbyName == null || lobbyName.isBlank() ? "ロビー" : lobbyName, lobbyAddress.trim());
    }
}
