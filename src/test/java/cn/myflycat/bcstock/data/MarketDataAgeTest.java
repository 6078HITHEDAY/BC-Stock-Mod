package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataAgeTest {

    @AfterEach
    void reset() {
        MarketDataAge.resetForTest();
    }

    @Test
    @DisplayName("年龄文案：分钟 / 较旧")
    void labels() {
        long now = 1_000_000_000L;
        MarketDataAge.setCompaniesAtMs(now - 8L * 60_000L);
        assertEquals("数据 8 分钟前", MarketDataAge.formatLabel(now));

        MarketDataAge.setCompaniesAtMs(now - MarketDataAge.STALE_MS - 60_000L);
        String stale = MarketDataAge.formatLabel(now);
        assertTrue(stale.startsWith("数据较旧"), stale);
    }
}
