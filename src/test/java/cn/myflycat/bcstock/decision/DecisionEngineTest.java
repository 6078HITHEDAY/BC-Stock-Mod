package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorSnapshot;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionEngineTest {

    @Test
    @DisplayName("★ floors-sample：DEFAULT 买联合储蓄 / 月港；CONSERVATIVE 都观望")
    void sampleFloorsThreePresets() throws Exception {
        FloorSnapshot floors = loadSample();
        FloorView para = floors.ofName("帕拉伦联合储蓄");
        FloorView yue = floors.ofName("月港控股");
        CompanyView paraCo = company("帕拉伦联合储蓄", 10, 1);
        CompanyView yueCo = company("月港控股", 56, 3);

        DecisionEngine.Advice paraDef = DecisionEngine.advise(paraCo, null, para, DecisionPreset.DEFAULT);
        assertEquals(DecisionEngine.Side.BUY, paraDef.side());
        assertEquals(10, paraDef.qty());
        assertTrue(paraDef.reason().contains("2.93%"));
        assertTrue(paraDef.reason().contains("≤ 3%"));

        DecisionEngine.Advice paraCon = DecisionEngine.advise(paraCo, null, para, DecisionPreset.CONSERVATIVE);
        assertEquals(DecisionEngine.Side.HOLD, paraCon.side());
        assertTrue(paraCon.reason().contains("距地板"));

        DecisionEngine.Advice yueDef = DecisionEngine.advise(yueCo, null, yue, DecisionPreset.DEFAULT);
        assertEquals(DecisionEngine.Side.BUY, yueDef.side());
        assertEquals(10, yueDef.qty());

        DecisionEngine.Advice yueCon = DecisionEngine.advise(yueCo, null, yue, DecisionPreset.CONSERVATIVE);
        assertEquals(DecisionEngine.Side.HOLD, yueCon.side());
        assertTrue(yueCon.reason().contains("风险 3 > 2"));
    }

    @Test
    @DisplayName("★ 浮盈 +60% / 浮亏 -35% → DEFAULT 卖")
    void takeProfitAndStopLoss() {
        CompanyView c = company("月港控股", 56, 3);
        FloorView near = new FloorView(55, "月港控股", 42.0, 42.38, 0.90, 42.29,
                true, 0.12, "near");
        HoldingView win = holding("月港控股", 10, 160.0, 10.0);
        assertEquals(60.0, win.profitPct(), 1e-9);
        DecisionEngine.Advice tp = DecisionEngine.advise(c, win, near, DecisionPreset.DEFAULT);
        assertEquals(DecisionEngine.Side.SELL, tp.side());
        assertEquals(10, tp.qty());
        assertTrue(tp.reason().contains("浮盈"));
        assertTrue(tp.reason().contains("60"));

        HoldingView lose = holding("月港控股", 10, 65.0, 10.0);
        assertEquals(-35.0, lose.profitPct(), 1e-9);
        DecisionEngine.Advice sl = DecisionEngine.advise(c, lose, near, DecisionPreset.DEFAULT);
        assertEquals(DecisionEngine.Side.SELL, sl.side());
        assertTrue(sl.reason().contains("浮亏"));
    }

    @Test
    @DisplayName("★ 无地板 / 破产 / 均价未知 → 观望；同时满足买卖 → 卖")
    void unknownsAndSellWins() {
        CompanyView live = company("月港控股", 56, 3);
        CompanyView dead = new CompanyView("赤石科技", 1, 1, 0, "0", Double.NaN, "",
                Double.NaN, Double.NaN, CompanyView.STATUS_BANKRUPT, 2, 0, CompanyView.Source.API);
        FloorView near = new FloorView(55, "月港控股", 42.0, 42.38, 0.90, 42.29,
                true, 0.12, "near");

        assertEquals(DecisionEngine.Side.HOLD,
                DecisionEngine.advise(live, null, null, DecisionPreset.DEFAULT).side());
        assertEquals("地板未知",
                DecisionEngine.advise(live, null, null, DecisionPreset.DEFAULT).reason());
        assertEquals(DecisionEngine.Side.HOLD,
                DecisionEngine.advise(dead, null, near, DecisionPreset.DEFAULT).side());

        HoldingView avgUnknown = new HoldingView("月港控股", 56, "", 10, 423.8, Double.NaN);
        DecisionEngine.Advice noAvg = DecisionEngine.advise(live, avgUnknown, near, DecisionPreset.DEFAULT);
        assertEquals(DecisionEngine.Side.BUY, noAvg.side(), "均价未知不能卖，仍可建议加仓");

        HoldingView win = holding("月港控股", 10, 160.0, 10.0);
        DecisionEngine.Advice both = DecisionEngine.advise(live, win, near, DecisionPreset.DEFAULT);
        assertEquals(DecisionEngine.Side.SELL, both.side(), "同时满足买和卖 → 卖");

        CompanyView noId = new CompanyView("月港控股", CompanyView.ID_UNKNOWN, 55, 42.38, "42.38",
                Double.NaN, "", 0, 0, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
        assertEquals(DecisionEngine.Side.HOLD,
                DecisionEngine.advise(noId, null, near, DecisionPreset.DEFAULT).side());

        CompanyView noRisk = company("月港控股", 56, 0);
        assertEquals(DecisionEngine.Side.HOLD,
                DecisionEngine.advise(noRisk, null, near, DecisionPreset.DEFAULT).side());
        assertEquals("风险未知",
                DecisionEngine.advise(noRisk, null, near, DecisionPreset.DEFAULT).reason());
    }

    @Test
    @DisplayName("详情行文案：买带数量，观望不带数量")
    void detailLineCopy() {
        DecisionEngine.Advice buy = DecisionEngine.Advice.buy(10, "距地板 2.93% ≤ 3%；风险 1 ≤ 3");
        assertEquals("建议: 买 10　距地板 2.93% ≤ 3%；风险 1 ≤ 3", buy.detailLine());
        assertEquals("买 ", buy.prefix());
        DecisionEngine.Advice hold = DecisionEngine.Advice.hold("地板未知");
        assertEquals("建议: 观望　地板未知", hold.detailLine());
        assertEquals("", hold.prefix());
    }

    private static FloorSnapshot loadSample() throws Exception {
        String body = new String(
                Objects.requireNonNull(DecisionEngineTest.class.getResourceAsStream("/floors-sample.json"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        return FloorSnapshot.parse(body);
    }

    private static CompanyView company(String name, int marketId, int risk) {
        return new CompanyView(name, marketId, marketId, 42.38, "42.38", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, risk, 100, CompanyView.Source.API);
    }

    private static HoldingView holding(String name, long shares, double value, double avg) {
        return new HoldingView(name, 56, "", shares, value, avg);
    }
}
