package jp.mc.serverlink.client;

import jp.mc.serverlink.logic.LinkSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * 行き先との接続が切れたあと、ロビーへつなぎ直す前に一瞬だけ出す画面。
 * 切断の後片付け（ワールドを閉じる・登録を戻す）が終わってから接続を始めたいので、
 * 切断の画面を開く途中ではなく、この画面の tick から接続を始める。
 */
final class ReturningScreen extends Screen {
    /** 何 tick 見せてからつなぐか（0.5 秒） */
    private static final int DELAY_TICKS = 10;

    private final LinkSession.Failover<Component> failover;
    private int ticks;

    ReturningScreen(LinkSession.Failover<Component> failover) {
        super(Component.translatable("mc_server_link.returning.title", failover.fromName()));
        this.failover = failover;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
            ClientLink.cancelFailover();
            minecraft.gui.setScreen(ClientLink.menuScreen());
        }).bounds(width / 2 - 100, height / 2 + 40, 200, 20).build());
    }

    @Override
    public void tick() {
        if (++ticks == DELAY_TICKS) {
            ClientLink.connect(ClientLink.menuScreen(), failover.lobby());
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int cx = width / 2;
        int y = height / 2 - 40;
        g.centeredText(font, title, cx, y, 0xFFFFFFFF);
        g.centeredText(font, failover.reason(), cx, y + 16, 0xFFA8A8B3);
        g.centeredText(font, Component.translatable("mc_server_link.returning.body", failover.lobby().name()), cx, y + 40, 0xFFFFD27A);
    }
}
