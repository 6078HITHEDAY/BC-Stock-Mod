package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.TradeDraft;
import java.io.File;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerStoreTest {

    @AfterEach
    void tearDown() {
        LedgerRuntime.onDisconnect();
        SnapshotStore.SHARED.set(StockSnapshot.empty(), false);
        SnapshotStore.SHARED.setJournal(null);
        SnapshotStore.SHARED.setLedger(null);
    }

    @Test
    @DisplayName("建库 schema_version=1，空库 isFresh")
    void schemaAndFresh() {
        try (LedgerStore store = LedgerStore.openMemory("schema_" + System.nanoTime())) {
            assertEquals(LedgerStore.SCHEMA_VERSION, store.schemaVersion());
            assertTrue(store.isFresh());
            store.setPlayerUuid("abc");
            assertEquals("abc", store.playerUuid());
            assertTrue(store.firstPluginSeenAt().isEmpty());
        }
    }

    @Test
    @DisplayName("空库导入 JSONL + 行情缓存；market_id 读出时抹掉")
    void importLegacyOnce(@TempDir File dir) {
        File bc = new File(dir, SnapshotJournal.RELATIVE_DIR);
        assertTrue(bc.mkdirs());
        CompanyView live = yuegang(42.38);
        StockSnapshot snap = StockSnapshot.empty()
                .withCompanies(List.of(live), true, Instant.parse("2026-09-26T08:00:00Z"))
                .withHoldings(List.of(new HoldingView("月港控股", 56, "", 3, 127.14, 42.38)),
                        Instant.parse("2026-09-26T08:00:00Z"))
                .withWallet(new WalletView(1000.0), Instant.parse("2026-09-26T08:00:00Z"));
        new SnapshotJournal(new File(bc, SnapshotJournal.FILE_NAME)).append(snap);
        MarketCache.write(new File(bc, MarketCache.FILE_NAME), List.of(live), 1_000L);

        LedgerRuntime.onJoin(dir, "player-1");
        LedgerStore store = LedgerRuntime.store();
        assertFalse(store.isFresh());
        StockSnapshot loaded = SnapshotStore.SHARED.get();
        assertEquals(1, loaded.companies().size());
        assertFalse(loaded.companies().get(0).marketIdKnown(), "恢复必须抹编号");
        assertTrue(loaded.holdingsKnown());
        assertEquals(3, loaded.holdingOf("月港控股").shares());
        assertEquals(1000.0, loaded.wallet().balance(), 1e-9);
        assertTrue(store.firstPluginSeenAt().isPresent());

        int companies = loaded.companies().size();
        LedgerRuntime.onJoin(dir, "player-1");
        assertEquals(companies, SnapshotStore.SHARED.get().companies().size(), "第二次进服不再当空库导入");
    }

    @Test
    @DisplayName("订单 SENT 重开标成 UNCONFIRMED；当日成交重启仍计入")
    void orderRestart(@TempDir File dir) {
        File storeFile = LedgerStore.filePath(dir, "p2");
        long day = System.currentTimeMillis();
        try (LedgerStore store = LedgerStore.openFile(storeFile)) {
            long pending = store.insertPending(TradeDraft.Side.BUY, 56, "月港控股", 7,
                    OrderRecord.Origin.MANUAL, day);
            store.markSent(pending, day);
            long ok = store.insertPending(TradeDraft.Side.BUY, 56, "月港控股", 4,
                    OrderRecord.Origin.AUTO, day);
            store.markSent(ok, day);
            store.markChatOk(ok, day, 42.38);
            assertEquals(4, store.filledQtyOnDay(day));
            assertEquals(OrderRecord.Status.SENT, store.find(pending).orElseThrow().status());
        }
        try (LedgerStore store = LedgerStore.openFile(storeFile)) {
            assertEquals(4, store.filledQtyOnDay(day), "重开后单日用量还在");
            int n = store.markAllSentUnconfirmed(day + 1);
            assertEquals(1, n);
            OrderRecord unconfirmed = store.listAll().stream()
                    .filter(o -> o.status() == OrderRecord.Status.UNCONFIRMED)
                    .findFirst()
                    .orElseThrow();
            store.reconcileUnconfirmed(day + 2);
            assertEquals(OrderRecord.Status.RECONCILED, store.find(unconfirmed.id()).orElseThrow().status());
        }
    }

    @Test
    @DisplayName("SnapshotStore 写入同步 upsert 公司/持仓/余额")
    void snapshotStoreUpserts() {
        LedgerStore ledger = LedgerStore.openMemory("upsert_" + System.nanoTime());
        SnapshotStore store = new SnapshotStore();
        store.setLedger(ledger);
        Instant at = Instant.parse("2026-09-26T08:00:00Z");
        store.updateCompanies(List.of(yuegang(42.38)), true, at);
        store.updateHoldings(List.of(new HoldingView("月港控股", 56, "", 2, 84.76, 42.38)), at);
        store.updateWallet(new WalletView(500.0), at);
        StockSnapshot loaded = ledger.loadSnapshotWipingMarketId();
        assertEquals(1, loaded.companies().size());
        assertFalse(loaded.companies().get(0).marketIdKnown());
        assertEquals(2, loaded.holdingOf("月港控股").shares());
        assertEquals(500.0, loaded.wallet().balance(), 1e-9);
        ledger.close();
    }

    private static CompanyView yuegang(double price) {
        return new CompanyView("月港控股", 56, 55, price, String.valueOf(price), Double.NaN, "",
                1.5, Double.NaN, CompanyView.STATUS_TRADING, 3, 149_889, CompanyView.Source.API);
    }
}
