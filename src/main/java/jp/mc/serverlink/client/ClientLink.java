package jp.mc.serverlink.client;

import java.lang.reflect.Field;
import jp.mc.serverlink.McServerLink;
import jp.mc.serverlink.logic.LinkSession;
import jp.mc.serverlink.net.LinkInfoPayload;
import jp.mc.serverlink.net.MenuPayload;
import jp.mc.serverlink.net.TransferNoticePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * クライアント側の本体。
 * <ul>
 *   <li>サーバーからの案内（ロビーはどこか）と移動の予告を {@link LinkSession} に渡す</li>
 *   <li>切断の画面が開くときに割り込み、行き先から切れたのならロビーへつなぎ直す。ロビーにもつながらなければ両方の理由を出して諦める</li>
 *   <li>タイトル画面に「ロビーに参加」のボタンを出す</li>
 * </ul>
 * どれもゲームのメインのスレッドで呼ばれる。
 */
final class ClientLink {
    private static final LinkSession<Component> SESSION = new LinkSession<>();
    private static ClientConfig config;
    /** ロビーへ戻れたあと、チャットに出す知らせ */
    private static LinkSession.Returned<Component> toAnnounce;
    private static boolean autoConnectPending;
    private static boolean autoConnectChecked;
    private static Field detailsField;

    private ClientLink() {
    }

    static void init(ClientConfig c) {
        config = c;
    }

    // ---- サーバーから ----

    static void onLinkInfo(LinkInfoPayload p) {
        SESSION.onLinkInfo(p.thisName(), p.thisIsLobby(), lobbyFrom(p.lobbyName(), p.lobbyAddress()));
    }

    static void onTransferNotice(TransferNoticePayload p) {
        SESSION.onTransferNotice(p.targetName(), p.targetIsLobby(), lobbyFrom(p.lobbyName(), p.lobbyAddress()));
    }

    static void onMenu(MenuPayload p) {
        Minecraft.getInstance().gui.setScreen(new ServerMenuScreen(p));
    }

    private static LinkSession.Lobby lobbyFrom(String name, String address) {
        if (config.useServerLobby && address != null && !address.isBlank()) {
            return new LinkSession.Lobby(name, address);
        }
        return config.configLobby();
    }

    // ---- 画面 ----

    static void onScreenOpening(ScreenEvent.Opening e) {
        Screen next = e.getNewScreen();
        if (next instanceof ConnectScreen) {
            SESSION.onConnectStarted();
        } else if (next instanceof DisconnectedScreen d) {
            onDisconnected(e, d);
        }
    }

    private static void onDisconnected(ScreenEvent.Opening e, DisconnectedScreen d) {
        Component reason = reasonOf(d);
        // transfer のときも、移る前に一度だけ切断の画面が開く（すぐ接続の画面に変わる）。これは無視する
        if (isKey(reason, "disconnect.transfer")) {
            return;
        }
        if (!config.failover) {
            SESSION.clear();
            return;
        }
        LinkSession.Decision<Component> decision = SESSION.onDisconnected(reason, isKey(reason, "multiplayer.disconnect.duplicate_login"));
        if (decision instanceof LinkSession.Failover<Component> f) {
            McServerLink.LOGGER.info("[mc-server-link] {} との接続が切れたので、ロビー（{}）へつなぎ直します: {}",
                    f.fromName(), f.lobby().address(), reason.getString());
            e.setNewScreen(new ReturningScreen(f));
        } else if (decision instanceof LinkSession.GiveUp<Component> g) {
            McServerLink.LOGGER.info("[mc-server-link] ロビー（{}）にもつながらないので諦めます: {}", g.lobby().address(), reason.getString());
            Component body = Component.translatable("mc_server_link.giveup.body",
                    Component.literal(g.fromName()), g.firstReason(), Component.literal(g.lobby().name()), g.lobbyReason());
            e.setNewScreen(new DisconnectedScreen(menuScreen(), Component.translatable("mc_server_link.giveup.title"), body));
        }
    }

    /** 切断の画面に出る理由。vanilla は private のフィールドにしか持っていないので名前で読む。読めなければ見出し */
    private static Component reasonOf(DisconnectedScreen d) {
        try {
            if (detailsField == null) {
                detailsField = DisconnectedScreen.class.getDeclaredField("details");
                detailsField.setAccessible(true);
            }
            return ((DisconnectionDetails) detailsField.get(d)).reason();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return d.getTitle();
        }
    }

    private static boolean isKey(Component c, String key) {
        return c.getContents() instanceof TranslatableContents t && t.getKey().equals(key);
    }

    /** 接続をやめたときや諦めたときに戻る画面（サーバー一覧） */
    static Screen menuScreen() {
        return new JoinMultiplayerScreen(new TitleScreen());
    }

    /** ロビーへつなぐ（タイトル画面のボタン・起動直後・つなぎ直し） */
    static void connect(Screen parent, LinkSession.Lobby lobby) {
        Minecraft mc = Minecraft.getInstance();
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(lobby.address()),
                new ServerData(lobby.name(), lobby.address(), ServerData.Type.OTHER), false, null);
    }

    static void joinLobby(Screen parent, LinkSession.Lobby lobby) {
        SESSION.beginLobbyJoin(lobby);
        connect(parent, lobby);
    }

    /** つなぎ直しをやめた */
    static void cancelFailover() {
        SESSION.clear();
    }

    static void onScreenInit(ScreenEvent.Init.Post e) {
        if (!(e.getScreen() instanceof TitleScreen title)) {
            return;
        }
        LinkSession.Lobby lobby = config.configLobby();
        if (lobby == null) {
            return;
        }
        if (!autoConnectChecked) {
            autoConnectChecked = true;
            autoConnectPending = config.autoConnect;
        }
        if (!config.titleButton) {
            return;
        }
        // Realms のボタンの場所に置く（無ければ置かない）
        AbstractWidget realms = null;
        for (GuiEventListener l : e.getListenersList()) {
            if (l instanceof AbstractWidget w && isKey(w.getMessage(), "menu.online")) {
                realms = w;
                break;
            }
        }
        if (realms == null) {
            return;
        }
        e.removeListener(realms);
        Button join = Button.builder(Component.translatable("mc_server_link.title.join", lobby.name()), b -> joinLobby(title, lobby))
                .bounds(realms.getX(), realms.getY(), realms.getWidth(), realms.getHeight())
                .tooltip(Tooltip.create(Component.translatable("mc_server_link.title.join_tooltip", lobby.address())))
                .build();
        join.active = realms.active;
        e.addListener(join);
    }

    // ---- ゲームに入ったとき・毎 tick ----

    static void onLoggedIn() {
        LinkSession.Returned<Component> r = SESSION.onLoggedIn();
        if (r != null) {
            toAnnounce = r;
        }
    }

    static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (toAnnounce != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.translatable("mc_server_link.returned",
                    Component.literal(toAnnounce.fromName()), Component.literal(toAnnounce.lobby().name()), toAnnounce.reason())
                    .withStyle(ChatFormatting.YELLOW));
            toAnnounce = null;
        }
        if (autoConnectPending && mc.gui.screen() instanceof TitleScreen title) {
            autoConnectPending = false;
            LinkSession.Lobby lobby = config.configLobby();
            if (lobby != null) {
                joinLobby(title, lobby);
            }
        }
    }
}
