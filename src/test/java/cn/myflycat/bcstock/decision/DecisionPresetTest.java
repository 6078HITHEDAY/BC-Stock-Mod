package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.BcStockSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DecisionPresetTest {

    @AfterEach
    void reset() {
        BcStockSettings.setDecisionPresetForTest(DecisionPreset.DEFAULT);
        AutoTradeSettings.resetForTest();
    }

    @Test
    @DisplayName("★ 三档取值符合表（待真人确认）")
    void threePresetsMatchTable() {
        assertEquals(3.0, DecisionPreset.DEFAULT.entryDistancePct(), 1e-9);
        assertEquals(50.0, DecisionPreset.DEFAULT.takeProfitPct(), 1e-9);
        assertEquals(-30.0, DecisionPreset.DEFAULT.stopLossPct(), 1e-9);
        assertEquals(10, DecisionPreset.DEFAULT.defaultBuyQty());
        assertEquals(3, DecisionPreset.DEFAULT.maxRisk());

        assertEquals(8.0, DecisionPreset.AGGRESSIVE.entryDistancePct(), 1e-9);
        assertEquals(100.0, DecisionPreset.AGGRESSIVE.takeProfitPct(), 1e-9);
        assertEquals(-50.0, DecisionPreset.AGGRESSIVE.stopLossPct(), 1e-9);
        assertEquals(100, DecisionPreset.AGGRESSIVE.defaultBuyQty());
        assertEquals(5, DecisionPreset.AGGRESSIVE.maxRisk());

        assertEquals(1.0, DecisionPreset.CONSERVATIVE.entryDistancePct(), 1e-9);
        assertEquals(30.0, DecisionPreset.CONSERVATIVE.takeProfitPct(), 1e-9);
        assertEquals(-15.0, DecisionPreset.CONSERVATIVE.stopLossPct(), 1e-9);
        assertEquals(1, DecisionPreset.CONSERVATIVE.defaultBuyQty());
        assertEquals(2, DecisionPreset.CONSERVATIVE.maxRisk());

        assertEquals(DecisionPreset.DEFAULT, DecisionPreset.parse("default"));
        assertEquals(DecisionPreset.AGGRESSIVE, DecisionPreset.parse("aggressive"));
        assertEquals(DecisionPreset.CONSERVATIVE, DecisionPreset.parse("conservative"));
        assertEquals(DecisionPreset.DEFAULT, BcStockSettings.decisionPreset());
    }

    @Test
    @DisplayName("★ 自动化开关默认 advice；kill 默认 false")
    void autoDefaults() {
        assertEquals(AutoTradeSettings.Mode.ADVICE, AutoTradeSettings.mode());
        assertEquals(10, AutoTradeSettings.maxPerTrade());
        assertEquals(100, AutoTradeSettings.maxPerDay());
        assertFalse(AutoTradeSettings.kill());
    }
}
