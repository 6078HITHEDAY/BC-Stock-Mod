package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.io.File;
import java.util.Map;

/**
 * 进服时打开按 UUID 隔离的账本，断线时关掉。
 * 不引用 {@code net.minecraft}。
 */
public final class LedgerRuntime {

    private static volatile LedgerStore current;
    private static volatile File runDirectory;

    private LedgerRuntime() {
    }

    public static LedgerStore store() {
        return current;
    }

    public static synchronized void onJoin(File runDir, String playerUuid) {
        onDisconnect();
        File dir = (runDir == null) ? new File(".") : runDir;
        runDirectory = dir;
        String uuid = (playerUuid == null || playerUuid.isBlank()) ? "unknown" : playerUuid.trim();
        File path = LedgerStore.filePath(dir, uuid);
        LedgerStore store;
        try {
            store = LedgerStore.openFile(path);
        } catch (RuntimeException e) {
            BcStockLog.warn("进服打开账本失败，本局只留内存：{}", e.toString());
            return;
        }
        store.setPlayerUuid(uuid);
        long now = System.currentTimeMillis();
        int abandoned = store.markAllSentUnconfirmed(now);
        if (abandoned > 0) {
            BcStockLog.info("账本：{} 笔 SENT 已标成未确认（不重发）", abandoned);
        }

        StockSnapshot live = SnapshotStore.SHARED.get();
        boolean liveVerified = hasVerifiedIds(live);
        if (store.isFresh()) {
            importLegacy(dir, store, now);
            BcStockLog.info("账本为空，已尝试导入 JSONL / 行情缓存 → {}", path.getParent());
        }

        StockSnapshot fromDb = store.loadSnapshotWipingMarketId();
        StockSnapshot merged = fromDb;
        if (liveVerified) {
            merged = fromDb.withCompanies(live.companies(), live.apiHealthy(), live.at());
        } else if (merged.companies().isEmpty() && !live.companies().isEmpty()) {
            merged = merged.withCompanies(live.withIdsFrom(Map.of()).companies(),
                    live.apiHealthy(), live.at());
        }
        if (!merged.holdingsKnown() && live.holdingsKnown()) {
            merged = merged.withHoldings(live.holdings(), live.at());
        }
        if (!merged.wallet().known() && live.wallet().known()) {
            merged = merged.withWallet(live.wallet(), live.at());
        }
        SnapshotStore.SHARED.set(merged, false);
        SnapshotStore.SHARED.setLedger(store);
        current = store;
        if (liveVerified) {
            store.writeSnapshot(merged, now);
        }
        if (merged.companies().isEmpty() && !merged.holdingsKnown() && !merged.wallet().known()) {
            BcStockLog.info("账本已打开（空），路径 {}", path.getParent());
        } else {
            BcStockLog.info("账本已打开：公司 {} 家 / 持仓 {} / 余额 {}",
                    merged.companies().size(),
                    merged.holdingsKnown() ? merged.holdingsOrEmpty().size() + " 家" : "未知",
                    merged.wallet().known() ? merged.wallet().balance() : "未知");
        }
    }

    public static synchronized void onDisconnect() {
        SnapshotStore.SHARED.setLedger(null);
        LedgerStore store = current;
        current = null;
        if (store != null) {
            store.close();
        }
    }

    /** 开盘/采集拿到持仓和余额后，把重进留下的 UNCONFIRMED 收口。 */
    public static void reconcileUnconfirmedIfReady(StockSnapshot snap, long nowMs) {
        LedgerStore store = current;
        if (store == null || snap == null) {
            return;
        }
        if (!snap.holdingsKnown() || !snap.wallet().known()) {
            return;
        }
        int n = store.reconcileUnconfirmed(nowMs);
        if (n > 0) {
            BcStockLog.info("账本：初始化对账收口 {} 笔未确认单", n);
        }
    }

    static int importLegacy(File runDir, LedgerStore store, long now) {
        if (runDir == null || store == null) {
            return 0;
        }
        File bcDir = new File(runDir, SnapshotJournal.RELATIVE_DIR);
        File journalFile = new File(bcDir, SnapshotJournal.FILE_NAME);
        File cacheFile = new File(bcDir, MarketCache.FILE_NAME);
        StockSnapshot imported = StockSnapshot.empty();
        var cache = MarketCache.read(cacheFile);
        if (cache.isPresent()) {
            imported = imported.withCompanies(
                    MarketCache.toViewsWipingMarketId(cache.get()),
                    true,
                    java.time.Instant.ofEpochMilli(Math.max(0L, cache.get().savedAtMs())));
        }
        SnapshotJournal journal = new SnapshotJournal(journalFile);
        var last = journal.last();
        if (last.isPresent()) {
            StockSnapshot wiped = last.get().withIdsFrom(Map.of());
            if (imported.companies().isEmpty()) {
                imported = wiped;
            } else {
                imported = imported.withWallet(wiped.wallet(), wiped.at());
                if (wiped.holdingsKnown()) {
                    imported = imported.withHoldings(wiped.holdings(), wiped.at());
                }
            }
        }
        if (imported.companies().isEmpty() && !imported.holdingsKnown() && !imported.wallet().known()) {
            return 0;
        }
        store.writeSnapshot(imported, now);
        return imported.companies().size();
    }

    private static boolean hasVerifiedIds(StockSnapshot snap) {
        if (snap == null) {
            return false;
        }
        for (CompanyView c : snap.companies()) {
            if (c != null && c.marketIdKnown()) {
                return true;
            }
        }
        return false;
    }

    public static File runDirectory() {
        return runDirectory;
    }
}
