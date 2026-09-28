package cn.myflycat.bcstock.data.reply;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyTextTest {

    @Test
    @DisplayName("价格：42.38 / 1.54 K / 2.78 M")
    void parsePriceUnits() {
        assertEquals(42.38, ReplyText.parsePrice("价格: 42.38"), 1e-9);
        assertEquals(1540.0, ReplyText.parsePrice("价格: 1.54 K"), 1e-9);
        assertEquals(2_780_000.0, ReplyText.parsePrice("价格: 2.78 M"), 1e-9);
        assertTrue(Double.isNaN(ReplyText.parsePrice("状态: 交易中")));
        assertEquals("1.54 K", ReplyText.priceRaw("价格: 1.54 K"));
    }

    @Test
    @DisplayName("块开始 / 块结束 / 错误前缀")
    void blockMarkers() {
        assertTrue(ReplyText.isBlockStart("-=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-"));
        assertTrue(ReplyText.isBlockEnd("-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-"));
        assertFalse(ReplyText.isBlockEnd("-=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-"));
        assertTrue(ReplyText.isError("帕拉伦股市 > 无效的公司。"));
        assertFalse(ReplyText.isError("月港控股"));
        assertTrue(ReplyText.isNoHoldingsMessage("帕拉伦股市 > 您没有任何股票。"));
        assertFalse(ReplyText.isNoHoldingsMessage("帕拉伦股市 > 无效的公司。"));
    }

    @Test
    @DisplayName("行情刷新广播：原文 / 无句号 / 颜色码")
    void marketUpdatedBroadcast() {
        assertTrue(ReplyText.isMarketUpdated("帕拉伦股市 > 所有商业股票已更新。"));
        assertTrue(ReplyText.isMarketUpdated("帕拉伦股市 > 所有商业股票已更新"));
        assertTrue(ReplyText.isMarketUpdated("§e帕拉伦股市 > 所有商业股票已更新。"));
        assertFalse(ReplyText.isMarketUpdated("帕拉伦股市 > 无效的公司。"));
        assertFalse(ReplyText.isMarketUpdated("所有商业股票已更新。"));
    }
}
