package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.DegradePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomBarLabelsTest {

    @Test
    @DisplayName("有数据年龄时 NO_API 显示年龄，不喊 API 不可用")
    void noApiShowsAgeNotUnavailable() {
        String p = BottomBarLabels.prefix(DegradePolicy.NO_API, false, "数据 8 分钟前");
        assertEquals("数据 8 分钟前", p);
        assertFalse(p.contains("API 不可用"));
    }

    @Test
    @DisplayName("无年龄时 NO_API 才显示不可用")
    void noApiWithoutAge() {
        assertEquals("API 不可用，仅显示本地数据",
                BottomBarLabels.prefix(DegradePolicy.NO_API, false, ""));
    }

    @Test
    @DisplayName("交易未启用提示是人话，无 -D")
    void tradeHintPlainLanguage() {
        assertEquals("交易未启用", BottomBarLabels.tradeHint(false));
        assertEquals("", BottomBarLabels.tradeHint(true));
        assertFalse(BottomBarLabels.tradeHint(false).contains("-D"));
        assertFalse(BottomBarLabels.tradeHint(false).contains("trade.enabled"));
    }

    @Test
    @DisplayName("窄模式前缀文案存在；排序/筛选文案非空")
    void narrowPrefixAndControls() {
        assertTrue(BottomBarLabels.prefix(DegradePolicy.FULL, true).contains("窗口过窄"));
        assertTrue(BottomBarLabels.sortText(BoardInteraction.Sort.DEFAULT).startsWith("排序:"));
        assertTrue(BottomBarLabels.filterText(BoardInteraction.Filter.ALL).startsWith("筛选:"));
    }

    @Test
    @DisplayName("状态文案在筛选右侧：偏移 216；放不下则 statusFits=false")
    void statusAfterFilterLayout() {
        assertEquals(216, BottomBarLabels.STATUS_AFTER_FILTER_X);
        assertEquals(100 + 216, BottomBarLabels.statusTextX(100));
        assertTrue(BottomBarLabels.statusFits(0, 50, 300));
        assertFalse(BottomBarLabels.statusFits(0, 200, 300)); // 216+200 > 300
    }

    @Test
    @DisplayName("底栏计数说明档位：全部点明含多少家破产，排除破产点明已排除")
    void countTextExplainsFilter() {
        assertEquals("共 54 家（含 43 家已破产）",
                BottomBarLabels.countText(BoardInteraction.Filter.ALL, 54, 43, -1));
        assertEquals("共 54 家",
                BottomBarLabels.countText(BoardInteraction.Filter.ALL, 54, 0, -1));
        assertEquals("共 11 家（已排除破产）",
                BottomBarLabels.countText(BoardInteraction.Filter.NO_BANKRUPT, 11, 0, -1));
        assertEquals("共 11 家（仅持仓）",
                BottomBarLabels.countText(BoardInteraction.Filter.HELD, 11, 0, -1));
    }

    @Test
    @DisplayName("底栏计数不再编分母：空列表就是 0 家，不是 0/1")
    void countTextNeverFakesDenominator() {
        String empty = BottomBarLabels.countText(BoardInteraction.Filter.HELD, 0, 0, -1);
        assertEquals("共 0 家（仅持仓）", empty);
        assertFalse(empty.contains("/"), "空列表不许出现 0/1 这种假分母");
    }

    @Test
    @DisplayName("选中行时另说第几行，序号不越界")
    void countTextMentionsSelectedRow() {
        assertEquals("第 4 行 · 共 54 家（含 43 家已破产）",
                BottomBarLabels.countText(BoardInteraction.Filter.ALL, 54, 43, 3));
        assertEquals("第 54 行 · 共 54 家（含 43 家已破产）",
                BottomBarLabels.countText(BoardInteraction.Filter.ALL, 54, 43, 99),
                "越界下标必须夹回总数");
    }

    @Test
    @DisplayName("计数宽度守卫：压到筛选按钮就换短写法")
    void countFitsGuard() {
        assertTrue(BottomBarLabels.countFits(100, 130, 100 + 480), "宽盘面放得下");
        assertFalse(BottomBarLabels.countFits(100, 130, 100 + 280), "窄盘面放不下");
        assertTrue(BottomBarLabels.countFits(100, 54, 100 + 280), "短写法要放得下");
    }

    @Test
    @DisplayName("空档给人话提示：空仓、无活公司、无数据各一句")
    void emptyHintPerFilter() {
        assertEquals("你当前没有持仓",
                BottomBarLabels.emptyHint(BoardInteraction.Filter.HELD));
        assertEquals("没有非破产公司",
                BottomBarLabels.emptyHint(BoardInteraction.Filter.NO_BANKRUPT));
        assertEquals("暂无数据", BottomBarLabels.emptyHint(BoardInteraction.Filter.ALL));
    }

    @Test
    @DisplayName("三档筛选用三个不同的字面：不许出现「按 3 次有 2 次还是全部」")
    void threeFilterLabelsDistinct() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BoardInteraction.Filter f : BoardInteraction.Filter.values()) {
            assertTrue(seen.add(BottomBarLabels.filterText(f)),
                    "筛选文案重复了：" + BottomBarLabels.filterText(f));
        }
        assertEquals(BoardInteraction.Filter.values().length, seen.size());
    }

    @Test
    @DisplayName("盘面底栏状态用 TEXT 白、画在筛选右侧（源码回归）")
    void boardStatusUsesTextAfterFilter() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/StockBoardScreen.java"));
        assertTrue(src.contains("BottomBarLabels.statusTextX"), "须用 statusTextX");
        assertTrue(src.contains("UiPalette.TEXT"), "状态文案须用 TEXT 白");
        int method = src.indexOf("private void drawBottomStatus");
        assertTrue(method > 0);
        // 2026-09-28：计数改成「共 N 家…」后这个方法长了，窗口从 1200 放到 2400，
        // 免得 statusTextX 被截到窗口外误报（断言本身没改）。
        String body = src.substring(method, Math.min(src.length(), method + 2400));
        assertFalse(body.contains("UiPalette.WARN"),
                "drawBottomStatus 不许再用 WARN 画数据年龄");
    }

    @Test
    @DisplayName("底栏计数不再编分母、按钮文案不再被列表早退挡住（源码回归）")
    void boardCountAndLabelsRegression() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/StockBoardScreen.java"));
        assertFalse(src.contains("Math.max(1, visibleRows.size())"),
                "空列表不许再编出 0/1 这种假分母");
        assertTrue(src.contains("BottomBarLabels.countText("), "底栏须用说明档位的计数");
        assertTrue(src.contains("refreshControlLabels();"), "切档后按钮文案须无条件刷新");
        assertFalse(src.contains("Math.max(0, list.selectedIndex())"),
                "别再拿「选中序号」当页码画");
    }

    @Test
    @DisplayName("底栏按钮先于列表入树；mouseClicked 先派发底栏并忽略 doubled")
    void boardBottomButtonsWinClicks() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/StockBoardScreen.java"));
        int init = src.indexOf("protected void init()");
        String initBody = src.substring(init, src.indexOf("void restoreNavAfterRebuild"));
        int sortAdd = initBody.indexOf("addDrawableChild(sortButton)");
        int listAdd = initBody.indexOf("addDrawableChild(list)");
        assertTrue(sortAdd > 0 && listAdd > sortAdd, "排序/筛选必须加在列表前面");
        assertTrue(src.contains("public boolean mouseClicked(Click click, boolean doubled)"));
        assertTrue(src.contains("if (doubled)"), "连点 doubled 不得再 cycle");
    }
}
