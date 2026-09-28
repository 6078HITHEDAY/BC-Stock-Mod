package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.BoardInteraction;
import cn.myflycat.bcstock.ui.BoardRow;
import cn.myflycat.bcstock.ui.BoardFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BcStockSettingsTest {

    @AfterEach
    void reset() {
        BcStockSettings.setShowBankruptForTest(true);
    }

    @Test
    @DisplayName("默认显示破产；关掉只改开盘默认筛选项，「全部」仍有破产行")
    void showBankruptFilter() {
        assertTrue(BcStockSettings.showBankrupt());
        BoardRow live = row("月港控股", false);
        BoardRow dead = row("红苹果短剧", true);
        List<BoardRow> src = List.of(live, dead);

        List<BoardRow> all = BoardInteraction.apply(src, BoardInteraction.Sort.DEFAULT,
                BoardInteraction.Filter.ALL, true);
        assertEquals(2, all.size());

        BcStockSettings.setShowBankruptForTest(false);
        List<BoardRow> stillAll = BoardInteraction.apply(src, BoardInteraction.Sort.DEFAULT,
                BoardInteraction.Filter.ALL, BcStockSettings.showBankrupt());
        assertEquals(2, stillAll.size(), "全部 + showBankrupt=false 仍列出破产");
        assertTrue(stillAll.stream().anyMatch(BoardRow::bankrupt));
        List<BoardRow> excluded = BoardInteraction.apply(src, BoardInteraction.Sort.DEFAULT,
                BoardInteraction.Filter.NO_BANKRUPT);
        assertEquals(1, excluded.size());
        assertEquals("月港控股", excluded.get(0).name());
        assertEquals(BoardInteraction.Filter.NO_BANKRUPT,
                BoardInteraction.defaultFilter(BcStockSettings.showBankrupt()));
        assertEquals(BoardFormat.BANKRUPT, dead.priceText());
    }

    private static BoardRow row(String name, boolean bankrupt) {
        CompanyView c = new CompanyView(name, 1, 1, 10, "10", Double.NaN, "",
                0, 0,
                bankrupt ? CompanyView.STATUS_BANKRUPT : CompanyView.STATUS_TRADING,
                1, 1, CompanyView.Source.API);
        return BoardRow.from(c, null);
    }
}
