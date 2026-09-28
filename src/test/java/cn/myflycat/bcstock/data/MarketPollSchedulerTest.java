package cn.myflycat.bcstock.data;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPollSchedulerTest {

    @Test
    @DisplayName("pollOnce 拉 companies+floors；不再按相位自己开火")
    void pollOnceFetchesCompaniesAndFloors() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AtomicInteger hits = new AtomicInteger();
        ApiClient client = new ApiClient("http://example.test", path -> {
            hits.incrementAndGet();
            if (path.contains("/companies")) {
                return "[{\"id\":10,\"market_id\":0,\"name\":\"帕拉伦联合储蓄\",\"risk_level\":1,"
                        + "\"status\":\"交易中\",\"latest_price\":61.76,\"change_pct\":0,\"available_shares\":1}]";
            }
            if (path.contains("/floors")) {
                return "{\"interval\":\"15m\",\"floors\":[],\"last_error\":null,"
                        + "\"last_external_error\":null,\"poll_interval\":300}";
            }
            throw new IllegalStateException("unexpected " + path);
        });
        SnapshotStore store = new SnapshotStore();
        FloorCache floors = new FloorCache(clock::get, client);
        MarketPollScheduler sched = new MarketPollScheduler(
                clock::get, () -> client, store, floors);

        sched.pollOnce(Long.MIN_VALUE);
        assertEquals(2, hits.get(), "companies+floors（不再打 health）");
        assertEquals(1, sched.pollCount());
        assertTrue(store.get().apiHealthy());
        assertEquals(1, store.get().companies().size());

        sched.pollOnce(Long.MIN_VALUE);
        assertEquals(2, sched.pollCount());
        assertEquals(4, hits.get());
    }

    @Test
    @DisplayName("★ 渲染路径源码不许出现 HTTP / tick 发网")
    void rendererNeverHttp() throws Exception {
        String renderer = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/BoardRenderer.java"));
        String hud = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/StockHud.java"));
        for (String src : java.util.List.of(renderer, hud)) {
            assertFalse(src.contains("HttpClient"));
            assertFalse(src.contains("fetchKline"));
            assertFalse(src.contains("fetchFloors"));
            assertFalse(src.contains("pollOnce"));
            assertFalse(src.contains(".trySend"));
        }
        assertTrue(renderer.contains("KLINE_LIMIT") || renderer.contains("近 "));
    }

    @Test
    @DisplayName("失败降级：apiHealthy=false，但保留已有公司行")
    void failDegradesKeepsCompanies() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CompanyView kept = new CompanyView("月港控股", CompanyView.ID_UNKNOWN, 55, 42.38, "42.38",
                Double.NaN, "", 0, Double.NaN,
                CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API);
        SnapshotStore store = new SnapshotStore(
                StockSnapshot.empty().withCompanies(java.util.List.of(kept), true, Instant.EPOCH));
        ApiClient client = new ApiClient("http://127.0.0.1:1", path -> {
            throw new IllegalStateException("HTTP 500");
        });
        FloorCache floors = new FloorCache(clock::get, client);
        MarketPollScheduler sched = new MarketPollScheduler(
                clock::get, () -> client, store, floors);
        sched.pollOnce(Long.MIN_VALUE);
        assertEquals(1, sched.pollCount());
        assertFalse(store.get().apiHealthy());
        assertEquals(1, store.get().companies().size(), "单次失败不清空已有行情");
        assertEquals("月港控股", store.get().companies().get(0).name());
    }
}
