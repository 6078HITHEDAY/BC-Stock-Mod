package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.ApiClient;
import cn.myflycat.bcstock.data.KlineCache;
import cn.myflycat.bcstock.data.KlineSeries;
import cn.myflycat.bcstock.data.WalletView;
import java.util.Optional;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * 仍需自绘：K 线折线（金色保留）。详情已迁独立 {@code DetailScreen}。
 */
public final class BoardRenderer {

    public static final int KLINE_CHART_H = 48;

    private BoardRenderer() {
    }

    static String walletText(WalletView wallet) {
        if (wallet == null || !wallet.known()) {
            return "余额 --";
        }
        return "余额 " + BoardFormat.price(wallet.balance());
    }

    /** K 线块：最高/最低标注 + 折线；无数据时占位。渲染路径只读缓存。 */
    public static void drawKlineBlock(DrawContext g, TextRenderer font,
                                      int x, int y, int w,
                                      Optional<KlineSeries> kline, KlineCache.Status status) {
        KlineCache.Status st = (status == null) ? KlineCache.Status.EMPTY : status;
        if (kline != null && kline.isPresent()) {
            KlineSeries series = kline.get();
            g.drawText(font, "最高 " + series.maxLabel(), x, y, UiPalette.UP, false);
            String min = "最低 " + series.minLabel();
            g.drawText(font, min, x + w - font.getWidth(min), y, UiPalette.DOWN, false);
            String window = "近 " + ApiClient.KLINE_LIMIT + " 根";
            g.drawText(font, window, x + (w - font.getWidth(window)) / 2, y, UiPalette.MUTED, false);
            int chartY = y + 12;
            int[][] pts = series.screenPoints(x, chartY, w, KLINE_CHART_H);
            drawPolyline(g, pts, UiPalette.GOLD);
            return;
        }
        String placeholder = (st == KlineCache.Status.LOADING) ? "走势加载中" : "走势 --";
        g.drawText(font, placeholder, x, y, UiPalette.MUTED, false);
    }

    /** 折线：相邻点之间用 1px fill 连（不用 blit / 渐变）。 */
    static void drawPolyline(DrawContext g, int[][] pts, int color) {
        if (pts == null || pts.length == 0) {
            return;
        }
        for (int i = 0; i < pts.length; i++) {
            int px = pts[i][0];
            int py = pts[i][1];
            g.fill(px, py, px + 1, py + 1, color);
            if (i + 1 < pts.length) {
                drawSegment(g, px, py, pts[i + 1][0], pts[i + 1][1], color);
            }
        }
    }

    private static void drawSegment(DrawContext g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0;
        int y = y0;
        while (x != x1 || y != y1) {
            g.fill(x, y, x + 1, y + 1, color);
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }
}
