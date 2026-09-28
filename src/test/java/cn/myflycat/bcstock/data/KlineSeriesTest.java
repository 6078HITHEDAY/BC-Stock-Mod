package cn.myflycat.bcstock.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KlineSeriesTest {

    @Test
    @DisplayName("★ 点数 == 本次返回 candles 条数；最高最低 == 手算；用的是 apiId")
    void parseFixtureMatchesHandCalc() throws IOException {
        String body = new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/kline-sample-10-15m.json"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        // 手算样本：12 根，close min=103.05 max=105.32（生成脚本输出）
        KlineSeries series = ApiClient.parseKline(body, 10);
        assertNotNull(series);
        assertEquals(10, series.apiId(), "必须用 apiId，不是 market_id");
        assertEquals(12, series.pointCount());
        assertEquals(105.32, series.maxClose(), 1e-9);
        assertEquals(103.05, series.minClose(), 1e-9);
        assertEquals("105.32", series.maxLabel());
        assertEquals("103.05", series.minLabel());

        int[][] pts = series.screenPoints(0, 0, 100, 40);
        assertEquals(series.pointCount(), pts.length, "渲染点数必须等于本次返回 candles 条数");
        assertEquals(0, pts[0][0]);
        assertEquals(99, pts[pts.length - 1][0]);
    }

    @Test
    @DisplayName("★ company_id 与请求 apiId 不一致 → 丢弃")
    void rejectsIdMismatch() {
        String body = "{\"company_id\":99,\"interval\":\"15m\",\"candles\":[{\"time\":1,\"open\":1,\"high\":1,\"low\":1,\"close\":1}]}";
        assertEquals(null, ApiClient.parseKline(body, 10));
    }

    @Test
    @DisplayName("旧 data 键仍可兜底解析")
    void dataKeyFallback() {
        String body = "{\"company_id\":10,\"interval\":\"15m\",\"data\":[{\"time\":1,\"open\":2,\"high\":2,\"low\":2,\"close\":2}]}";
        KlineSeries s = ApiClient.parseKline(body, 10);
        assertNotNull(s);
        assertEquals(1, s.pointCount());
    }

    @Test
    @DisplayName("缓存 TTL 内不重复请求（假时钟）")
    void cacheTtl() {
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        KlineCache cache = new KlineCache(clock::get, null);
        KlineSeries s = new KlineSeries(10, "15m", new double[]{1, 2, 3}, new long[]{1, 2, 3});
        cache.putForTest(s, clock.get());
        assertEquals(KlineCache.Status.READY, cache.status(10));
        assertEquals(3, cache.get(10).orElseThrow().pointCount());
        cache.request(10);
        assertEquals(KlineCache.Status.READY, cache.status(10), "TTL 内 request 不得把状态打成 LOADING");
        clock.addAndGet(KlineCache.TTL_MS);
        assertEquals(KlineCache.Status.EMPTY, cache.status(10));
    }

    @Test
    @DisplayName("fetchKline 路径拼的是 /api/history/candles + company_id + limit")
    void fetchUsesHistoryCandlesPath() {
        AtomicReference<String> path = new AtomicReference<>();
        ApiClient client = new ApiClient("http://example.test", p -> {
            path.set(p);
            return "{\"company_id\":55,\"interval\":\"15m\",\"candles\":[{\"time\":1,\"open\":2,\"high\":2,\"low\":2,\"close\":2}]}";
        });
        var series = client.fetchKline(55, "15m").orElseThrow();
        assertEquals("/api/history/candles?company_id=55&interval=15m&limit=" + ApiClient.KLINE_LIMIT,
                path.get());
        assertEquals(55, series.apiId());
        assertTrue(series.pointCount() == 1);
    }

    @Test
    @DisplayName("★ 非 2xx → Optional.empty，不抛")
    void non2xxReturnsEmpty() {
        ApiClient client = new ApiClient("http://example.test", p -> {
            throw new IllegalStateException("HTTP 404 " + p);
        });
        Optional<KlineSeries> out = client.fetchKline(10, "15m");
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("DEFAULT_BASE 是 BCquant 前缀路径")
    void defaultBaseIsBCquant() {
        assertEquals("https://tool.myflycat.cn/quant", ApiClient.DEFAULT_BASE);
        assertEquals(500, ApiClient.KLINE_LIMIT);
    }
}
