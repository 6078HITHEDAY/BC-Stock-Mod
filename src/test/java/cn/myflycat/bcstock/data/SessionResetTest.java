package cn.myflycat.bcstock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第二轮审计修复的回归测试：会话复位、last() 不吃增量、账本事务、编号抹除。 */
class SessionResetTest {

  private static CompanyView yuegang(int marketId, double price) {
    return new CompanyView(
        "月港控股",
        marketId,
        55,
        price,
        price + "",
        0,
        "",
        1.5,
        Double.NaN,
        CompanyView.STATUS_TRADING,
        3,
        149_889,
        CompanyView.Source.API);
  }

  @Nested
  @DisplayName("★ 断线抹除上一会话的 market_id")
  class WipeIds {

    @Test
    @DisplayName("wipeIds 后公司行保留（数字不消失）但编号全未知")
    void wipeKeepsRowsButClearsIds() {
      SnapshotStore store = new SnapshotStore();
      store.set(
          StockSnapshot.empty().withCompanies(List.of(yuegang(56, 42.38)), true, Instant.EPOCH),
          false);
      assertTrue(store.get().companies().get(0).marketIdKnown());

      store.wipeIds();

      var c = store.get().companies().get(0);
      assertEquals("月港控股", c.name(), "公司行不许消失");
      assertEquals(42.38, c.price(), 1e-9, "价格不许消失");
      assertFalse(c.marketIdKnown(), "编号必须归未知");
      assertEquals(CompanyView.ID_UNKNOWN, c.marketId());
    }

    @Test
    @DisplayName("编号本来就未知时 wipe 是空操作")
    void wipeIsNoopWhenNoIds() {
      SnapshotStore store = new SnapshotStore();
      store.set(
          StockSnapshot.empty()
              .withCompanies(List.of(yuegang(CompanyView.ID_UNKNOWN, 42.38)), true, Instant.EPOCH),
          false);
      store.wipeIds();
      assertFalse(store.get().companies().get(0).marketIdKnown());
    }

    @Test
    @DisplayName("空快照 wipe 不炸")
    void wipeOnEmpty() {
      SnapshotStore store = new SnapshotStore();
      store.wipeIds();
    }
  }

  @Nested
  @DisplayName("★ last() 不再回退到增量行")
  class LastSkipsDelta {

    @Test
    @DisplayName("文件里只有增量行 → last() 返回 empty，不给残缺数据")
    void deltaOnlyFileIsEmpty(@TempDir Path dir) throws Exception {
      Path file = dir.resolve("snapshots.jsonl");
      SnapshotJournal journal = new SnapshotJournal(file.toFile());
      StockSnapshot full =
          StockSnapshot.empty().withCompanies(List.of(yuegang(56, 42.38)), true, Instant.EPOCH);
      // 直接手写一条增量行（append 带 previous 才会产生，这里绕开凑文件内容）
      String delta =
          "{\"at\":\"2026-09-26T08:15:00Z\",\"apiHealthy\":true,"
              + "\"companies\":[{\"name\":\"月港控股\",\"marketId\":56,\"apiId\":55,"
              + "\"price\":44.21,\"priceRaw\":\"44.21\",\"risk\":3,"
              + "\"availableShares\":149889,\"source\":\"API\"}],"
              + "\"trigger\":\"companies\",\"delta\":true}";
      Files.writeString(file, delta + "\n", StandardCharsets.UTF_8);

      assertTrue(journal.last().isEmpty(), "只有增量行时 last() 必须 empty");
      // 写入完整行后又能恢复
      journal.append(full);
      assertEquals(42.38, journal.last().orElseThrow().companyOf("月港控股").price(), 1e-9);
    }
  }

  @Nested
  @DisplayName("★ 账本 replace 是事务")
  class LedgerTransactions {

    @Test
    @DisplayName("replaceHoldings 正常路径：INSERT 成功后 holdings_known 才为 true")
    void holdingsKnownOnlyAfterInsert() {
      try (LedgerStore store = LedgerStore.openMemory("tx_h")) {
        store.writeSnapshot(
            StockSnapshot.empty()
                .withHoldings(
                    List.of(new HoldingView("月港控股", 56, "", 111, 4703.98, Double.NaN)),
                    Instant.EPOCH),
            1L);
        assertTrue(store.loadSnapshotWipingMarketId().holdingsKnown());
        assertTrue(store.loadSnapshotWipingMarketId().heldOnly().size() == 1);
      }
    }

    @Test
    @DisplayName("空持仓覆盖：清空旧持仓并确认空仓")
    void emptyHoldingsReplace() {
      try (LedgerStore store = LedgerStore.openMemory("tx_e")) {
        store.writeSnapshot(
            StockSnapshot.empty()
                .withHoldings(
                    List.of(new HoldingView("月港控股", 56, "", 111, 4703.98, Double.NaN)),
                    Instant.EPOCH),
            1L);
        store.writeSnapshot(
            StockSnapshot.empty().withHoldings(List.of(), Instant.EPOCH.plusSeconds(60)), 2L);
        var snap = store.loadSnapshotWipingMarketId();
        assertTrue(snap.holdingsKnown());
        assertTrue(snap.holdingsOrEmpty().isEmpty(), "旧持仓必须被清掉");
      }
    }
  }

  @Nested
  @DisplayName("★ 未确认单收口带脏标记")
  class ReconcileDirty {

    @Test
    @DisplayName("没有未确认单时 reconcileUnconfirmedIfReady 是空操作且清掉脏标记")
    void noReconcileWhenClean() {
      try (LedgerStore store = LedgerStore.openMemory("dirty_off")) {
        store.writeSnapshot(
            StockSnapshot.empty()
                .withHoldings(List.of(), Instant.EPOCH)
                .withWallet(new WalletView(100), Instant.EPOCH),
            1L);
        LedgerRuntime.testOnlySetStore(store);
        try {
          LedgerRuntime.markUnconfirmedDirty();
          LedgerRuntime.reconcileUnconfirmedIfReady(
              StockSnapshot.empty()
                  .withHoldings(List.of(), Instant.EPOCH)
                  .withWallet(new WalletView(100), Instant.EPOCH),
              2L);
          // 没有未确认单：不该抛、orders 表不动，脏标记随后关闭（下一 tick 不再打 SQL）。
          assertTrue(store.listAll().isEmpty());
        } finally {
          LedgerRuntime.testOnlySetStore(null);
          LedgerRuntime.markUnconfirmedDirty();
        }
      }
    }
  }
}
