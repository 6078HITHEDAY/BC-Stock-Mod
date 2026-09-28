package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.data.WalletView;
import cn.myflycat.bcstock.ui.TradeDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTradePlannerTest {

    @Test
    @DisplayName("advice 不交意图")
    void adviceYieldsNothing() {
        var intent = AutoTradePlanner.plan(buyableSnap(), Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits(AutoTradeSettings.Mode.ADVICE, 10, 100, 0, 0, List.of()),
                1_000L);
        assertTrue(intent.isEmpty());
    }

    @Test
    @DisplayName("limited：数量夹到单笔上限和当日剩余")
    void limitedClampsQty() {
        AutoTradePlanner.Limits limits = limits(AutoTradeSettings.Mode.LIMITED, 3, 5, 2, 0, List.of());
        var intent = AutoTradePlanner.plan(buyableSnap(), Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits, 1_000L);
        assertTrue(intent.isPresent());
        assertEquals(TradeDraft.Side.BUY, intent.get().side());
        assertEquals(3, intent.get().qty(), "min(建议10, 单笔3, 当日剩余3)");
    }

    @Test
    @DisplayName("白名单拦掉未列出的公司")
    void whitelistFilters() {
        AutoTradePlanner.Limits limits = limits(AutoTradeSettings.Mode.LIMITED, 10, 100, 0, 0,
                List.of("不存在"));
        var intent = AutoTradePlanner.plan(buyableSnap(), Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits, 1_000L);
        assertTrue(intent.isEmpty());

        AutoTradePlanner.Limits byId = limits(AutoTradeSettings.Mode.LIMITED, 10, 100, 0, 0,
                List.of("56"));
        assertTrue(AutoTradePlanner.plan(buyableSnap(), Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, byId, 1_000L).isPresent());
    }

    @Test
    @DisplayName("现金底线：买完后钱包不得低于底线")
    void cashFloorCapsBuy() {
        // 价 42.38，钱包 100，底线 90 → 最多买 floor(10/42.38)=0
        StockSnapshot snap = StockSnapshot.empty()
                .withCompanies(List.of(company()), true, Instant.EPOCH)
                .withWallet(new WalletView(100.0), Instant.EPOCH);
        AutoTradePlanner.Limits limits = new AutoTradePlanner.Limits(
                AutoTradeSettings.Mode.LIMITED, true, true, 10, 100, 0, 0, 0, 90.0, List.of());
        assertTrue(AutoTradePlanner.plan(snap, Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits, 1_000L).isEmpty());

        AutoTradePlanner.Limits room = new AutoTradePlanner.Limits(
                AutoTradeSettings.Mode.LIMITED, true, true, 10, 100, 0, 0, 0, 10.0, List.of());
        var intent = AutoTradePlanner.plan(snap, Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, room, 1_000L);
        assertTrue(intent.isPresent());
        assertEquals(2, intent.get().qty(), "floor((100-10)/42.38)=2");
    }

    @Test
    @DisplayName("full 不夹单笔/单日上限")
    void fullIgnoresCaps() {
        AutoTradePlanner.Limits limits = limits(AutoTradeSettings.Mode.FULL, 1, 1, 100, 0, List.of());
        var intent = AutoTradePlanner.plan(buyableSnap(), Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits, 1_000L);
        assertTrue(intent.isPresent());
        assertEquals(10, intent.get().qty(), "DEFAULT 建议 10，full 不夹");
    }

    @Test
    @DisplayName("冷却未满不交意图")
    void cooldownBlocks() {
        AutoTradePlanner.Limits limits = new AutoTradePlanner.Limits(
                AutoTradeSettings.Mode.LIMITED, true, true, 10, 100, 0,
                60_000L, 1_000L, 0.0, List.of());
        assertTrue(AutoTradePlanner.plan(buyableSnap(), Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits, 30_000L).isEmpty());
    }

    @Test
    @DisplayName("先卖后买")
    void sellBeforeBuy() {
        CompanyView c = company();
        HoldingView win = new HoldingView("月港控股", 56, "", 8, 160.0, 10.0);
        StockSnapshot snap = StockSnapshot.empty()
                .withCompanies(List.of(c), true, Instant.EPOCH)
                .withHoldings(List.of(win), Instant.EPOCH)
                .withWallet(new WalletView(10_000.0), Instant.EPOCH);
        var intent = AutoTradePlanner.plan(snap, Map.of("月港控股", nearFloor()),
                DecisionPreset.DEFAULT, limits(AutoTradeSettings.Mode.LIMITED, 10, 100, 0, 0, List.of()),
                1_000L);
        assertTrue(intent.isPresent());
        assertEquals(TradeDraft.Side.SELL, intent.get().side());
        assertEquals(8, intent.get().qty());
    }

    private static AutoTradePlanner.Limits limits(AutoTradeSettings.Mode mode, int maxTrade, int maxDay,
                                                  int filled, long lastAuto, List<String> whitelist) {
        return new AutoTradePlanner.Limits(mode, true, true, maxTrade, maxDay, filled,
                0L, lastAuto, 0.0, whitelist);
    }

    private static StockSnapshot buyableSnap() {
        return StockSnapshot.empty()
                .withCompanies(List.of(company()), true, Instant.EPOCH)
                .withWallet(new WalletView(10_000.0), Instant.EPOCH);
    }

    private static CompanyView company() {
        return new CompanyView("月港控股", 56, 56, 42.38, "42.38", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
    }

    private static FloorView nearFloor() {
        return new FloorView(55, "月港控股", 42.0, 42.38, 0.90, 42.29, true, 0.12, "near");
    }
}
