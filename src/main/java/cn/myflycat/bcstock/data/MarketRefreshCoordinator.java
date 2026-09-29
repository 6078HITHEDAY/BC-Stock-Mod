package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 行情刷新：进服 API 全量 → 聊天「所有商业股票已更新」后 {@code /invest companies} 增量叠加 →
 * 超过 20 分钟没刷成功才回 API。
 *
 * <p>不 import {@code net.minecraft}。广播回调只记 flag，发命令在 {@link #tick}。
 */
public final class MarketRefreshCoordinator {

    public static final long FALLBACK_MS = 20L * 60L * 1000L;

    public static final MarketRefreshCoordinator SHARED = new MarketRefreshCoordinator();

    private LongSupplier clock = System::currentTimeMillis;
    private BooleanSupplier connected = () -> false;
    private CommandGateway gateway = CommandGateway.SHARED;
    private SnapshotStore store = SnapshotStore.SHARED;
    private MarketPollScheduler poller = MarketPollScheduler.SHARED;
    private CollectScheduler collect = CollectScheduler.SHARED;
    private FloorCache floors = FloorCache.SHARED;
    private File cacheFile;
    private boolean pendingAuto;

    /** 测试注入：同步返回是否成功。非空时 {@link #onJoinPoll} / 兜底走这条，不碰 HTTP 线程。 */
    private BooleanSupplier apiPollForTest;

    private volatile long generation;
    private long lastSuccessMs = Long.MIN_VALUE / 2;
    private long lastApiAttemptMs = Long.MIN_VALUE / 2;
    private boolean joinAttempted;
    private boolean pendingBroadcast;
    private boolean companiesInFlight;
    private boolean apiInFlight;

    public MarketRefreshCoordinator() {
    }

    public MarketRefreshCoordinator(LongSupplier clock, BooleanSupplier connected,
                                    CommandGateway gateway, SnapshotStore store,
                                    BooleanSupplier apiPollForTest, CollectScheduler collect) {
        setClock(clock);
        setConnected(connected);
        setGateway(gateway);
        setStore(store);
        setApiPollForTest(apiPollForTest);
        setCollect(collect);
    }

    public void setClock(LongSupplier clock) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
    }

    public void setConnected(BooleanSupplier connected) {
        this.connected = (connected == null) ? () -> false : connected;
    }

    public void setGateway(CommandGateway gateway) {
        this.gateway = (gateway == null) ? CommandGateway.SHARED : gateway;
    }

    public void setStore(SnapshotStore store) {
        this.store = (store == null) ? SnapshotStore.SHARED : store;
    }

    public void setPoller(MarketPollScheduler poller) {
        this.poller = (poller == null) ? MarketPollScheduler.SHARED : poller;
    }

    public void setCollect(CollectScheduler collect) {
        this.collect = (collect == null) ? CollectScheduler.SHARED : collect;
    }

    public void setFloors(FloorCache floors) {
        this.floors = (floors == null) ? FloorCache.SHARED : floors;
    }

    public void setCacheFile(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    public void setApiPollForTest(BooleanSupplier apiPollForTest) {
        this.apiPollForTest = apiPollForTest;
    }

    public long generation() {
        return generation;
    }

    public boolean pendingBroadcastForTest() {
        return pendingBroadcast;
    }

    public boolean companiesInFlightForTest() {
        return companiesInFlight;
    }

    /** 聊天广播：只记 flag。重复广播在未完成前不排队。 */
    public synchronized void onBroadcast() {
        if (pendingBroadcast || companiesInFlight) {
            return;
        }
        pendingBroadcast = true;
    }

    /**
     * 进服全量。调用方应在后台线程（HTTP 可能数秒）。
     */
    public void onJoinPoll() {
        joinAttempted = true;
        lastApiAttemptMs = clock.getAsLong();
        boolean ok = runApiPoll(true);
        if (ok) {
            markSuccess(false);
        }
    }

    public synchronized void tick() {
        drainCompanies();
        maybeSendCompanies();
        maybeFallback();
        maybeAutoTrade();
    }

    private void drainCompanies() {
        if (!companiesInFlight || gateway.inFlight()) {
            return;
        }
        companiesInFlight = false;
        Optional<List<CompanyView>> got = gateway.takeLastCompanies();
        if (got.isPresent() && !got.get().isEmpty()) {
            overlayCompanies(got.get());
            markSuccess(true);
        } else {
            BcStockLog.info("公司列表增量失败，立刻 API 兜底");
            requestApi("companies-fail");
        }
    }

    private void maybeSendCompanies() {
        if (!pendingBroadcast || !connected.getAsBoolean()) {
            return;
        }
        if (gateway.userTradeBusy()) {
            return;
        }
        if (gateway.inFlight()) {
            return;
        }
        if (gateway.trySendCompanies()) {
            pendingBroadcast = false;
            companiesInFlight = true;
            BcStockLog.info("行情：聊天已更新，已发 invest companies");
        }
        // 单飞/间隔：下个 tick 再试。sender 失败也留着 pending。
    }

    private void maybeFallback() {
        if (!joinAttempted || !connected.getAsBoolean()) {
            return;
        }
        if (companiesInFlight) {
            return;
        }
        long now = clock.getAsLong();
        long anchor = (lastSuccessMs > Long.MIN_VALUE / 4) ? lastSuccessMs : lastApiAttemptMs;
        if (now - anchor <= FALLBACK_MS) {
            return;
        }
        requestApi("idle-20min");
    }

    private void requestApi(String why) {
        if (apiInFlight) {
            return;
        }
        lastApiAttemptMs = clock.getAsLong();
        BcStockLog.info("行情：API 兜底（{}）", why);
        if (apiPollForTest != null) {
            boolean ok = runApiPoll(false);
            if (ok) {
                markSuccess(true);
            }
            return;
        }
        apiInFlight = true;
        poller.pollOnceAsync(ok -> {
            apiInFlight = false;
            if (ok) {
                markSuccess(true);
            }
        });
    }

    private boolean runApiPoll(boolean join) {
        if (apiPollForTest != null) {
            return apiPollForTest.getAsBoolean();
        }
        ApiClient.Result result = poller.pollOnce(join ? Long.MIN_VALUE : clock.getAsLong());
        return result != null && result.ok();
    }

    private void overlayCompanies(List<CompanyView> incoming) {
        StockSnapshot cur = store.get();
        List<CompanyView> adjusted = new ArrayList<>(incoming.size());
        for (CompanyView n : incoming) {
            if (n == null) {
                continue;
            }
            adjusted.add(n.withRoundChangeFrom(cur.companyOf(n.name())));
        }
        Instant at = Instant.ofEpochMilli(clock.getAsLong());
        store.updateCompanies(adjusted, cur.apiHealthy(), at, "companies");
        StockSnapshot next = store.get();
        MarketDataAge.setCompaniesAtMs(at.toEpochMilli());
        File cf = cacheFile;
        if (cf != null) {
            MarketCache.write(cf, next.companies(), at.toEpochMilli());
        }
        BcStockLog.info("行情：companies 增量叠加 {} 家（全表 {} 家）",
                adjusted.size(), next.companies().size());
    }

    private synchronized void markSuccess(boolean requestCollect) {
        lastSuccessMs = clock.getAsLong();
        generation++;
        pendingAuto = true;
        BcStockLog.info("行情刷新世代 {}", generation);
        if (requestCollect) {
            collect.requestAfterRefresh();
        }
    }

    private void maybeAutoTrade() {
        if (!pendingAuto) {
            return;
        }
        if (gateway.inFlight() || gateway.userTradeBusy()) {
            return;
        }
        pendingAuto = false;
        AutoTradeExecutor.tryOnce(store, floors, gateway, clock.getAsLong());
    }
}
