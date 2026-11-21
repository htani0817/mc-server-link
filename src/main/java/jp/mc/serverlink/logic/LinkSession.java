package jp.mc.serverlink.logic;

/**
 * クライアント側の「つないだサーバーの組」の状態。切断されたときにロビーへつなぎ直すかを決める。
 * Minecraft に依存しないので単体テストできる（R は切断の理由の型。ゲームでは Component）。
 *
 * <pre>
 * ロビーへ参加（タイトル画面）──┐
 * サーバーからの案内（入ったとき）─┼→ 組あり（ロビーのアドレスが分かる）
 * 移動の予告（transfer の直前）───┘
 *
 * 切断された:
 *   ロビーへつなぎ直している途中だった → 諦める（1 回だけ試す）
 *   組が無い・いた所がロビー・二重ログイン → いつもの切断画面
 *   それ以外 → ロビーへつなぎ直す
 * </pre>
 * 自分で別のサーバーへつないだとき（サーバー一覧から選んだなど）は組を捨てる。
 */
public final class LinkSession<R> {
    /** 戻り先のロビー */
    public record Lobby(String name, String address) {
    }

    /** 切断されたときにどうするか */
    public sealed interface Decision<R> {
    }

    /** いつもの切断画面を出す */
    public record Show<R>() implements Decision<R> {
    }

    /** ロビーへつなぎ直す */
    public record Failover<R>(Lobby lobby, String fromName, R reason) implements Decision<R> {
    }

    /** ロビーにもつながらなかったので諦める */
    public record GiveUp<R>(Lobby lobby, String fromName, R firstReason, R lobbyReason) implements Decision<R> {
    }

    /** ロビーへ戻れたあとに知らせる中身 */
    public record Returned<R>(Lobby lobby, String fromName, R reason) {
    }

    private record Target(String name, boolean lobby) {
    }

    private Lobby lobby;
    /** いまつないでいる（つなごうとしている）先 */
    private Target target;
    /** transfer の予告で聞いた次の行き先 */
    private Target pending;
    /** 自分（この Mod）が始めた接続を待っている */
    private boolean expectingConnect;
    /** ロビーへつなぎ直している途中 */
    private Failover<R> failing;

    public Lobby lobby() {
        return lobby;
    }

    public boolean active() {
        return lobby != null;
    }

    /** タイトル画面などからロビーへ参加するとき */
    public void beginLobbyJoin(Lobby l) {
        lobby = l;
        target = new Target(l.name(), true);
        pending = null;
        failing = null;
        expectingConnect = true;
    }

    /** サーバーに入ったときの案内。l が null ならロビーは前に聞いたまま */
    public void onLinkInfo(String thisName, boolean thisIsLobby, Lobby l) {
        if (l != null) {
            lobby = l;
        }
        target = new Target(thisName, thisIsLobby);
    }

    /** transfer の直前の予告 */
    public void onTransferNotice(String targetName, boolean targetIsLobby, Lobby l) {
        if (l != null) {
            lobby = l;
        }
        pending = new Target(targetName, targetIsLobby);
    }

    /** 接続の画面が開いた。予告も、自分で始めた接続も無ければ、プレイヤーが自分で選んだ接続 */
    public void onConnectStarted() {
        if (pending != null) {
            target = pending;
            pending = null;
            expectingConnect = false;
        } else if (expectingConnect) {
            expectingConnect = false;
        } else {
            clear();
        }
    }

    /** ゲームに入れた。ロビーへ戻れたところなら、知らせる中身を返す */
    public Returned<R> onLoggedIn() {
        pending = null;
        if (failing != null) {
            Returned<R> r = new Returned<>(failing.lobby(), failing.fromName(), failing.reason());
            failing = null;
            return r;
        }
        return null;
    }

    /**
     * 切断された（接続に失敗したときも含む）。
     *
     * @param reason         理由
     * @param duplicateLogin 別の場所から同じアカウントで入られた（つなぎ直すと追い出し合いになる）
     */
    public Decision<R> onDisconnected(R reason, boolean duplicateLogin) {
        if (failing != null) {
            GiveUp<R> g = new GiveUp<>(failing.lobby(), failing.fromName(), failing.reason(), reason);
            clear();
            return g;
        }
        if (lobby == null || target == null || target.lobby() || duplicateLogin) {
            clear();
            return new Show<>();
        }
        Failover<R> f = new Failover<>(lobby, target.name(), reason);
        failing = f;
        target = new Target(lobby.name(), true);
        pending = null;
        expectingConnect = true;
        return f;
    }

    public void clear() {
        lobby = null;
        target = null;
        pending = null;
        failing = null;
        expectingConnect = false;
    }
}
