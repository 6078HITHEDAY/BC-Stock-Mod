package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自动化配置的解析边界：白名单分隔符、默认值。 */
class AutoTradeSettingsTest {

    @Test
    @DisplayName("★ 白名单同时认半角/全角逗号与空白")
    void whitelistParsesBothCommas() {
        assertEquals(java.util.List.of("月港控股", "帕拉伦联合储蓄", "10"),
                AutoTradeSettings.parseWhitelist(" 月港控股, 帕拉伦联合储蓄，10 "));
    }

    @Test
    @DisplayName("空 / null 白名单 = 全部可交易标的")
    void blankWhitelistMeansAll() {
        assertTrue(AutoTradeSettings.parseWhitelist(null).isEmpty());
        assertTrue(AutoTradeSettings.parseWhitelist("   ").isEmpty());
        assertTrue(AutoTradeSettings.parseWhitelist(",，").isEmpty());
    }

    @Test
    @DisplayName("模式解析：未知值退回 advice（安全默认）")
    void modeParsingFallsBackToAdvice() {
        assertEquals(AutoTradeSettings.Mode.ADVICE, AutoTradeSettings.Mode.parse(null));
        assertEquals(AutoTradeSettings.Mode.ADVICE, AutoTradeSettings.Mode.parse("whatever"));
        assertEquals(AutoTradeSettings.Mode.LIMITED, AutoTradeSettings.Mode.parse(" LIMITED "));
        assertEquals(AutoTradeSettings.Mode.FULL, AutoTradeSettings.Mode.parse("full"));
    }
}
