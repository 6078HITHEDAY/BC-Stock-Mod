package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * API 全量拉行情 + 地板。调用方是 {@link MarketRefreshCoordinator}
 * （进服 / 聊天失败 / 20 分钟兜底），不再按时钟相位自己开火。
 *
 * <p>HTTP 只在后台线程（{@link #pollOnceAsync}）；渲染路径零发包。单飞：上一轮没回来不开新的。
 * 失败时保留已有公司行（含启动缓存），只标不健康。
 */
public final class MarketPollScheduler {

    public static final MarketPollScheduler SHARED = new MarketPollScheduler();

    private LongSupplier clock = System::currentTimeMillis;
    private Supplier<ApiClient> clientFactory = ApiClient::new;
    private SnapshotStore store = SnapshotStore.SHARED;
    private FloorCache floors = FloorCache.SHARED;
    private File cacheFile;
    private ExecutorService executor;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private long lastFailLogPhaseId = Long.MIN_VALUE;
    private final AtomicInteger pollCount = new AtomicInteger();

    public MarketPollScheduler() {
    }

    public MarketPollScheduler(LongSupplier clock, Supplier<ApiClient> clientFactory,
                               SnapshotStore store, FloorCache floors) {
        setClock(clock);
        setClientFactory(clientFactory);
        setStore(store);
        setFloors(floors);
    }

    public void setClock(LongSupplier clock) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
    }

    public void setClientFactory(Supplier<ApiClient> clientFactory) {
        this.clientFactory = (clientFactory == null) ? ApiClient::new : clientFactory;
    }

    public void setStore(SnapshotStore store) {
        this.store = (store == null) ? SnapshotStore.SHARED : store;
    }

    public void setFloors(FloorCache floors) {
        this.floors = (floors == null) ? FloorCache.SHARED : floors;
    }

    /** 成功刷新后覆盖写入的 {@code market-cache.json}；null 则不写。 */
    public void setCacheFile(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    public int pollCount() {
        return pollCount.get();
    }

    /**
     * 拉一次行情 + 地板，写入 store。可被启动线程与兜底调度复用。
     *
     * @param phaseIdForLog 失败日志去重用；启动首次传 {@link Long#MIN_VALUE} 表示总打一行
     */
    public ApiClient.Result pollOnce(long phaseIdForLog) {
        return pollOnce(phaseIdForLog, null);
    }

    /**
     * @param stillValid 非空时：HTTP 回来后、落盘前再验；失败则丢弃结果（断线 / 换服）。
     *                   测试与无门禁路径传 null。
     */
    public ApiClient.Result pollOnce(long phaseIdForLog, BooleanSupplier stillValid) {
        pollCount.incrementAndGet();
        ApiClient client = clientFactory.get();
        ApiClient.Result result = client.fetch();
        if (stillValid != null && !stillValid.getAsBoolean()) {
            BcStockLog.info("行情结果丢弃（门禁已失效）");
            return ApiClient.Result.failed();
        }
        Instant now = Instant.ofEpochMilli(clock.getAsLong());
        if (result.ok()) {
            store.updateCompanies(result.companies(), true, now);
            Map<String, Integer> ids = new LinkedHashMap<>();
            for (CompanyView c : result.companies()) {
                if (c.marketIdKnown()) {
                    ids.put(c.name(), c.marketId());
                }
            }
            store.updateIds(ids, now);
            MarketDataAge.setCompaniesAtMs(now.toEpochMilli());
            File cf = cacheFile;
            if (cf != null) {
                MarketCache.write(cf, result.companies(), now.toEpochMilli());
            }
            BcStockLog.info("行情刷新：companies={} latest_price_at={} marker={}",
                    result.companies().size(),
                    result.latestPriceAt() == null ? "-" : result.latestPriceAt(),
                    result.lastUpdateMarker() == null ? "-" : result.lastUpdateMarker());
        } else {
            // 有缓存/上次数据时保留公司行；界面按数据年龄说话，不因单次失败清空盘面。
            store.markApiUnhealthy();
            logFailOnce(phaseIdForLog);
        }
        floors.setClient(client);
        floors.fetchSync(client);
        return result;
    }

    /**
     * 后台拉一次。渲染路径 / 客户端 tick 必须走这里，不许同步 {@link #pollOnce}。
     *
     * @return 是否真的开了新任务（已在飞则 false）
     */
    public boolean pollOnceAsync(Consumer<Boolean> onDone) {
        return pollOnceAsync(onDone, null);
    }

    public boolean pollOnceAsync(Consumer<Boolean> onDone, BooleanSupplier stillValid) {
        if (!inFlight.compareAndSet(false, true)) {
            return false;
        }
        executor().execute(() -> {
            boolean ok = false;
            try {
                ok = pollOnce(Long.MIN_VALUE, stillValid).ok();
            } finally {
                inFlight.set(false);
                if (onDone != null) {
                    try {
                        onDone.accept(ok);
                    } catch (RuntimeException e) {
                        BcStockLog.warn("行情异步回调失败：{}", e.toString());
                    }
                }
            }
        });
        return true;
    }

    private void logFailOnce(long phaseId) {
        if (phaseId != Long.MIN_VALUE && phaseId == lastFailLogPhaseId) {
            return;
        }
        lastFailLogPhaseId = phaseId;
        BcStockLog.warn("行情刷新失败（不重试）");
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bcstock-market-poll");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }
}
