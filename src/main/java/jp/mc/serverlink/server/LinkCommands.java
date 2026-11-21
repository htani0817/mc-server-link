package jp.mc.serverlink.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jp.mc.serverlink.logic.LinkConfig;
import jp.mc.serverlink.logic.StatusPing;
import jp.mc.serverlink.net.MenuPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * コマンド。
 * <pre>
 * /server            サーバーの一覧（Mod を入れたクライアントは画面、入れていなければチャットに押せる一覧）
 * /server &lt;id&gt;       そのサーバーへ移る
 * /lobby             ロビーへ移る
 * /serverlink status 各サーバーの状態（OP）
 * /serverlink send &lt;プレイヤー&gt; &lt;id&gt;  プレイヤーを移す（OP）
 * /serverlink reload 設定を読み直す（OP）
 * </pre>
 * ロビーのゲートなどのデータパックからは、プレイヤーとして「server survival」を実行すれば移せる。
 * 関数の中では結果の表示が出ないことがあるので、プレイヤーへの文は直接送る。
 */
final class LinkCommands {
    private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, b) -> LinkServer.enabled()
            ? SharedSuggestionProvider.suggest(LinkServer.config().servers.stream().map(e -> e.id), b)
            : b.buildFuture();

    private LinkCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("server")
                .executes(LinkCommands::list)
                .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                        .executes(c -> move(c, StringArgumentType.getString(c, "id")))));
        d.register(Commands.literal("lobby")
                .executes(c -> LinkServer.enabled() ? move(c, LinkServer.config().lobby) : notReady(c)));
        d.register(Commands.literal("serverlink")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(LinkCommands::status))
                .then(Commands.literal("reload").executes(LinkCommands::reload))
                .then(Commands.literal("send")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(IDS)
                                        .executes(LinkCommands::send)))));
    }

    private static int notReady(CommandContext<CommandSourceStack> c) {
        c.getSource().sendFailure(LinkServer.msg("not_ready", "サーバーの行き来は準備中です（設定がありません）"));
        return 0;
    }

    private static Optional<LinkConfig.Entry> find(CommandContext<CommandSourceStack> c, String id) {
        Optional<LinkConfig.Entry> e = LinkServer.config().find(id);
        if (e.isEmpty()) {
            c.getSource().sendFailure(LinkServer.msg("unknown_server", "「%s」というサーバーはありません", id));
        }
        return e;
    }

    private static int move(CommandContext<CommandSourceStack> c, String id) throws CommandSyntaxException {
        if (!LinkServer.enabled()) {
            return notReady(c);
        }
        ServerPlayer p = c.getSource().getPlayerOrException();
        Optional<LinkConfig.Entry> e = find(c, id);
        if (e.isEmpty()) {
            return 0;
        }
        LinkServer.requestMove(p, e.get(), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        if (!LinkServer.enabled()) {
            return notReady(c);
        }
        MenuPayload menu = LinkServer.menu();
        ServerPlayer p = c.getSource().getPlayer();
        if (p != null && p.connection.hasChannel(MenuPayload.TYPE)) {
            PacketDistributor.sendToPlayer(p, menu);
            return 1;
        }
        // Mod を入れていないクライアントとコンソールには、チャットに押せる一覧を出す
        MutableComponent out = LinkServer.msg("list_title", "サーバーの一覧（名前を押すと移動）").withStyle(ChatFormatting.GOLD);
        for (MenuPayload.Entry e : menu.entries()) {
            out.append("\n");
            out.append(listLine(e));
        }
        if (p != null) {
            p.sendSystemMessage(out);
        } else {
            c.getSource().sendSystemMessage(out);
        }
        return 1;
    }

    private static Component listLine(MenuPayload.Entry e) {
        MutableComponent name = Component.literal(" " + e.name()).withStyle(e.current() ? ChatFormatting.WHITE : ChatFormatting.AQUA);
        if (!e.current() && e.state() != MenuPayload.State.OFFLINE) {
            name = name.withStyle(s -> s.withUnderlined(true)
                    .withClickEvent(new ClickEvent.RunCommand("/server " + e.id()))
                    .withHoverEvent(new HoverEvent.ShowText(e.description().isEmpty()
                            ? LinkServer.msg("list_click", "押すと %s へ移動します", e.name())
                            : Component.literal(e.description()))));
        }
        MutableComponent line = Component.empty().append(name).append(Component.literal("  "));
        line.append(stateText(e));
        if (e.current()) {
            line.append(Component.literal("  ")).append(LinkServer.msg("list_here", "← いまここ").withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    private static MutableComponent stateText(MenuPayload.Entry e) {
        return switch (e.state()) {
            case ONLINE -> e.online() >= 0
                    ? LinkServer.msg("state.online_count", "● 動いています %s/%s", e.online(), e.max()).withStyle(ChatFormatting.GREEN)
                    : LinkServer.msg("state.online", "● 動いています").withStyle(ChatFormatting.GREEN);
            case OFFLINE -> LinkServer.msg("state.offline", "● 止まっています").withStyle(ChatFormatting.RED);
            case UNKNOWN -> LinkServer.msg("state.unknown", "● 人数が分かりません").withStyle(ChatFormatting.YELLOW);
            case CHECKING -> LinkServer.msg("state.checking", "● 確かめています").withStyle(ChatFormatting.GRAY);
        };
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        if (!LinkServer.enabled()) {
            return notReady(c);
        }
        LinkConfig cfg = LinkServer.config();
        MutableComponent out = Component.literal("mc-server-link: このサーバー " + cfg.thisServer + "、ロビー " + cfg.lobby)
                .withStyle(ChatFormatting.GOLD);
        for (LinkConfig.Entry e : cfg.servers) {
            out.append("\n ");
            out.append(Component.literal(e.id + " ").withStyle(ChatFormatting.AQUA));
            out.append(Component.literal(e.address + (e.statusAddress == null || e.statusAddress.isBlank() ? "" : "（確認 " + e.statusAddress + "）") + "  ")
                    .withStyle(ChatFormatting.GRAY));
            if (e.id.equals(cfg.thisServer)) {
                out.append(Component.literal("このサーバー").withStyle(ChatFormatting.WHITE));
                continue;
            }
            StatusPing.Result r = LinkServer.status(e.id);
            if (r == null) {
                out.append(Component.literal("確かめています").withStyle(ChatFormatting.GRAY));
            } else if (r.state() == StatusPing.State.ONLINE) {
                out.append(Component.literal("動いています " + r.online() + "/" + r.max() + "（" + r.millis() + " ms、" + r.version() + "）")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                out.append(Component.literal((r.state() == StatusPing.State.OFFLINE ? "止まっています" : "応答を読めません") + "（" + r.error() + "）")
                        .withStyle(r.state() == StatusPing.State.OFFLINE ? ChatFormatting.RED : ChatFormatting.YELLOW));
            }
        }
        c.getSource().sendSystemMessage(out);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        List<String> lines = LinkServer.reload();
        for (String line : lines) {
            c.getSource().sendSystemMessage(Component.literal("[mc-server-link] " + line)
                    .withStyle(LinkServer.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        return LinkServer.enabled() ? 1 : 0;
    }

    private static int send(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        if (!LinkServer.enabled()) {
            return notReady(c);
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(c, "targets");
        Optional<LinkConfig.Entry> e = find(c, StringArgumentType.getString(c, "id"));
        if (e.isEmpty()) {
            return 0;
        }
        for (ServerPlayer p : targets) {
            LinkServer.requestMove(p, e.get(), true);
        }
        c.getSource().sendSuccess(() -> Component.literal(targets.size() + " 人を " + e.get().displayName() + " へ送ります"), true);
        return targets.size();
    }
}
