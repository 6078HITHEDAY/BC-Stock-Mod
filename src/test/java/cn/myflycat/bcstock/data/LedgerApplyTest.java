package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.TradeDraft;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerApplyTest {

    @Test
    @DisplayName("买入乐观入账：持股 +qty、现金按实时价减")
    void buyAddsSharesAndDebitsWallet() {
        CompanyView c = yue();
        WalletView wallet = new WalletView(10_000);
        TradeDraft d = TradeDraft.create(c, TradeDraft.Side.BUY, 10, wallet, null, 48.62).orElseThrow();
        StockSnapshot snap = StockSnapshot.empty()
                .withCompanies(List.of(c), true, Instant.EPOCH)
                .withWallet(wallet, Instant.EPOCH);
        StockSnapshot next = LedgerApply.optimistic(snap, d, Instant.EPOCH);
        HoldingView h = next.holdingOf("月港控股");
        assertTrue(h != null && h.held());
        assertEquals(10, h.shares());
        assertEquals(d.walletAfter(), next.wallet().balance(), 1e-9);
        assertEquals("买入 月港控股 10 股 @ 48.62", LedgerApply.fillMessage(d));
    }

    @Test
    @DisplayName("卖出到 0：墓碑行持股 0，现金加回")
    void sellToZeroKeepsTombstone() {
        CompanyView c = yue();
        HoldingView held = new HoldingView("月港控股", 56, "", 10, 486.20, 48.62);
        WalletView wallet = new WalletView(1_000);
        TradeDraft d = TradeDraft.create(c, TradeDraft.Side.SELL, 10, wallet, held, 48.62).orElseThrow();
        StockSnapshot snap = StockSnapshot.empty()
                .withCompanies(List.of(c), true, Instant.EPOCH)
                .withHoldings(List.of(held), Instant.EPOCH)
                .withWallet(wallet, Instant.EPOCH);
        StockSnapshot next = LedgerApply.optimistic(snap, d, Instant.EPOCH);
        HoldingView h = next.holdingOf("月港控股");
        assertTrue(h != null);
        assertEquals(0, h.shares());
        assertEquals(d.walletAfter(), next.wallet().balance(), 1e-9);
    }

    @Test
    @DisplayName("applyToStore 写入后对账 drain 能覆盖乐观数")
    void storeThenReconcileOverwrites() {
        SnapshotStore store = new SnapshotStore();
        CompanyView c = yue();
        WalletView wallet = new WalletView(10_000);
        store.updateCompanies(List.of(c), true, Instant.EPOCH);
        store.updateWallet(wallet, Instant.EPOCH);
        TradeDraft d = TradeDraft.create(c, TradeDraft.Side.BUY, 10, wallet, null, 48.62).orElseThrow();
        LedgerApply.applyToStore(store, d, Instant.EPOCH);
        assertEquals(10, store.get().holdingOf("月港控股").shares());

        HoldingView server = new HoldingView("月港控股", 56, "", 10, 500.00, 50.00);
        store.updateHoldings(List.of(server), Instant.EPOCH);
        store.updateWallet(new WalletView(9_500), Instant.EPOCH);
        assertEquals(10, store.get().holdingOf("月港控股").shares());
        assertEquals(50.00, store.get().holdingOf("月港控股").averageBuyPrice(), 1e-9);
        assertEquals(9_500, store.get().wallet().balance(), 1e-9);
    }

    private static CompanyView yue() {
        return new CompanyView("月港控股", 56, 55, 48.62, "48.62", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
    }
}
