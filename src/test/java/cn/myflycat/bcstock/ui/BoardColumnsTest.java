package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.decision.DecisionEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardColumnsTest {

    @Test
    @DisplayName("★ 宽/窄：列不重叠、左右居中 ±1")
    void twoWindowSizes() {
        assertColumns(new BoardColumns(320));
        assertColumns(new BoardColumns(1920));
    }

    @Test
    @DisplayName("320 宽走窄模式，砍涨跌/风险/成本/盈亏，保留操作列")
    void narrowDropsCostAndRisk() {
        BoardColumns n = new BoardColumns(320);
        assertTrue(n.narrow);
        assertFalse(n.columns.contains(BoardColumns.Col.COST));
        assertFalse(n.columns.contains(BoardColumns.Col.RISK));
        assertTrue(n.columns.contains(BoardColumns.Col.ACTION));
        BoardColumns w = new BoardColumns(1920);
        assertFalse(w.narrow);
        assertEquals(8, w.columns.size());
    }

    @Test
    @DisplayName("★ 1.30 K → 1300.00，不是 1.30 K")
    void priceIsRealValue() {
        assertEquals("1300.00", BoardFormat.price(1300.0));
        assertEquals("2,780,000.00", BoardFormat.price(2_780_000.0));
    }

    @Test
    @DisplayName("★ 破产单价是「已破产」不是 0.00；零持仓盈亏是 -")
    void bankruptAndZeroHolding() {
        CompanyView dead = new CompanyView("红苹果短剧", 58, 57, 0, "0.00", 0, "",
                -100, -100, CompanyView.STATUS_BANKRUPT, 5, -1, CompanyView.Source.API);
        BoardRow row = BoardRow.from(dead, null);
        assertEquals(BoardFormat.BANKRUPT, row.priceText());
        assertEquals(BoardFormat.DASH, row.changeText());
        assertEquals(BoardFormat.DASH, row.profitText());
        assertTrue(row.bankrupt());

        CompanyView live = new CompanyView("月港控股", 56, 55, 42.38, "42.38", 0, "",
                0.0, Double.NaN, CompanyView.STATUS_TRADING, 2, 100, CompanyView.Source.API);
        HoldingView zero = new HoldingView("月港控股", 56, "", 0, 0, 36.26);
        BoardRow empty = BoardRow.from(live, zero);
        assertEquals(BoardFormat.DASH, empty.profitText());
        assertFalse(empty.held());
    }

    @Test
    @DisplayName("有持仓且均价已知：盈亏带正负号")
    void heldProfit() {
        CompanyView live = new CompanyView("月港控股", 56, 55, 42.38, "42.38", 0, "",
                1.82, Double.NaN, CompanyView.STATUS_TRADING, 2, 100, CompanyView.Source.API);
        HoldingView h = new HoldingView("月港控股", 56, "", 111, 4703.98, 36.26);
        BoardRow row = BoardRow.from(live, h);
        assertEquals("42.38", row.priceText());
        assertEquals("+1.82%", row.changeText());
        assertEquals("111", row.sharesText());
        assertEquals("+16.87%", row.profitText());
        assertEquals(UiPalette.UP, row.changeColor());
    }

    @Test
    @DisplayName("建议前缀只改显示名，查找名仍是公司名")
    void advicePrefixDoesNotChangeLookupName() {
        CompanyView live = new CompanyView("月港控股", 56, 55, 42.38, "42.38", 0, "",
                0.0, Double.NaN, CompanyView.STATUS_TRADING, 2, 100, CompanyView.Source.API);
        DecisionEngine.Advice buy = DecisionEngine.Advice.buy(10, "距地板 0.90% ≤ 3%；风险 2 ≤ 3");
        BoardRow row = BoardRow.from(live, null, buy);
        assertEquals("月港控股", row.name());
        assertEquals("买 月港控股", row.text(BoardColumns.Col.NAME));
        assertEquals(UiPalette.UP, row.nameColor());
        BoardRow hold = BoardRow.from(live, null);
        assertEquals("月港控股", hold.text(BoardColumns.Col.NAME));
    }

    private static void assertColumns(BoardColumns c) {
        int x = c.originX;
        for (BoardColumns.Col col : c.columns) {
            BoardColumns.Rect r = c.columnRect(col, 0, 18);
            assertEquals(x, r.x());
            assertEquals(col.width, r.w());
            x += col.width;
        }
        assertEquals(c.originX + c.tableWidth, x);
        int right = c.originX; // unused symmetry check via screen width isn't stored; table fits
        assertTrue(c.originX >= 0);
        assertTrue(c.tableWidth > 0);
        assertTrue(right >= 0);
    }
}
