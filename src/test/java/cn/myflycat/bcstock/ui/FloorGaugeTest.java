package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.TradeSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloorGaugeTest {

    @AfterEach
    void resetTrade() {
        TradeSettings.setEnabledForTest(false);
    }

    @Test
    @DisplayName("★ 三个端点：地板 0 / 天花板 1 / 中间线性")
    void endpoints() {
        assertEquals(0.0, FloorGauge.position(10, 10, 20), 1e-12);
        assertEquals(1.0, FloorGauge.position(20, 10, 20), 1e-12);
        assertEquals(0.5, FloorGauge.position(15, 10, 20), 1e-12);
        assertTrue(Double.isNaN(FloorGauge.position(15, 20, 10)));
    }

    @Test
    @DisplayName("★ MODE=FLOOR，标签是距地板")
    void floorModeUnlocked() {
        assertEquals(FloorGauge.Mode.FLOOR, FloorGauge.MODE);
        assertEquals("距地板", FloorGauge.label());
        assertTrue(FloorGauge.label().contains("地板"));
    }

    @Test
    @DisplayName("★ 危险区闪烁用时间取模，500ms 开/关")
    void blinkIsModulo() {
        assertTrue(FloorGauge.blinkOn(0));
        assertTrue(FloorGauge.blinkOn(499));
        assertFalse(FloorGauge.blinkOn(500));
        assertFalse(FloorGauge.blinkOn(999));
        assertTrue(FloorGauge.danger(0.0));
        assertFalse(FloorGauge.danger(0.02));
    }

    @Test
    @DisplayName("浮层：有地板显示距离；无地板显示未知（不许 0%）")
    void detailFloorLine() {
        CompanyView c = new CompanyView("月港控股", 56, 55, 42.38, "42.38", 4_720_000, "4.72 M",
                1.5, -60.63, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
        HoldingView h = new HoldingView("月港控股", 56, "", 111, 4703.98, 36.26);
        FloorView floor = new FloorView(55, "月港控股", 42.0, 42.38, 0.9048, 42.294,
                true, 0.12, "near");
        var lines = DetailLines.of(c, h, floor);
        assertEquals(10, lines.size());
        assertEquals("月港控股  #56", lines.get(0));
        assertTrue(lines.get(6).startsWith("距地板:"));
        assertTrue(lines.get(6).contains("0.90"));
        assertFalse(lines.get(6).contains("0.00%") && !lines.get(6).contains("0.90"));
        assertTrue(lines.get(8).startsWith("建议:"));

        var unknown = DetailLines.of(c, null, null);
        assertEquals("距地板: " + BoardFormat.UNKNOWN, unknown.get(6));
        assertFalse(unknown.get(6).contains("0.00%"));
        assertFalse(unknown.get(6).contains("安全"));
        assertEquals("建议: 观望　地板未知", unknown.get(8));
    }

    @Test
    @DisplayName("开关打开才显示行内买卖提示")
    void shortcutsWhenEnabled() {
        TradeSettings.setEnabledForTest(true);
        CompanyView c = new CompanyView("月港控股", 56, 55, 42.38, "42.38", 4_720_000, "4.72 M",
                1.5, -60.63, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
        var lines = DetailLines.of(c, null);
        assertTrue(lines.get(9).contains("买"));
        assertTrue(lines.get(9).contains("卖"));
        assertFalse(lines.get(9).contains("[右键]"));
        TradeSettings.setEnabledForTest(false);
        assertEquals("买卖入口已关闭", DetailLines.of(c, null).get(9));
    }
}
