package cn.myflycat.bcstock.data;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCacheTest {

    @TempDir
    File tmp;

    @AfterEach
    void reset() {
        MarketDataAge.resetForTest();
    }

    @Test
    @DisplayName("★ 缓存里有 market_id，恢复后未刷新 → 不允许下单")
    void cacheWipesMarketIdNotTradable() {
        CompanyView live = new CompanyView("月港控股", 56, 55, 44.21, "44.21",
                Double.NaN, "", 1.5, Double.NaN,
                CompanyView.STATUS_TRADING, 3, 1000, CompanyView.Source.API);
        assertTrue(live.tradable());

        File f = new File(tmp, MarketCache.FILE_NAME);
        MarketCache.write(f, List.of(live), 1_700_000_000_000L);

        MarketCache.Payload payload = MarketCache.read(f).orElseThrow();
        assertEquals(56, payload.companies().get(0).marketId, "盘上可以带着旧号");

        List<CompanyView> views = MarketCache.toViewsWipingMarketId(payload);
        assertEquals(1, views.size());
        assertFalse(views.get(0).marketIdKnown());
        assertFalse(views.get(0).tradable(), "未经验证的 market_id 绝不能下单");
        assertEquals(55, views.get(0).apiId(), "apiId 保留，供 K 线");
        assertEquals(44.21, views.get(0).price(), 1e-9);
    }

    @Test
    @DisplayName("灌进 SnapshotStore 后仍不可交易，直到 updateIds")
    void storeRestoreThenLiveIds() {
        CompanyView live = new CompanyView("月港控股", 56, 55, 44.21, "44.21",
                Double.NaN, "", 1.5, Double.NaN,
                CompanyView.STATUS_TRADING, 3, 1000, CompanyView.Source.API);
        File f = new File(tmp, MarketCache.FILE_NAME);
        MarketCache.write(f, List.of(live), System.currentTimeMillis());

        SnapshotStore store = new SnapshotStore();
        List<CompanyView> views = MarketCache.toViewsWipingMarketId(MarketCache.read(f).orElseThrow());
        store.set(StockSnapshot.empty().withCompanies(views, true, java.time.Instant.EPOCH), false);
        assertFalse(store.get().companyOf("月港控股").tradable());

        store.updateIds(java.util.Map.of("月港控股", 56), java.time.Instant.now());
        assertTrue(store.get().companyOf("月港控股").tradable());
    }
}
