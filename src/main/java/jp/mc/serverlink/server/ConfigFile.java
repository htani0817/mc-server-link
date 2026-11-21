package jp.mc.serverlink.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jp.mc.serverlink.logic.LinkConfig;

/** config/mc_server_link/servers.json の読み書き */
final class ConfigFile {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /**
     * @param config  読めた設定（読めなければ null）
     * @param created ファイルが無かったので例を書き出した
     * @param errors  間違いの一覧
     */
    record Loaded(LinkConfig config, boolean created, List<String> errors) {
        boolean usable() {
            return config != null && !created && errors.isEmpty();
        }
    }

    private ConfigFile() {
    }

    static Loaded load(Path file) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                // 例を書き出す。中身を直すまでは動かさない（127.0.0.1 のままプレイヤーを送らないため）
                Files.writeString(file, GSON.toJson(new LinkConfig()) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                return new Loaded(null, true, List.of("書き出せません: " + e.getMessage()));
            }
            return new Loaded(new LinkConfig(), true, List.of());
        }
        try {
            LinkConfig c = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LinkConfig.class);
            if (c == null) {
                return new Loaded(null, false, List.of("中身が空です"));
            }
            return new Loaded(c, false, c.validate());
        } catch (IOException | JsonParseException e) {
            return new Loaded(null, false, List.of("読めません: " + e.getMessage()));
        }
    }
}
