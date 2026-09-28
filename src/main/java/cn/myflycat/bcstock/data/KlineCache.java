package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * 按 {@code apiId} 缓存 K 线。15 分钟 TTL（与行情相位一致）。
 * {@link #request} 可在输入回调里调；渲染路径只 {@link #get}，不许在这里发网。
 */
public final class KlineCache {

    public static final long TTL_MS = 15L * 60L * 1000L;
    public static final KlineCache SHARED = new KlineCache();

    public enum Status { EMPTY, LOADING, READY, FAILED }

    private final ConcurrentHashMap<Integer, Entry> byApiId = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private ApiClient client;
    private ExecutorService executor;

    public KlineCache() {
        this(System::currentTimeMillis, null);
    }

    public KlineCache(LongSupplier clock, ApiClient client) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
        this.client = client;
    }

    public void setClient(ApiClient client) {
        this.client = client;
    }

    /** 仅测试：同步塞进缓存，不启线程。 */
    public void putForTest(KlineSeries series, long fetchedAtMs) {
        if (series == null) {
            return;
        }
        byApiId.put(series.apiId(), new Entry(Status.READY, series, fetchedAtMs, fetchedAtMs));
    }

    public void putFailedForTest(int apiId, long atMs) {
        byApiId.put(apiId, new Entry(Status.FAILED, null, atMs, atMs));
    }

    public Status status(int apiId) {
        if (apiId < 0) {
            return Status.EMPTY;
        }
        Entry e = byApiId.get(apiId);
        if (e == null) {
            return Status.EMPTY;
        }
        if (e.status == Status.READY && clock.getAsLong() - e.fetchedAt >= TTL_MS) {
            return Status.EMPTY;
        }
        if (e.status == Status.FAILED && clock.getAsLong() - e.fetchedAt >= TTL_MS) {
            return Status.EMPTY;
        }
        return e.status;
    }

    public Optional<KlineSeries> get(int apiId) {
        if (status(apiId) != Status.READY) {
            return Optional.empty();
        }
        Entry e = byApiId.get(apiId);
        return Optional.ofNullable(e == null ? null : e.series);
    }

    /**
     * 选中某家时请求一次。已在飞 / TTL 内有结果或失败 → 不重发。
     * <b>不要在 render 里调。</b>
     */
    public synchronized void request(int apiId) {
        if (apiId < 0) {
            return;
        }
        long now = clock.getAsLong();
        Entry e = byApiId.get(apiId);
        if (e != null) {
            if (e.status == Status.LOADING) {
                return;
            }
            if ((e.status == Status.READY || e.status == Status.FAILED)
                    && now - e.fetchedAt < TTL_MS) {
                return;
            }
        }
        byApiId.put(apiId, new Entry(Status.LOADING, null, now, now));
        ApiClient api = (client != null) ? client : new ApiClient();
        executor().execute(() -> fetch(apiId, api));
    }

    private void fetch(int apiId, ApiClient api) {
        try {
            Optional<KlineSeries> series = api.fetchKline(apiId, "15m");
            long now = clock.getAsLong();
            if (series.isPresent()) {
                byApiId.put(apiId, new Entry(Status.READY, series.get(), now, now));
                BcStockLog.info("K线就绪：apiId={} 点数={}", apiId, series.get().pointCount());
            } else {
                byApiId.put(apiId, new Entry(Status.FAILED, null, now, now));
                BcStockLog.info("K线失败：apiId={}（占位，不重试刷屏）", apiId);
            }
        } catch (Exception e) {
            long now = clock.getAsLong();
            byApiId.put(apiId, new Entry(Status.FAILED, null, now, now));
            BcStockLog.info("K线异常：apiId={} {}（占位）", apiId, e.toString());
        }
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bcstock-kline");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    private record Entry(Status status, KlineSeries series, long fetchedAt, long requestedAt) {
    }
}
