package jp.mc.serverlink.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.List;
import org.junit.jupiter.api.Test;

/** アドレスの読み方と、設定の間違いの見つけ方 */
class LinkConfigTest {
    @Test
    void アドレスを読む() {
        assertEquals(new HostPort("play.example.com", 25565), HostPort.parse("play.example.com"));
        assertEquals(new HostPort("10.0.0.5", 25566), HostPort.parse(" 10.0.0.5:25566 "));
        assertEquals(new HostPort("::1", 25570), HostPort.parse("[::1]:25570"));
        assertEquals(new HostPort("::1", 25565), HostPort.parse("[::1]"));
        assertEquals(new HostPort("fe80::1", 25565), HostPort.parse("fe80::1"));
        assertEquals("[::1]:25570", HostPort.parse("[::1]:25570").toString());
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse(""));
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse("host:abc"));
        assertThrows(IllegalArgumentException.class, () -> HostPort.parse("host:70000"));
    }

    @Test
    void 既定の設定は使える() {
        LinkConfig c = new LinkConfig();
        assertTrue(c.validate().isEmpty(), c.validate().toString());
        assertTrue(c.isLobby());
        assertEquals("サバイバル", c.find("survival").orElseThrow().displayName());
    }

    @Test
    void JSONから読む() {
        String json = """
                {
                  "thisServer": "survival",
                  "lobby": "lobby",
                  "servers": [
                    { "id": "lobby", "name": "ロビー", "address": "play.example.com:25565", "statusAddress": "10.0.0.2:25565" },
                    { "id": "survival", "name": "サバイバル", "address": "play.example.com:25566" }
                  ]
                }
                """;
        LinkConfig c = new Gson().fromJson(json, LinkConfig.class);
        assertTrue(c.validate().isEmpty(), c.validate().toString());
        assertEquals(false, c.isLobby());
        assertEquals(new HostPort("10.0.0.2", 25565), c.lobbyEntry().pingAddress());
        assertEquals(new HostPort("play.example.com", 25566), c.self().pingAddress());
        // 書いていない項目は既定値
        assertEquals(10, c.statusIntervalSeconds);
        assertTrue(c.sendToLobbyOnStop);
    }

    @Test
    void 間違いを見つける() {
        LinkConfig c = new LinkConfig();
        c.thisServer = "nothing";
        c.servers = List.of(
                new LinkConfig.Entry("lobby", "ロビー", "", "a:1"),
                new LinkConfig.Entry("lobby", "重複", "", "b:2"),
                new LinkConfig.Entry("Bad Id", "", "", "c:x"));
        List<String> errors = c.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("重複")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("英小文字")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("address")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("thisServer")), errors.toString());
    }
}
