package jp.mc.serverlink;

import com.mojang.logging.LogUtils;
import jp.mc.serverlink.net.LinkInfoPayload;
import jp.mc.serverlink.net.MenuPayload;
import jp.mc.serverlink.net.TransferNoticePayload;
import jp.mc.serverlink.server.LinkServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * サーバー同士をつないで行き来できるようにする Mod。サーバーとクライアントの両方で読み込まれる入口。
 * サーバー側（server/）は専用サーバーのときだけ動く。クライアント側は client/McServerLinkClient。
 */
@Mod(McServerLink.MODID)
public class McServerLink {
    public static final String MODID = "mc_server_link";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 通信の形を変えたら上げる */
    public static final int PROTOCOL = 1;

    public McServerLink(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, McServerLink::registerPayloads);
        LinkServer.register(NeoForge.EVENT_BUS);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: Mod を入れていないクライアントもサーバーに入れる（画面と、落ちたときのつなぎ直しが無いだけ）。
        // Mod を入れたクライアントも、この Mod の無いサーバーに入れる
        PayloadRegistrar registrar = event.registrar(String.valueOf(PROTOCOL)).optional();
        registrar.playToClient(LinkInfoPayload.TYPE, LinkInfoPayload.STREAM_CODEC);
        registrar.playToClient(TransferNoticePayload.TYPE, TransferNoticePayload.STREAM_CODEC);
        registrar.playToClient(MenuPayload.TYPE, MenuPayload.STREAM_CODEC);
    }
}
