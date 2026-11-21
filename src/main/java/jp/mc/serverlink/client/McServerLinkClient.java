package jp.mc.serverlink.client;

import jp.mc.serverlink.McServerLink;
import jp.mc.serverlink.net.LinkInfoPayload;
import jp.mc.serverlink.net.MenuPayload;
import jp.mc.serverlink.net.TransferNoticePayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** クライアント側の入口。設定を読み、サーバーから届く案内の受け口と画面のイベントをつなぐ */
@Mod(value = McServerLink.MODID, dist = Dist.CLIENT)
public class McServerLinkClient {
    public McServerLinkClient(IEventBus modBus) {
        ClientLink.init(ClientConfig.load(FMLPaths.CONFIGDIR.get().resolve("mc_server_link").resolve("client.json")));
        modBus.addListener(RegisterClientPayloadHandlersEvent.class, e -> {
            e.register(LinkInfoPayload.TYPE, (p, ctx) -> ClientLink.onLinkInfo(p));
            e.register(TransferNoticePayload.TYPE, (p, ctx) -> ClientLink.onTransferNotice(p));
            e.register(MenuPayload.TYPE, (p, ctx) -> ClientLink.onMenu(p));
        });
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, ClientLink::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, ClientLink::onScreenInit);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> ClientLink.onLoggedIn());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ClientLink.onTick());
    }
}
