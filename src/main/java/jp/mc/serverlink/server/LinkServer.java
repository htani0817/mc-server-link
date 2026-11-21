package jp.mc.serverlink.server;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jp.mc.serverlink.McServerLink;
import jp.mc.serverlink.logic.HostPort;
import jp.mc.serverlink.logic.LinkConfig;
import jp.mc.serverlink.logic.StatusPing;
import jp.mc.serverlink.net.LinkInfoPayload;
import jp.mc.serverlink.net.MenuPayload;
import jp.mc.serverlink.net.TransferNoticePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * サーバー側の本体。専用サーバーのときだけ動く（シングルプレイの統合サーバーでは何もしない）。
 * <ul>
 *   <li>入ってきた人に「このサーバーは何か・ロビーはどこか」を送る</li>
 *   <li>ロビー以外へ直接入ってきた人をロビーへ送る（設定で切れる）</li>
 *   <li>/server・/lobby で、動いているかを確かめてから transfer で送る</li>
 *   <li>ロビー以外のサーバーを止めるとき、入っている人をロビーへ送る</li>
 * </ul>
 */
public final class LinkServer {
    private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("mc_server_link").resolve("servers.json");
    /** 止めるときにロビーへ送ったあと、切れるのを待つ最大の時間 */
    private static final long STOP_WAIT_MILLIS = 3000;

    private static MinecraftServer server;
    private static LinkConfig config;
    private static StatusCache cache;
    private static final Map<UUID, Long> lastMove = new ConcurrentHashMap<>();
    private static Field transferredField;
    private static boolean transferredFieldFailed;

