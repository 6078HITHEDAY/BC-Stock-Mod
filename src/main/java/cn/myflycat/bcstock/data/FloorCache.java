package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 服务端地板快照缓存。TTL = 300s（对齐 {@code poll_interval}），
 * 略短于相位会浪费请求，短于后台缓存刷新没意义。
 *
 * <p>{@link #request} 可在 tick / 行情刷新线程里调；渲染路径只 {@link #ofName} /
 * {@link #distancePctByName}，不许在这里发网。
 */
public final class FloorCache {

    /** 与服务端 poll_interval=300 对齐；更短只会反复打同一份缓存。 */
    public static final long TTL_MS = 300_000L;

    public static final FloorCache SHARED = new FloorCache();

    public enum Status { EMPTY, LOADING, READY, FAILED }

    private final LongSupplier clock;
    private ApiClient client;
    private ExecutorService executor;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    private volatile Status status = Status.EMPTY;
    private volatile FloorSnapshot snapshot;
    private volatile long fetchedAt = Long.MIN_VALUE;

    public FloorCache() {
        this(System::currentTimeMillis, null);
    }

    public FloorCache(LongSupplier clock, ApiClient client) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
        this.client = client;
    }

    public void setClient(ApiClient client) {
        this.client = client;
    }

    public Status status() {
        long now = clock.getAsLong();
        if ((status == Status.READY || status == Status.FAILED)
                && fetchedAt != Long.MIN_VALUE
                && now - fetchedAt >= TTL_MS) {
            return Status.EMPTY;
        }
        return status;
    }

    public Optional<FloorSnapshot> get() {
        if (status() != Status.READY || snapshot == null) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    public Optional<FloorView> ofName(String name) {
        return get().map(s -> s.ofName(name)).filter(f -> f != null && f.known());
    }

    /** 公司名 → distance_pct；没有地板的公司不在 map 里。 */
    public Map<String, Double> distancePctByName() {
        return get().map(FloorSnapshot::distancePctByName).orElse(Map.of());
    }

    /** 仅测试：同步塞进缓存。 */
    public void putForTest(FloorSnapshot snap, long fetchedAtMs) {
        this.snapshot = snap;
        this.fetchedAt = fetchedAtMs;
        this.status = (snap == null || snap.hasError()) ? Status.FAILED : Status.READY;
    }

    public void putFailedForTest(long atMs) {
        this.snapshot = null;
        this.fetchedAt = atMs;
        this.status = Status.FAILED;
    }

    public void clearForTest() {
        snapshot = null;
        fetchedAt = Long.MIN_VALUE;
        status = Status.EMPTY;
        inFlight.set(false);
    }

    /**
     * 后台拉一次。已在飞 / TTL 内有结果或失败 → 不重发。
     * <b>不要在 render 里调。</b>
     */
    public void request() {
        long now = clock.getAsLong();
        Status st = status();
        if (st == Status.LOADING) {
            return;
        }
        if ((st == Status.READY || st == Status.FAILED) && now - fetchedAt < TTL_MS) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        status = Status.LOADING;
        ApiClient api = (client != null) ? client : new ApiClient();
        executor().execute(() -> fetch(api));
    }

    /** 同步拉一次（行情刷新线程里复用，不另起池）。 */
    public void fetchSync(ApiClient api) {
        if (api == null) {
            return;
        }
        long now = clock.getAsLong();
        try {
            Optional<FloorSnapshot> snap = api.fetchFloors();
            if (snap.isPresent() && !snap.get().hasError()) {
                snapshot = snap.get();
                fetchedAt = now;
                status = Status.READY;
                BcStockLog.info("地板就绪：{} 家", snap.get().byName().size());
            } else {
                snapshot = null;
                fetchedAt = now;
                status = Status.FAILED;
                BcStockLog.info("地板失败或 last_error 非空（占位，不重试）");
            }
        } catch (Exception e) {
            snapshot = null;
            fetchedAt = now;
            status = Status.FAILED;
            BcStockLog.info("地板异常：{}（占位）", e.toString());
        }
    }

    private void fetch(ApiClient api) {
        try {
            fetchSync(api);
        } finally {
            inFlight.set(false);
        }
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bcstock-floors");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }
}
