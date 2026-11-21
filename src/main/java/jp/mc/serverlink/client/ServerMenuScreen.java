package jp.mc.serverlink.client;

import java.util.ArrayList;
import java.util.List;
import jp.mc.serverlink.net.MenuPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * サーバーの一覧の画面（/server を引数なしで実行したとき、サーバーから届く）。
 * <pre>
 *              サーバーを選ぶ
 *           いまいる場所: ロビー
 * ┌──────────────────────────────────┐
 * │▌ロビー                  [いまここ] │
 * │  最初に入る待機所       ● 3/20 人  │
 * ├──────────────────────────────────┤
 * │▌サバイバル              ● 動いています │
 * │  メインのワールド         5/20 人  │
 * └──────────────────────────────────┘
 *                [ 閉じる ]
 * </pre>
 * 押すと「/server &lt;id&gt;」を送る（動いているかはサーバーがもう一度確かめる）。止まっているサーバーといまいるサーバーは押せない。
 */
final class ServerMenuScreen extends Screen {
    private static final int ROW_W = 300;
    private static final int ROW_H = 34;
    private static final int GAP = 4;
    private static final int TOP = 52;
    private static final int BOTTOM = 36;

    // 色（黒に近い地に、つながりの青緑を差し色に 1 色だけ。状態は緑・赤・琥珀・灰）
    private static final int ROW_BG = 0xC0121519;
    private static final int ROW_HOVER = 0xE01B2329;
    private static final int ROW_CURRENT = 0xC0161C1F;
    private static final int ACCENT = 0xFF5CCFD6;
    private static final int TEXT = 0xFFF1F2F4;
    private static final int SUB = 0xFF9AA3AB;
    private static final int DIM = 0xFF5F676E;
    private static final int OK = 0xFF5BDB8B;
    private static final int BAD = 0xFFFF7A7A;
    private static final int WARN = 0xFFFFC857;
    private static final int TAG_BG = 0x335CCFD6;

    private final MenuPayload menu;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private int visible;

    ServerMenuScreen(MenuPayload menu) {
        super(Component.translatable("mc_server_link.menu.title"));
        this.menu = menu;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rows.clear();
        visible = Math.max(1, (height - TOP - BOTTOM) / (ROW_H + GAP));
        scroll = Math.max(0, Math.min(scroll, menu.entries().size() - visible));
        int x = (width - ROW_W) / 2;
        for (MenuPayload.Entry e : menu.entries()) {
            Row r = new Row(x, ROW_W, e);
            rows.add(r);
            addRenderableWidget(r);
        }
        layoutRows();
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 75, height - BOTTOM + 8, 150, 20).build());
    }

    private void layoutRows() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int slot = i - scroll;
            r.visible = slot >= 0 && slot < visible;
            r.setY(TOP + slot * (ROW_H + GAP));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int max = Math.max(0, rows.size() - visible);
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(sy)));
        if (next != scroll) {
            scroll = next;
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int cx = width / 2;
        g.centeredText(font, title, cx, 18, TEXT);
        g.centeredText(font, Component.translatable("mc_server_link.menu.here", menu.thisName()), cx, 32, SUB);
        if (rows.size() > visible) {
            g.centeredText(font, Component.translatable("mc_server_link.menu.more", scroll + 1, Math.min(rows.size(), scroll + visible), rows.size()),
                    cx, height - BOTTOM - 2, DIM);
        }
    }

    /** 一覧の 1 行。押すと移動する */
    private final class Row extends AbstractButton {
        private final MenuPayload.Entry entry;

        Row(int x, int w, MenuPayload.Entry entry) {
            super(x, 0, w, ROW_H, Component.literal(entry.name()));
            this.entry = entry;
            this.active = !entry.current() && entry.state() != MenuPayload.State.OFFLINE;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.connection.sendCommand("server " + entry.id());
            }
            onClose();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            Font f = Minecraft.getInstance().font;
            int x0 = getX();
            int y0 = getY();
            int x1 = x0 + getWidth();
            int y1 = y0 + getHeight();
            boolean hot = active && isHoveredOrFocused();
            g.fill(x0, y0, x1, y1, entry.current() ? ROW_CURRENT : hot ? ROW_HOVER : ROW_BG);
            // 左端の縦線: 押せるものは差し色、いまいる所は細く、押せないものは灰
            int bar = entry.current() ? ACCENT : active ? (hot ? ACCENT : 0x885CCFD6) : DIM;
            g.fill(x0, y0, x0 + 2, y1, bar);
            if (hot) {
                g.outline(x0, y0, getWidth(), getHeight(), 0x665CCFD6);
            }

            int tx = x0 + 10;
            int nameColor = entry.state() == MenuPayload.State.OFFLINE ? DIM : TEXT;
            Component name = entry.lobby()
                    ? Component.translatable("mc_server_link.menu.lobby_name", entry.name())
                    : Component.literal(entry.name());
            g.text(f, name, tx, y0 + 7, nameColor, true);
            if (!entry.description().isEmpty()) {
                g.text(f, f.plainSubstrByWidth(entry.description(), getWidth() - 120), tx, y0 + 20,
                        entry.state() == MenuPayload.State.OFFLINE ? DIM : SUB, false);
            }

            // 右上: 状態、右下: 人数
            int right = x1 - 8;
            if (entry.current()) {
                Component tag = Component.translatable("mc_server_link.menu.current");
                int tw = f.width(tag);
                g.fill(right - tw - 8, y0 + 5, right, y0 + 17, TAG_BG);
                g.text(f, tag, right - tw - 4, y0 + 7, ACCENT, false);
            } else {
                Component st = stateLabel();
                int sw = f.width(st);
                g.text(f, st, right - sw, y0 + 7, stateColor(), false);
                g.fill(right - sw - 9, y0 + 8, right - sw - 4, y0 + 13, stateColor());
            }
            if (entry.online() >= 0) {
                Component count = entry.max() >= 0
                        ? Component.translatable("mc_server_link.menu.count", entry.online(), entry.max())
                        : Component.translatable("mc_server_link.menu.count_only", entry.online());
                g.text(f, count, right - f.width(count), y0 + 20, SUB, false);
            }
        }

        private Component stateLabel() {
            return Component.translatable(switch (entry.state()) {
                case ONLINE -> "mc_server_link.menu.state.online";
                case OFFLINE -> "mc_server_link.menu.state.offline";
                case UNKNOWN -> "mc_server_link.menu.state.unknown";
                case CHECKING -> "mc_server_link.menu.state.checking";
            });
        }

        private int stateColor() {
            return switch (entry.state()) {
                case ONLINE -> OK;
                case OFFLINE -> BAD;
                case UNKNOWN -> WARN;
                case CHECKING -> DIM;
            };
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
