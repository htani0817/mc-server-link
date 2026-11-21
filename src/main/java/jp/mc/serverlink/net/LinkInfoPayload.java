package jp.mc.serverlink.net;

import jp.mc.serverlink.McServerLink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバー → クライアント。入ったときに送る「このサーバーは何か・ロビーはどこか」の案内。
 * クライアントはこれで、落ちたときの戻り先を知る。
 *
 * @param thisName     このサーバーの表示名
 * @param thisIsLobby  このサーバーがロビーなら true
 * @param lobbyName    ロビーの表示名
 * @param lobbyAddress ロビーのアドレス（プレイヤーがつなぐ host:port）
 */
public record LinkInfoPayload(String thisName, boolean thisIsLobby, String lobbyName, String lobbyAddress)
        implements CustomPacketPayload {
    public static final Type<LinkInfoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(McServerLink.MODID, "info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LinkInfoPayload> STREAM_CODEC =
            CustomPacketPayload.codec(LinkInfoPayload::write, LinkInfoPayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(thisName, 64);
        buf.writeBoolean(thisIsLobby);
        buf.writeUtf(lobbyName, 64);
        buf.writeUtf(lobbyAddress, 255);
    }

    private static LinkInfoPayload read(RegistryFriendlyByteBuf buf) {
        return new LinkInfoPayload(buf.readUtf(64), buf.readBoolean(), buf.readUtf(64), buf.readUtf(255));
    }

    @Override
    public Type<LinkInfoPayload> type() {
        return TYPE;
    }
}
