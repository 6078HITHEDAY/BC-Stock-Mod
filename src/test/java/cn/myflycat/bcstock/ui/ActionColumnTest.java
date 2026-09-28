package cn.myflycat.bcstock.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionColumnTest {

    @Test
    @DisplayName("★ TABLE_W=480；宽模式含 ACTION；窄模式仍含 ACTION")
    void actionAlwaysVisible() {
        assertEquals(480, UiPalette.TABLE_W);
        BoardColumns wide = new BoardColumns(1920);
        assertFalse(wide.narrow);
        assertTrue(wide.columns.contains(BoardColumns.Col.ACTION));
        assertEquals(8, wide.columns.size());

        BoardColumns narrow = new BoardColumns(320);
        assertTrue(narrow.narrow);
        assertTrue(narrow.columns.contains(BoardColumns.Col.ACTION), "窄模式按钮必须可见");
        assertFalse(narrow.columns.contains(BoardColumns.Col.RISK));
        assertFalse(narrow.columns.contains(BoardColumns.Col.COST));
        assertFalse(narrow.columns.contains(BoardColumns.Col.PROFIT));
        assertFalse(narrow.columns.contains(BoardColumns.Col.CHANGE));
        assertTrue(narrow.columns.contains(BoardColumns.Col.NAME));
        assertTrue(narrow.columns.contains(BoardColumns.Col.SHARES));
    }

    @Test
    @DisplayName("★ 买卖按钮可点性由 TradeButtonState 决定（非自绘命中）")
    void actionButtonGates() {
        assertFalse(TradeButtonState.buyActive(false, false));
        assertFalse(TradeButtonState.buyActive(true, true));
        assertTrue(TradeButtonState.buyActive(true, false));
        assertFalse(TradeButtonState.sellActive(false, true));
        assertFalse(TradeButtonState.sellActive(true, false));
        assertTrue(TradeButtonState.sellActive(true, true));
    }
}
