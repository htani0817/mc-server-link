package jp.mc.serverlink.net;

import jp.mc.serverlink.McServerLink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバー → クライアント。transfer の直前に送る「これから○○へ移る」の予告。
 * 同じ接続で transfer より先に届くので、クライアントは次の接続が移動によるものだと分かる。
 * 行き先へつながらなかったときに、ロビーへ戻すために使う。
 *
 * @param targetName    行き先の表示名
 * @param targetIsLobby 行き先がロビーなら true（ロビーにつながらなければ諦める）
 * @param lobbyName     ロビーの表示名
 * @param lobbyAddress  ロビーのアドレス
 */
public record TransferNoticePayload(String targetName, boolean targetIsLobby, String lobbyName, String lobbyAddress)
        implements CustomPacketPayload {
    public static final Type<TransferNoticePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(McServerLink.MODID, "transfer_notice"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransferNoticePayload> STREAM_CODEC =
            CustomPacketPayload.codec(TransferNoticePayload::write, TransferNoticePayload::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(targetName, 64);
        buf.writeBoolean(targetIsLobby);
        buf.writeUtf(lobbyName, 64);
        buf.writeUtf(lobbyAddress, 255);
    }

    private static TransferNoticePayload read(RegistryFriendlyByteBuf buf) {
        return new TransferNoticePayload(buf.readUtf(64), buf.readBoolean(), buf.readUtf(64), buf.readUtf(255));
    }

    @Override
    public Type<TransferNoticePayload> type() {
        return TYPE;
    }
}
