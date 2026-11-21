package jp.mc.serverlink.net;

import java.util.ArrayList;
import java.util.List;
import jp.mc.serverlink.McServerLink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバー → クライアント。サーバーの一覧の画面を開く（/server を引数なしで実行したとき）。
 *
 * @param thisName このサーバーの表示名
 * @param entries  一覧（設定の並び順）
 */
public record MenuPayload(String thisName, List<Entry> entries) implements CustomPacketPayload {
    public static final Type<MenuPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(McServerLink.MODID, "menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuPayload> STREAM_CODEC =
            CustomPacketPayload.codec(MenuPayload::write, MenuPayload::read);
    public static final int MAX_ENTRIES = 32;

    public enum State {
        /** 動いている */
        ONLINE,
        /** 止まっている（つながらない） */
        OFFLINE,
        /** つながるが人数などが分からない */
        UNKNOWN,
        /** まだ確かめていない */
        CHECKING
    }

    /**
     * @param id          コマンドで使う名前
     * @param name        表示名
     * @param description 説明
     * @param state       状態
     * @param online      人数（分からなければ -1）
     * @param max         最大人数（分からなければ -1）
     * @param lobby       ロビーなら true
     * @param current     いまいるサーバーなら true
     */
    public record Entry(String id, String name, String description, State state, int online, int max, boolean lobby,
                        boolean current) {
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(thisName, 64);
        int n = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            buf.writeUtf(e.id(), 32);
            buf.writeUtf(e.name(), 64);
            buf.writeUtf(e.description(), 256);
            buf.writeEnum(e.state());
            buf.writeVarInt(e.online() + 1);
            buf.writeVarInt(e.max() + 1);
            buf.writeBoolean(e.lobby());
            buf.writeBoolean(e.current());
        }
    }

    private static MenuPayload read(RegistryFriendlyByteBuf buf) {
        String thisName = buf.readUtf(64);
        int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readUtf(32), buf.readUtf(64), buf.readUtf(256), buf.readEnum(State.class),
                    buf.readVarInt() - 1, buf.readVarInt() - 1, buf.readBoolean(), buf.readBoolean()));
        }
        return new MenuPayload(thisName, List.copyOf(list));
    }

    @Override
    public Type<MenuPayload> type() {
        return TYPE;
    }
}
