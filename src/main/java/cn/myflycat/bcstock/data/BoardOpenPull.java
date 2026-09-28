package cn.myflycat.bcstock.data;

/**
 * 打开盘面时拉个人余额 / 持仓。只走 {@link CommandGateway}，不绕过单飞 / 间隔 / 冷却。
 *
 * <p>开盘瞬间只能飞一条：先 {@code bal}，后续 {@link #tickWhileOpen} 在单飞结束且间隔满后
 * 再发 {@code invest portfolio}。15 分钟内重复开盘会被采集冷却挡住。
 */
public final class BoardOpenPull {

    private BoardOpenPull() {
    }

    /** 开盘时调一次。顺序：余额在前。 */
    public static void onOpen(CommandGateway gateway) {
        pull(gateway);
    }

    /**
     * 盘面仍开着时每个 tick 调。单飞挡着时第二条会失败；冷却期内两条都拒——这是预期。
     */
    public static void tickWhileOpen(CommandGateway gateway) {
        pull(gateway);
    }

    private static void pull(CommandGateway gateway) {
        if (gateway == null) {
            return;
        }
        if (gateway.userTradeBusy()) {
            return;
        }
        gateway.trySendBalance();
        gateway.trySendPortfolio();
    }

    /**
     * 把网关最近一次成功回执灌进快照。只有值变了才写，避免 JSONL 被每 tick 刷爆。
     *
     * @param holdingsTrigger 非空时写入 journal（B1 采集用 {@code "collect"}）
     */
    public static void drainToStore(CommandGateway gateway, SnapshotStore store, java.time.Instant at) {
        drainToStore(gateway, store, at, null);
    }

    public static void drainToStore(CommandGateway gateway, SnapshotStore store, java.time.Instant at,
                                    String holdingsTrigger) {
        if (gateway == null || store == null) {
            return;
        }
        java.time.Instant when = (at == null) ? java.time.Instant.now() : at;
        gateway.lastBalance().ifPresent(wallet -> {
            if (!wallet.equals(store.get().wallet())) {
                store.updateWallet(wallet, when);
            }
        });
        gateway.lastHoldings().ifPresent(holdings -> {
            StockSnapshot cur = store.get();
            boolean changed = !cur.holdingsKnown() || !cur.holdingsOrEmpty().equals(holdings);
            boolean forceCollect = "collect".equals(holdingsTrigger);
            // collect 触发时即使持仓未变也写一行，避免「标记留下、下次开盘被污染」
            if (changed || forceCollect) {
                store.updateHoldings(holdings, when, holdingsTrigger);
                if (forceCollect) {
                    CollectScheduler.SHARED.clearCollectTag();
                }
            }
        });
        LedgerRuntime.reconcileUnconfirmedIfReady(store.get(), when.toEpochMilli());
    }
}
