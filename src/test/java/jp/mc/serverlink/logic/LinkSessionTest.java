package jp.mc.serverlink.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 切断されたときにロビーへつなぎ直すかの判断 */
class LinkSessionTest {
    private static final LinkSession.Lobby LOBBY = new LinkSession.Lobby("ロビー", "lobby.example:25565");

    /** タイトル画面からロビーへ入り、ゲートでサバイバルへ移ったところ */
    private static LinkSession<String> onSurvival() {
        LinkSession<String> s = new LinkSession<>();
        s.beginLobbyJoin(LOBBY);
        s.onConnectStarted();
        assertNull(s.onLoggedIn());
        s.onLinkInfo("ロビー", true, LOBBY);
        s.onTransferNotice("サバイバル", false, LOBBY);
        s.onConnectStarted();
        assertNull(s.onLoggedIn());
        s.onLinkInfo("サバイバル", false, LOBBY);
        return s;
    }

    @Test
    void 行き先が落ちたらロビーへ戻り_戻れたら知らせる() {
        LinkSession<String> s = onSurvival();
        LinkSession.Decision<String> d = s.onDisconnected("Server closed", false);
        LinkSession.Failover<String> f = assertInstanceOf(LinkSession.Failover.class, d);
        assertEquals("サバイバル", f.fromName());
        assertEquals(LOBBY, f.lobby());
        // つなぎ直しの接続
        s.onConnectStarted();
        LinkSession.Returned<String> r = s.onLoggedIn();
        assertNotNull(r);
        assertEquals("サバイバル", r.fromName());
        assertEquals("Server closed", r.reason());
        // 戻れたロビーの組は続く。もう一度移って落ちても戻れる
        s.onLinkInfo("ロビー", true, LOBBY);
        s.onTransferNotice("サバイバル", false, LOBBY);
        s.onConnectStarted();
        assertInstanceOf(LinkSession.Failover.class, s.onDisconnected("Connection reset", false));
    }

    @Test
    void ロビーにもつながらなければ1回で諦める() {
        LinkSession<String> s = onSurvival();
        assertInstanceOf(LinkSession.Failover.class, s.onDisconnected("Timed out", false));
        s.onConnectStarted();
        LinkSession.GiveUp<String> g = assertInstanceOf(LinkSession.GiveUp.class, s.onDisconnected("Connection refused", false));
        assertEquals("サバイバル", g.fromName());
        assertEquals("Timed out", g.firstReason());
        assertEquals("Connection refused", g.lobbyReason());
        assertFalse(s.active());
    }

    @Test
    void 移る先につながらなかったときもロビーへ戻る() {
        LinkSession<String> s = new LinkSession<>();
        s.beginLobbyJoin(LOBBY);
        s.onConnectStarted();
        s.onLoggedIn();
        s.onLinkInfo("ロビー", true, LOBBY);
        s.onTransferNotice("サバイバル", false, LOBBY);
        s.onConnectStarted();
        // ログインの前に失敗
        LinkSession.Failover<String> f = assertInstanceOf(LinkSession.Failover.class, s.onDisconnected("Connection refused", false));
        assertEquals("サバイバル", f.fromName());
    }

    @Test
    void ロビーから切れたらそのまま切断() {
        LinkSession<String> s = new LinkSession<>();
        s.beginLobbyJoin(LOBBY);
        s.onConnectStarted();
        s.onLoggedIn();
        s.onLinkInfo("ロビー", true, LOBBY);
        assertInstanceOf(LinkSession.Show.class, s.onDisconnected("Server closed", false));
        assertFalse(s.active());
    }

    @Test
    void ロビーへ移る途中で失敗したらそのまま切断() {
        LinkSession<String> s = onSurvival();
        s.onTransferNotice("ロビー", true, LOBBY);
        s.onConnectStarted();
        assertInstanceOf(LinkSession.Show.class, s.onDisconnected("Connection refused", false));
    }

    @Test
    void 二重ログインではつなぎ直さない() {
        LinkSession<String> s = onSurvival();
        assertInstanceOf(LinkSession.Show.class, s.onDisconnected("duplicate", true));
    }

    @Test
    void 自分で別のサーバーへつないだら組を捨てる() {
        LinkSession<String> s = onSurvival();
        // 普通に抜けて（切断の画面は出ない）、サーバー一覧から別のサーバーへ
        s.onConnectStarted();
        assertFalse(s.active());
        assertInstanceOf(LinkSession.Show.class, s.onDisconnected("Kicked", false));
    }

    @Test
    void サーバーの一覧から直接サバイバルに入っても_案内が来れば戻れる() {
        LinkSession<String> s = new LinkSession<>();
        s.onConnectStarted();
        s.onLoggedIn();
        s.onLinkInfo("サバイバル", false, LOBBY);
        assertInstanceOf(LinkSession.Failover.class, s.onDisconnected("Server closed", false));
    }

    @Test
    void Modの無いサーバーへ移った先で落ちてもロビーへ戻る() {
        LinkSession<String> s = new LinkSession<>();
        s.beginLobbyJoin(LOBBY);
        s.onConnectStarted();
        s.onLoggedIn();
        s.onLinkInfo("ロビー", true, LOBBY);
        s.onTransferNotice("ミニゲーム", false, LOBBY);
        s.onConnectStarted();
        s.onLoggedIn();
        // 案内は来ない
        LinkSession.Failover<String> f = assertInstanceOf(LinkSession.Failover.class, s.onDisconnected("Timed out", false));
        assertEquals("ミニゲーム", f.fromName());
    }

    @Test
    void 組が無ければいつもの切断() {
        LinkSession<String> s = new LinkSession<>();
        s.onConnectStarted();
        s.onLoggedIn();
        assertInstanceOf(LinkSession.Show.class, s.onDisconnected("Server closed", false));
    }

    @Test
    void ロビーが移ったらサーバーの案内のアドレスを使う() {
        LinkSession<String> s = onSurvival();
        LinkSession.Lobby moved = new LinkSession.Lobby("新ロビー", "new.example:25570");
        s.onLinkInfo("サバイバル", false, moved);
        LinkSession.Failover<String> f = assertInstanceOf(LinkSession.Failover.class, s.onDisconnected("x", false));
        assertEquals(moved, f.lobby());
    }
}
