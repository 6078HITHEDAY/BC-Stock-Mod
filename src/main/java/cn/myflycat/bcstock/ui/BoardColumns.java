package cn.myflycat.bcstock.ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 盘面列宽布局（纯逻辑）。自绘表头 / Entry 行共用。
 * 不 import {@code net.minecraft}。
 */
public final class BoardColumns {

    public enum Col {
        NAME("公司", 100, false),
        PRICE("单价", 60, true),
        CHANGE("涨跌", 56, true),
        RISK("风险", 32, true),
        SHARES("持仓", 48, true),
        COST("成本", 56, true),
        PROFIT("盈亏", 56, true),
        ACTION("操作", 72, false);

        public final String header;
        public final int width;
        public final boolean numeric;

        Col(String header, int width, boolean numeric) {
            this.header = header;
            this.width = width;
            this.numeric = numeric;
        }
    }

    public record Rect(int x, int y, int w, int h) {
        public int x2() {
            return x + w;
        }
    }

    public final boolean narrow;
    public final List<Col> columns;
    public final int tableWidth;
    public final int originX;

    public BoardColumns(int screenWidth) {
        this.narrow = screenWidth < UiPalette.TABLE_W + 2 * UiPalette.PAD_X;
        Set<Col> hidden = this.narrow
                ? EnumSet.of(Col.RISK, Col.COST, Col.PROFIT, Col.CHANGE)
                : EnumSet.noneOf(Col.class);
        List<Col> cols = new ArrayList<>();
        int sum = 0;
        for (Col c : Col.values()) {
            if (!hidden.contains(c)) {
                cols.add(c);
                sum += c.width;
            }
        }
        this.columns = List.copyOf(cols);
        this.tableWidth = sum;
        this.originX = Math.max(UiPalette.PAD_X, (screenWidth - sum) / 2);
    }

    /** 相对表左缘的列矩形（y/h 由调用方填）。 */
    public Rect columnRect(Col col, int y, int h) {
        int x = originX;
        for (Col c : columns) {
            if (c == col) {
                return new Rect(x, y, c.width, h);
            }
            x += c.width;
        }
        return new Rect(originX, y, 0, h);
    }

    /** 在 entry 内容区内的列 x（以 contentX 为表左）。 */
    public int contentColumnX(int contentX, Col col) {
        int x = contentX;
        for (Col c : columns) {
            if (c == col) {
                return x;
            }
            x += c.width;
        }
        return contentX;
    }
}
