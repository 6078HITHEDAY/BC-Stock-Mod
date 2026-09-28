package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorCache;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.KlineCache;
import cn.myflycat.bcstock.data.KlineSeries;
import cn.myflycat.bcstock.data.SnapshotStore;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * 公司详情：独立 Screen。原版半透明背景；文本 + K 线继续自绘。
 * 返回时 {@link #back} 复用同一 {@link StockBoardScreen} 实例，不丢选中/滚动。
 *
 * <p>K 线只在 {@link #init()} 请求一次；{@link #render} 只读缓存。
 */
public final class DetailScreen extends Screen {

    private static final int LINE_H = 12;
    private static final int PAD = 16;

    private final StockBoardScreen back;
    private final String companyName;
    private int apiId = CompanyView.ID_UNKNOWN;

    public DetailScreen(StockBoardScreen back, BoardRow row) {
        super(Text.literal(row == null ? "详情" : row.name()));
        this.back = back;
        this.companyName = (row == null) ? "" : row.name();
    }

    @Override
    protected void init() {
        clearChildren();
        // 输入路径请求 K 线；渲染路径只读
        CompanyView c = SnapshotStore.SHARED.get().companyOf(companyName);
        if (c != null && c.apiIdKnown()) {
            apiId = c.apiId();
            KlineCache.SHARED.request(apiId);
        } else {
            apiId = CompanyView.ID_UNKNOWN;
        }
        int bw = 100;
        int bh = 20;
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> goBack())
                .dimensions(this.width / 2 - bw / 2, this.height - 40, bw, bh)
                .build());
    }

    private void goBack() {
        if (this.client != null) {
            this.client.setScreen(back); // 复用实例，不许 new
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // 原版半透明底 + 虚化

        CompanyView c = SnapshotStore.SHARED.get().companyOf(companyName);
        FloorView floor = FloorCache.SHARED.ofName(companyName).orElse(null);
        List<String> lines = DetailLines.of(c, SnapshotStore.SHARED.get().holdingOf(companyName), floor);
        long nowMs = Util.getMeasuringTimeMs();

        int contentW = Math.min(400, this.width - 2 * PAD);
        int x = (this.width - contentW) / 2;
        int y = 40;
        double floorDist = DetailLines.floorDistancePct(floor);
        boolean floorDanger = DetailLines.floorInDanger(floor);
        for (int i = 0; i < lines.size(); i++) {
            int color = UiPalette.TEXT;
            if (i == 0) {
                color = UiPalette.TEXT; // 公司名：主文本白
            } else if (i == lines.size() - 1) {
                color = UiPalette.MUTED; // 操作提示：次要灰
            }
            if (i == 6) {
                color = FloorGauge.barColorForDistance(floorDist, floorDanger, nowMs);
            }
            context.drawText(this.textRenderer, lines.get(i), x, y, color, false);
            y += LINE_H;
        }
        y += 8;
        Optional<KlineSeries> kline = Optional.empty();
        KlineCache.Status status = KlineCache.Status.EMPTY;
        if (apiId >= 0) {
            status = KlineCache.SHARED.status(apiId);
            kline = KlineCache.SHARED.get(apiId);
        }
        BoardRenderer.drawKlineBlock(context, this.textRenderer, x, y, contentW, kline, status);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            goBack();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 自己 goBack，避免关到游戏菜单
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
