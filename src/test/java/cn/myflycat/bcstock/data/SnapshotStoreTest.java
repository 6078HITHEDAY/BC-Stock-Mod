package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D0 统一快照的离线测试。样本里的名字/价格/编号都是 {@code 2026-09-26} 真服实测到的值
 * （见 {@code docs/commands.md} 与 {@code data/cmd-probe/}）。
 *
 * <p>重点守住的不是"能不能算"，而是<b>"不知道"不能被当成"是 0"</b>——
 * 这一层搞错，界面会显示错的数、下单会下错标的。
 */
class SnapshotStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-26T07:19:00Z");
    private static final Instant T1 = Instant.parse("2026-09-26T07:25:00Z");
    private static final Instant T2 = Instant.parse("2026-09-26T07:30:00Z");

    // ------------------------------------------------------------------
    // 样本工厂（照实测回执的样子搭）
    // ------------------------------------------------------------------

    /** 照 API / 命令 给的完整行。 */
    private static CompanyView full(String name, int marketId, int apiId, double price,
                                    double marketCap, double changePct, String status, long shares) {
        return new CompanyView(name, marketId, apiId, price, price + "", marketCap, marketCap + "",
                changePct, changePct, status, 3, shares, CompanyView.Source.API);
    }

    /** 照 {@code /invest companies} 那一屏：有名字/价格/状态，<b>没有编号</b>。 */
    private static CompanyView fromCommand(String name, double price, String status) {
        return new CompanyView(name, CompanyView.ID_UNKNOWN, CompanyView.ID_UNKNOWN,
                price, price + "", 0.0, "", Double.NaN, Double.NaN, status, 0,
                CompanyView.SHARES_UNKNOWN, CompanyView.Source.COMMAND);
    }

    // ==================================================================
    // CompanyView
    // ==================================================================

    @Nested
    @DisplayName("CompanyView 的判据")
    class CompanyViewRules {

        @Test
        @DisplayName("★ 编号未知时不许下单——这是 L2 一键买卖的放行判据")
        void tradableFalseWhenIdUnknown() {
            CompanyView c = fromCommand("月港控股", 42.38, CompanyView.STATUS_TRADING);
            assertFalse(c.marketIdKnown());
            assertFalse(c.tradable(), "拿不到 market_id 就绝不能拼出下单命令");
        }

        @Test
        @DisplayName("编号有了但公司破产，同样不许下单")
        void tradableFalseWhenBankrupt() {
            CompanyView c = full("红苹果短剧", 58, 47, 0.01, 1000, -99.99,
                    CompanyView.STATUS_BANKRUPT, 0);
            assertTrue(c.marketIdKnown());
            assertTrue(c.bankrupt());
            assertFalse(c.tradable());
        }

        @Test
        @DisplayName("编号有效且没破产才放行")
        void tradableTrueWhenHealthy() {
            CompanyView c = full("月港控股", 56, 55, 42.38, 4_720_000, 1.5,
                    CompanyView.STATUS_TRADING, 149_889);
            assertTrue(c.tradable());
        }

        @Test
        @DisplayName("overlay：命令那屏不认识的字段，不能把 API 给的编号冲掉")
        void overlayKeepsFieldsTheNewcomerDoesNotKnow() {
            CompanyView api = full("月港控股", 56, 55, 42.38, 4_720_000, 1.5,
                    CompanyView.STATUS_TRADING, 149_889);
            CompanyView cmd = fromCommand("月港控股", 43.10, CompanyView.STATUS_TRADING);

            CompanyView merged = api.overlay(cmd);

            assertEquals(43.10, merged.price(), 1e-9, "价格取新的（离成交更近）");
            assertEquals(56, merged.marketId(), "编号得留着 API 给的那个");
            assertEquals(55, merged.apiId());
            assertEquals(149_889, merged.availableShares(), "可用股数也是 API 独有的");
            assertTrue(merged.tradable(), "叠完还得能下单");
        }

        @Test
        @DisplayName("overlay：新的有效值覆盖旧的")
        void overlayOverwritesWithValidNewValues() {
            CompanyView old = full("月港控股", 56, 55, 42.38, 4_720_000, 1.5,
                    CompanyView.STATUS_TRADING, 100);
            CompanyView fresh = full("月港控股", 56, 55, 50.00, 5_000_000, 2.5,
                    CompanyView.STATUS_TRADING, 120);

            CompanyView merged = old.overlay(fresh);

            assertEquals(50.00, merged.price(), 1e-9);
            assertEquals(5_000_000, merged.marketCap(), 1e-9);
            assertEquals(2.5, merged.changePct(), 1e-9);
            assertEquals(120, merged.availableShares());
        }

        @Test
        @DisplayName("overlay：changePct 取 0 是合法值（↑0.00%），不能被当成\"未知\"而漏掉")
        void overlayTreatsZeroChangeAsKnown() {
            CompanyView old = full("联邦健保", 3, 2, 29.53, 4_720_000, 7.7,
                    CompanyView.STATUS_TRADING, 183_728);
            CompanyView flat = full("联邦健保", 3, 2, 29.53, 4_720_000, 0.0,
                    CompanyView.STATUS_TRADING, 183_728);

            assertEquals(0.0, old.overlay(flat).changePct(), 1e-9,
                    "0.00% 是真值，必须盖掉 7.7");
        }

        @Test
        @DisplayName("overlay：changePct 为 NaN 才是\"未知\"，要保留旧值")
        void overlayTreatsNaNChangeAsUnknown() {
            CompanyView old = full("联邦健保", 3, 2, 29.53, 4_720_000, 7.7,
                    CompanyView.STATUS_TRADING, 183_728);
            CompanyView blind = fromCommand("联邦健保", 29.53, CompanyView.STATUS_TRADING);

            assertEquals(7.7, old.overlay(blind).changePct(), 1e-9);
        }

        @Test
        @DisplayName("公司名不能是空的")
        void nameIsRequired() {
            assertThrows(IllegalArgumentException.class, () -> fromCommand("  ", 1.0, ""));
        }
    }

    // ==================================================================
    // HoldingView
    // ==================================================================

    @Nested
    @DisplayName("HoldingView 的盈亏")
    class HoldingRules {

        @Test
        @DisplayName("实测样本：111 股 / 均价 36.26 / 现值 4703.98 → 浮盈 679.12（+16.87%）")
        void profitOnRealHolding() {
            HoldingView h = new HoldingView("月港控股", CompanyView.ID_UNKNOWN,
                    CompanyView.STATUS_TRADING, 111, 4703.98, 36.26);

            assertEquals(4024.86, h.cost(), 1e-6);
            assertEquals(679.12, h.profit(), 1e-6);
            assertEquals(16.873, h.profitPct(), 1e-3);
            assertTrue(h.held());
        }

        @Test
        @DisplayName("★ 持股减到 0 的墓碑行：held 为 false，盈亏是 0 而不是 NaN")
        void tombstoneRowIsNotHeldAndYieldsZero() {
            // 实测：卖光「联邦投资集团」1 股后，这一行还在，字段仍是 0 / 0.00 / 264.11
            HoldingView h = new HoldingView("联邦投资集团", CompanyView.ID_UNKNOWN,
                    CompanyView.STATUS_TRADING, 0, 0.00, 264.11);

            assertFalse(h.held(), "行还在不等于还持有，判据是持股数");
            assertEquals(0.0, h.profit(), 1e-9);
            assertFalse(Double.isNaN(h.profit()));
            assertEquals(0.0, h.profitPct(), 1e-9);
            assertFalse(Double.isNaN(h.profitPct()));
        }

        @Test
        @DisplayName("亏损时是负数")
        void lossIsNegative() {
            // 10 股 / 均价 100 → 成本 1000；现值 90 → 亏 910，即 -91%
            HoldingView h = new HoldingView("某公司", 1, CompanyView.STATUS_TRADING,
                    10, 90.0, 100.0);
            assertEquals(-910.0, h.profit(), 1e-9);
            assertEquals(-91.0, h.profitPct(), 1e-9);
        }
    }

    // ==================================================================
    // StockSnapshot
    // ==================================================================

    @Nested
    @DisplayName("StockSnapshot 的更新与查询")
    class SnapshotRules {

        @Test
        @DisplayName("空快照：每个字段都是\"未知\"，不是 0")
        void emptySnapshotSaysUnknown() {
            StockSnapshot s = StockSnapshot.empty();

            assertTrue(s.companies().isEmpty());
            assertFalse(s.holdingsKnown(), "没问过持仓 ≠ 没有持仓");
            assertNull(s.holdingOf("月港控股"));
            assertFalse(s.wallet().known());
            assertEquals(0.0, s.wallet().orZero(), 1e-9);
        }

        @Test
        @DisplayName("holdingOf 只认名字（GUI 与命令里都没有可靠编号）")
        void holdingLookupIsByName() {
            StockSnapshot s = StockSnapshot.empty().withHoldings(
                    List.of(new HoldingView("月港控股", CompanyView.ID_UNKNOWN,
                            CompanyView.STATUS_TRADING, 111, 4703.98, 36.26)), T1);

            assertNotNull(s.holdingOf("月港控股"));
            assertEquals(111, s.holdingOf("月港控股").shares());
            assertNull(s.holdingOf("不存在的公司"));
            assertNull(s.holdingOf(null));
        }

        @Test
        @DisplayName("★ 命令只回一屏 16 家，不能把 API 给的另外 38 家删掉")
        void withCompaniesIsAdditiveNotReplacing() {
            StockSnapshot s = StockSnapshot.empty()
                    .withCompanies(List.of(
                            full("月港控股", 56, 55, 42.38, 4_720_000, 1.5, CompanyView.STATUS_TRADING, 149_889),
                            full("联邦健保", 3, 2, 29.53, 4_720_000, 7.7, CompanyView.STATUS_TRADING, 183_728),
                            full("红苹果短剧", 58, 47, 0.01, 1_000, 0.0, CompanyView.STATUS_BANKRUPT, 0)),
                            true, T1);

            StockSnapshot afterCommands = s.withCompanies(
                    List.of(fromCommand("月港控股", 43.10, CompanyView.STATUS_TRADING)), true, T2);

            assertEquals(3, afterCommands.companies().size(), "一屏 1 家叠进来，还是 3 家");
            assertEquals(43.10, afterCommands.companyOf("月港控股").price(), 1e-9);
            assertEquals(56, afterCommands.companyOf("月港控股").marketId(), "编号不能被冲掉");
            assertNotNull(afterCommands.companyOf("联邦健保"), "没出现在这次列表里，但要留着");
        }

        @Test
        @DisplayName("★ withHoldings(null) = 这次没问持仓，保留旧值；withHoldings([]) = 确认空仓，清空")
        void nullHoldingsKeepsOldButEmptyListClears() {
            StockSnapshot held = StockSnapshot.empty().withHoldings(
                    List.of(new HoldingView("月港控股", 56, CompanyView.STATUS_TRADING, 111, 4703.98, 36.26)), T1);

            StockSnapshot untouched = held.withCompanies(List.of(), true, T2);
            assertEquals(1, untouched.holdingsOrEmpty().size(), "只更新了行情，持仓必须原样在");

            StockSnapshot cleared = held.withHoldings(List.of(), T2);
            assertTrue(cleared.holdingsKnown());
            assertTrue(cleared.holdingsOrEmpty().isEmpty(), "明确说了没有持仓，就该清空");
        }

        @Test
        @DisplayName("钱包 UNKNOWN 不会把已知余额冲掉")
        void unknownWalletDoesNotWipeKnownBalance() {
            StockSnapshot s = StockSnapshot.empty().withWallet(new WalletView(45108.13), T1)
                    .withWallet(WalletView.UNKNOWN, T2);

            assertTrue(s.wallet().known());
            assertEquals(45108.13, s.wallet().balance(), 1e-9);
        }

        @Test
        @DisplayName("at 永远取较晚的那个")
        void atTakesTheLaterInstant() {
            StockSnapshot s = StockSnapshot.empty().withCompanies(List.of(), true, T2)
                    .withHoldings(List.of(), T1);
            assertEquals(T2, s.at());
            assertEquals(T2, StockSnapshot.empty().withWallet(new WalletView(1), T0)
                    .withWallet(new WalletView(2), T2).at());
        }

        @Test
        @DisplayName("API 探测失败要显式记一笔")
        void apiDownIsExplicit() {
            StockSnapshot s = StockSnapshot.apiDown(T2);
            assertFalse(s.apiHealthy());
            assertFalse(s.holdingsKnown(), "别顺手把持仓也清了");
        }

        @Test
        @DisplayName("heldOnly 会踢掉持股 0 的墓碑行")
        void heldOnlyFiltersTombstones() {
            StockSnapshot s = StockSnapshot.empty().withHoldings(List.of(
                    new HoldingView("月港控股", 56, CompanyView.STATUS_TRADING, 111, 4703.98, 36.26),
                    new HoldingView("联邦投资集团", 8, CompanyView.STATUS_TRADING, 0, 0.0, 264.11)), T1);

            assertEquals(2, s.holdingsOrEmpty().size(), "墓碑行还在数据里");
            assertEquals(1, s.heldOnly().size(), "但不算持有");
        }
    }

    // ==================================================================
    // ★ 运行时读编号
    // ==================================================================

    @Nested
    @DisplayName("★ withIdsFrom：编号只能当次从 API 读，读不到就抹成未知")
    class RuntimeIdLookup {

        @Test
        @DisplayName("把当次 API 的表补进公司和持仓")
        void stampsIdsFromApiTable() {
            StockSnapshot s = StockSnapshot.empty()
                    .withCompanies(List.of(fromCommand("月港控股", 42.38, CompanyView.STATUS_TRADING)), true, T1)
                    .withHoldings(List.of(new HoldingView("月港控股", CompanyView.ID_UNKNOWN,
                            CompanyView.STATUS_TRADING, 111, 4703.98, 36.26)), T1);

            assertFalse(s.companyOf("月港控股").tradable(), "补之前不能下单");

            StockSnapshot stamped = s.withIdsFrom(Map.of("月港控股", 56));

            assertEquals(56, stamped.companyOf("月港控股").marketId());
            assertEquals(56, stamped.holdingOf("月港控股").marketId());
            assertTrue(stamped.companyOf("月港控股").tradable());
        }

        @Test
        @DisplayName("★ 表里没有的公司，上一次读到的旧编号必须被抹掉——宁可不能下单，不能用过期号")
        void staleIdsAreErasedNotKept() {
            StockSnapshot s = StockSnapshot.empty().withCompanies(
                    List.of(full("老公司", 41, 40, 10.0, 1000, 0.0, CompanyView.STATUS_TRADING, 100)), true, T1);
            assertTrue(s.companyOf("老公司").tradable());

            // 这次 API 只报了别的公司，没报「老公司」（可能被下架/换号了）
            StockSnapshot after = s.withIdsFrom(Map.of("月港控股", 56));

            assertEquals(CompanyView.ID_UNKNOWN, after.companyOf("老公司").marketId(),
                    "旧号必须抹掉");
            assertFalse(after.companyOf("老公司").tradable());
        }

        @Test
        @DisplayName("表是 null（API 挂了）时全部抹成未知")
        void nullTableErasesEverything() {
            StockSnapshot s = StockSnapshot.empty().withCompanies(
                    List.of(full("月港控股", 56, 55, 42.38, 4_720_000, 1.5,
                            CompanyView.STATUS_TRADING, 149_889)), true, T1);

            StockSnapshot after = s.withIdsFrom(null);

            assertEquals(CompanyView.ID_UNKNOWN, after.companyOf("月港控股").marketId());
            assertFalse(after.companyOf("月港控股").tradable());
            assertEquals(42.38, after.companyOf("月港控股").price(), 1e-9, "只抹编号，别的数据留着");
        }

        @Test
        @DisplayName("没变的时候原对象返回，别白造垃圾")
        void keepsSameInstanceWhenNothingChanges() {
            CompanyView c = full("月港控股", 56, 55, 42.38, 4_720_000, 1.5,
                    CompanyView.STATUS_TRADING, 149_889);
            StockSnapshot s = StockSnapshot.empty().withCompanies(List.of(c), true, T1);

            StockSnapshot after = s.withIdsFrom(Map.of("月港控股", 56));

            assertSame(c, after.companyOf("月港控股"));
        }
    }

    // ==================================================================
    // SnapshotStore
    // ==================================================================

    @Nested
    @DisplayName("SnapshotStore")
    class StoreRules {

        @Test
        @DisplayName("新 store 的 get() 不是 null，是空快照")
        void freshStoreIsEmptyNotNull() {
            SnapshotStore store = new SnapshotStore();
            assertNotNull(store.get());
            assertTrue(store.get().companies().isEmpty());
            assertFalse(store.get().wallet().known());
        }

        @Test
        @DisplayName("三个 update 各灌各的，get() 拿到的是一份合起来的")
        void partialUpdatesAccumulate() {
            SnapshotStore store = new SnapshotStore();

            store.updateCompanies(List.of(
                    full("月港控股", 56, 55, 42.38, 4_720_000, 1.5, CompanyView.STATUS_TRADING, 149_889)),
                    true, T1);
            store.updateHoldings(List.of(new HoldingView("月港控股", CompanyView.ID_UNKNOWN,
                    CompanyView.STATUS_TRADING, 111, 4703.98, 36.26)), T1);
            store.updateWallet(new WalletView(45108.13), T1);

            StockSnapshot s = store.get();
            assertEquals(1, s.companies().size());
            assertEquals(111, s.holdingOf("月港控股").shares());
            assertEquals(45108.13, s.wallet().balance(), 1e-9);
            assertTrue(s.apiHealthy());
            assertEquals(T1, s.at());
        }

        @Test
        @DisplayName("updateIds 之后，同一份快照里的编号就能用了")
        void updateIdsMakesCompaniesTradable() {
            SnapshotStore store = new SnapshotStore();
            store.updateCompanies(List.of(fromCommand("月港控股", 42.38, CompanyView.STATUS_TRADING)), true, T1);
            assertFalse(store.get().companyOf("月港控股").tradable());

            store.updateIds(Map.of("月港控股", 56), T1);

            assertEquals(56, store.get().companyOf("月港控股").marketId());
            assertTrue(store.get().companyOf("月港控股").tradable());
        }

        @Test
        @DisplayName("set() 是整份替换，不是合并")
        void setReplacesWholesale() {
            SnapshotStore store = new SnapshotStore();
            store.updateCompanies(List.of(full("老公司", 1, 0, 1.0, 1, 0.0,
                    CompanyView.STATUS_TRADING, 1)), true, T1);

            store.set(StockSnapshot.empty());

            assertTrue(store.get().companies().isEmpty());
        }

        @Test
        @DisplayName("set(null) 退回空快照，不炸")
        void setNullIsSafe() {
            SnapshotStore store = new SnapshotStore();
            store.updateWallet(new WalletView(100), T1);

            store.set(null);

            assertNotNull(store.get());
            assertFalse(store.get().wallet().known());
        }
    }
}
