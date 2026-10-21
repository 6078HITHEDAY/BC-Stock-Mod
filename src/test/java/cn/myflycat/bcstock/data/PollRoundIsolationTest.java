package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 刷新轮次单步失败隔离：任何一步（尤其写缓存）失败，都不许把编号表与地板更新一起带走。
 *
 * <p>事故背景：2026-09-27 22:02 写 {@code market-cache.json} 抛
 * {@code IllegalArgumentException: NaN is not a valid double value}，
 * {@code pollOnce} 从那一行整个抛出——该轮的 {@code updateIds} 与
 * {@code floors.fetchSync} 全部作废，数据静默丢失。
 */
class PollRoundIsolationTest {

    private static final String COMPANIES = "[{\"id\":10,\"market_id\":7,\"name\":\"帕拉伦联合储蓄\","
            + "\"risk_level\":1,\"status\":\"交易中\",\"latest_price\":61.76,"
            + "\"change_pct\":0,\"available_shares\":1}]";

    private static final String FLOORS = "{\"interval\":\"15m\",\"floors\":[{\"company_id\":10,"
            + "\"company_name\":\"帕拉伦联合储蓄\",\"risk_level\":1,\"current\":61.76,"
            + "\"floor\":60.0,\"danger_zone\":60.4,\"distance_pct\":2.9333,"
            + "\"status\":\"floor_confirmed_low\"}],\"last_error\":null,\"poll_interval\":300}";

    private static ApiClient client() {
        return new ApiClient("http://example.test", path -> {
            if (path.contains("/companies")) {
                return COMPANIES;
            }
            if (path.contains("/floors")) {
                return FLOORS;
            }
            throw new IllegalStateException("unexpected " + path);
        });
    }

    @Test
    @DisplayName("★ 写行情缓存必败时，编号表与地板仍然更新")
    void cacheWriteFailureDoesNotAbortRound(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SnapshotStore store = new SnapshotStore();
        ApiClient client = client();
        FloorCache floors = new FloorCache(clock::get, client);
        MarketPollScheduler sched = new MarketPollScheduler(clock::get, () -> client, store, floors);
        // 把缓存路径指到一个目录：writeString 必败。
        sched.setCacheFile(dir.toFile());

        ApiClient.Result r = sched.pollOnce(Long.MIN_VALUE);

        assertTrue(r.ok(), "HTTP 本身成功");
        assertTrue(store.get().companyOf("帕拉伦联合储蓄").marketIdKnown(),
                "编号表必须已更新（缓存写失败不许连坐）");
        assertEquals(7, store.get().companyOf("帕拉伦联合储蓄").marketId());
        assertTrue(floors.ofName("帕拉伦联合储蓄").isPresent(),
                "地板必须已更新（缓存写失败不许连坐）");
        assertEquals(60.0, floors.ofName("帕拉伦联合储蓄").orElseThrow().floor(), 1e-9);
        assertFalse(dir.resolve("market-cache.json").toFile().isFile(), "缓存确实没写成");
    }
}