    private LinkServer() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ServerStartedEvent.class, e -> onStarted(e.getServer()));
        bus.addListener(ServerStoppingEvent.class, e -> onStopping(e.getServer()));
        bus.addListener(ServerStoppedEvent.class, e -> shutdown());
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                onJoin(p);
            }
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> lastMove.remove(e.getEntity().getUUID()));
        bus.addListener(RegisterCommandsEvent.class, e -> LinkCommands.register(e.getDispatcher()));
    }

    // ---- 起動・停止 ----

    private static void onStarted(MinecraftServer s) {
        if (!s.isDedicatedServer()) {
            return;
        }
        server = s;
        BukkitCompat.allowForEveryone("server", "lobby");
        reload();
    }

    /** 設定を読み直す。結果をログ用の文に返す */
    static List<String> reload() {
        if (cache != null) {
            cache.stop();
            cache = null;
        }
        config = null;
        ConfigFile.Loaded loaded = ConfigFile.load(CONFIG_FILE);
        List<String> out = new ArrayList<>();
        if (loaded.created()) {
            out.add("設定の例を書き出しました: " + CONFIG_FILE + "（直してから /serverlink reload か再起動）");
        }
        for (String err : loaded.errors()) {
            out.add("設定の間違い: " + err);
        }
        if (loaded.usable()) {
            config = loaded.config();
            cache = new StatusCache(config);
            cache.start();
            out.add("このサーバー: " + config.self().displayName() + "（" + config.thisServer + "）"
                    + (config.isLobby() ? " ロビー" : "") + "、つなぐサーバー " + config.servers.size() + " 台");
        } else {
            out.add("mc-server-link は止めています（設定を直すまで移動・案内をしません）");
        }
        for (String line : out) {
            if (config == null) {
                McServerLink.LOGGER.warn("[mc-server-link] {}", line);
            } else {
                McServerLink.LOGGER.info("[mc-server-link] {}", line);
            }
        }
        return out;
    }

    private static void onStopping(MinecraftServer s) {
        if (s != server || config == null || config.isLobby() || !config.sendToLobbyOnStop) {
            return;
        }
        List<ServerPlayer> players = List.copyOf(s.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            return;
        }
        LinkConfig.Entry lobby = config.lobbyEntry();
        StatusPing.Result r = cache.pingBlocking(lobby);
        if (!r.reachable()) {
            McServerLink.LOGGER.warn("[mc-server-link] 止めます。ロビー（{}）も止まっているので、{} 人をそのまま切断します（{}）",
                    lobby.id, players.size(), r.error());
            return;
        }
        McServerLink.LOGGER.info("[mc-server-link] 止めます。{} 人をロビー（{}）へ送ります", players.size(), lobby.id);
        for (ServerPlayer p : players) {
            p.sendSystemMessage(msg("stop_to_lobby", "このサーバーを止めるため、%s へ移動します", lobby.displayName())
                    .withStyle(ChatFormatting.YELLOW));
            sendTransfer(p, lobby);
        }
        // transfer を受け取ったクライアントは自分から切る。切れるまで（最大 3 秒）待ってから止める
        long until = System.currentTimeMillis() + STOP_WAIT_MILLIS;
        while (System.currentTimeMillis() < until && players.stream().anyMatch(p -> p.connection.getConnection().isConnected())) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void shutdown() {
        if (cache != null) {
            cache.stop();
        }
        cache = null;
        config = null;
        server = null;
        lastMove.clear();
    }

    // ---- 入ってきたとき ----

    private static void onJoin(ServerPlayer p) {
        if (config == null) {
            return;
        }
        LinkConfig.Entry self = config.self();
        LinkConfig.Entry lobby = config.lobbyEntry();
        if (p.connection.hasChannel(LinkInfoPayload.TYPE)) {
            PacketDistributor.sendToPlayer(p, new LinkInfoPayload(self.displayName(), config.isLobby(), lobby.displayName(), lobby.address));
        }
        if (config.isLobby() || !config.sendDirectJoinsToLobby || cameByTransfer(p)) {
            return;
        }
        if (config.directJoinOpsBypass && Commands.LEVEL_GAMEMASTERS.check(p.permissions())) {
            McServerLink.LOGGER.info("[mc-server-link] {} は直接入ってきましたが、OP なのでロビーへ送りません", p.getPlainTextName());
            return;
        }
        UUID id = p.getUUID();
        cache.pingNow(lobby).thenAccept(r -> server.execute(() -> {
            ServerPlayer now = server.getPlayerList().getPlayer(id);
            if (now == null || now.hasDisconnected()) {
                return;
            }
            if (!r.reachable()) {
                McServerLink.LOGGER.info("[mc-server-link] {} は直接入ってきましたが、ロビーが止まっているのでそのまま入れます", now.getPlainTextName());
                return;
            }
            now.sendSystemMessage(msg("direct_join", "最初は %s から入ってください。移動します", lobby.displayName())
                    .withStyle(ChatFormatting.YELLOW));
            McServerLink.LOGGER.info("[mc-server-link] {} は直接入ってきたので、ロビーへ送ります", now.getPlainTextName());
            sendTransfer(now, lobby);
        }));
    }

    /**
     * transfer で来たか。vanilla は private のフィールドにしか持っていないので名前で読む（26.x は難読化されていない）。
     * 読めなければ「来た」とみなして、ロビーへは送らない（誤って追い返さないため）
     */
    private static boolean cameByTransfer(ServerPlayer p) {
        if (transferredFieldFailed) {
            return true;
        }
        try {
            if (transferredField == null) {
                transferredField = ServerCommonPacketListenerImpl.class.getDeclaredField("transferred");
                transferredField.setAccessible(true);
            }
            return transferredField.getBoolean(p.connection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            transferredFieldFailed = true;
            McServerLink.LOGGER.warn("[mc-server-link] transfer で来たかを読めないので、直接入ってきた人をロビーへ送るのをやめます", e);
            return true;
        }
    }

    // ---- 移動 ----

    static boolean enabled() {
        return config != null;
    }

    static LinkConfig config() {
        return config;
    }

    /** 移動を頼む。動いているかを確かめてから送る。force は管理者の /serverlink send（待ち時間を見ない） */
    static void requestMove(ServerPlayer p, LinkConfig.Entry target, boolean force) {
        if (target.id.equals(config.thisServer)) {
            p.sendSystemMessage(msg("already_here", "いま %s にいます", target.displayName()).withStyle(ChatFormatting.GRAY));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastMove.get(p.getUUID());
        if (!force && last != null && now - last < config.moveCooldownSeconds * 1000L) {
            p.sendSystemMessage(msg("cooldown", "少し待ってからもう一度試してください").withStyle(ChatFormatting.GRAY));
            return;
        }
        lastMove.put(p.getUUID(), now);
        UUID id = p.getUUID();
        LinkConfig current = config;
        cache.pingNow(target).thenAccept(r -> server.execute(() -> {
            ServerPlayer pl = server.getPlayerList().getPlayer(id);
            if (pl == null || pl.hasDisconnected() || current != config) {
                return;
            }
            if (!r.reachable()) {
                pl.sendSystemMessage(msg("offline", "%s は止まっているため移動できません", target.displayName()).withStyle(ChatFormatting.RED));
                return;
            }
            pl.sendSystemMessage(msg("moving", "%s へ移動します…", target.displayName()).withStyle(ChatFormatting.GREEN));
            sendTransfer(pl, target);
        }));
    }

    /** 予告（Mod を入れたクライアントだけ）と transfer を同じ接続で送る。予告が先に届く */
    private static void sendTransfer(ServerPlayer p, LinkConfig.Entry target) {
        HostPort to = target.joinAddress();
        LinkConfig.Entry lobby = config.lobbyEntry();
        if (p.connection.hasChannel(TransferNoticePayload.TYPE)) {
            PacketDistributor.sendToPlayer(p, new TransferNoticePayload(target.displayName(), target.id.equals(config.lobby),
                    lobby.displayName(), lobby.address));
        }
        McServerLink.LOGGER.info("[mc-server-link] {} を {}（{}）へ送ります", p.getPlainTextName(), target.id, to);
        p.connection.send(new ClientboundTransferPacket(to.host(), to.port()));
    }

    // ---- 一覧 ----

    static MenuPayload.State stateOf(LinkConfig.Entry e) {
        if (e.id.equals(config.thisServer)) {
            return MenuPayload.State.ONLINE;
        }
        StatusPing.Result r = cache.get(e.id);
        if (r == null) {
            return MenuPayload.State.CHECKING;
        }
        return switch (r.state()) {
            case ONLINE -> MenuPayload.State.ONLINE;
            case OFFLINE -> MenuPayload.State.OFFLINE;
            case UNKNOWN -> MenuPayload.State.UNKNOWN;
        };
    }

    static MenuPayload menu() {
        List<MenuPayload.Entry> list = new ArrayList<>();
        for (LinkConfig.Entry e : config.servers) {
            boolean self = e.id.equals(config.thisServer);
            int online;
            int max;
            if (self) {
                online = server.getPlayerCount();
                max = server.getMaxPlayers();
            } else {
                StatusPing.Result r = cache.get(e.id);
                online = r == null ? -1 : r.online();
                max = r == null ? -1 : r.max();
            }
            list.add(new MenuPayload.Entry(e.id, e.displayName(), e.description == null ? "" : e.description, stateOf(e),
                    online, max, e.id.equals(config.lobby), self));
        }
        return new MenuPayload(config.self().displayName(), list);
    }

    static StatusPing.Result status(String id) {
        return cache.get(id);
    }

    /**
     * プレイヤーに送る文。Mod を入れたクライアントは自分の言語で、入れていないクライアントは日本語（fallback）で出る
     */
    static MutableComponent msg(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(McServerLink.MODID + "." + key, fallback, args);
    }
}
