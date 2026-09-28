package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardInteractionTest {

    @Test
    @DisplayName("★ 筛选只看持有 / 排除破产；排序按涨跌")
    void filterAndSort() {
        BoardRow a = row("PR雪地", 4, -4.19, false, false);
        BoardRow b = row("月港控股", 2, 1.82, true, false);
        BoardRow c = row("红苹果短剧", 5, -100, false, true);
        List<BoardRow> src = List.of(a, b, c);

        List<BoardRow> held = BoardInteraction.apply(src, BoardInteraction.Sort.DEFAULT,
                BoardInteraction.Filter.HELD);
        assertEquals(List.of("月港控股"), names(held));

        List<BoardRow> alive = BoardInteraction.apply(src, BoardInteraction.Sort.DEFAULT,
                BoardInteraction.Filter.NO_BANKRUPT);
        assertEquals(2, alive.size());
        assertFalse(alive.stream().anyMatch(BoardRow::bankrupt));

        List<BoardRow> byChg = BoardInteraction.apply(src, BoardInteraction.Sort.CHANGE,
                BoardInteraction.Filter.ALL);
        assertEquals("月港控股", byChg.get(0).name());
        assertEquals("PR雪地", byChg.get(1).name());
        assertEquals("红苹果短剧", byChg.get(2).name());

        List<BoardRow> allHiddenSetting = BoardInteraction.apply(src, BoardInteraction.Sort.DEFAULT,
                BoardInteraction.Filter.ALL, false);
        assertEquals(3, allHiddenSetting.size(), "showBankrupt=false 不得把「全部」里的破产删掉");
        assertTrue(allHiddenSetting.stream().anyMatch(BoardRow::bankrupt));
        assertEquals(BoardInteraction.Filter.NO_BANKRUPT, BoardInteraction.defaultFilter(false));
        assertEquals(BoardInteraction.Filter.ALL, BoardInteraction.defaultFilter(true));
    }

    private static List<String> names(List<BoardRow> rows) {
        return rows.stream().map(BoardRow::name).toList();
    }

    private static BoardRow row(String name, int risk, double chg, boolean held, boolean bankrupt) {
        CompanyView c = new CompanyView(name, 1, 1, 10, "10", 0, "",
                chg, chg,
                bankrupt ? CompanyView.STATUS_BANKRUPT : CompanyView.STATUS_TRADING,
                risk, 1, CompanyView.Source.API);
        HoldingView h = held
                ? new HoldingView(name, 1, "", 10, 120, 10)
                : new HoldingView(name, 1, "", 0, 0, 10);
        return BoardRow.from(c, h);
    }
}
