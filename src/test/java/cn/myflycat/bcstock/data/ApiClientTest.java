package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiClientTest {

    @Test
    @DisplayName("实测 3 家 fixture：id/market_id 不混、null 涨跌是 NaN、破产股数未知")
    void parseMeasuredThree() throws Exception {
        String json;
        try (InputStream in = ApiClientTest.class.getClassLoader()
                .getResourceAsStream("api-samples/companies-3.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var rows = ApiClient.parseCompanies(json);
        assertEquals(3, rows.size());

        CompanyView yue = rows.get(0);
        assertEquals("月港控股", yue.name());
        assertEquals(56, yue.marketId(), "market_id 是下单编号");
        assertEquals(55, yue.apiId(), "id 是 K 线编号");
        assertEquals(44.21, yue.price(), 1e-9);
        assertEquals(150000, yue.availableShares());
        assertEquals(CompanyView.Source.API, yue.source());
        assertTrue(yue.tradable());
        assertTrue(Double.isNaN(yue.marketCap()), "/api/companies 没有市值，必须是 NaN 不是 0");
        assertFalse(yue.marketCapKnown());

        CompanyView apple = rows.get(1);
        assertEquals("红苹果短剧", apple.name());
        assertEquals(58, apple.marketId());
        assertEquals(57, apple.apiId());
        assertTrue(apple.bankrupt());
        assertFalse(apple.availableSharesKnown(), "破产 available_shares=null → 未知不是 0");
        assertEquals(CompanyView.SHARES_UNKNOWN, apple.availableShares());
        assertFalse(apple.tradable());

        CompanyView red = rows.get(2);
        assertEquals("赤石科技", red.name());
        assertTrue(Double.isNaN(red.changePct()), "change_pct=null 必须是 NaN");
    }

    @Test
    @DisplayName("health JSON 无 error 才算好")
    void healthLooksOk() {
        assertTrue(ApiClient.healthLooksOk("{\"companies\":54}"));
        assertFalse(ApiClient.healthLooksOk("{\"error\":\"down\"}"));
        assertFalse(ApiClient.healthLooksOk("not-json"));
        assertFalse(ApiClient.healthLooksOk(""));
    }

    @Test
    @DisplayName("Getter 抛异常 → FAILED，不往外抛；且只发一次请求")
    void failureIsFailedNotThrown() {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        ApiClient client = new ApiClient("http://127.0.0.1:1", path -> {
            hits.incrementAndGet();
            throw new java.net.ConnectException("Connection refused");
        });
        ApiClient.Result r = client.fetch();
        assertEquals(ApiClient.Health.FAILED, r.health());
        assertTrue(r.companies().isEmpty());
        assertEquals(1, hits.get(), "失败路径只打 companies，不再先探 health");
    }

    @Test
    @DisplayName("fetch 只打 /api/companies 一次（不再探 health）")
    void companiesOnlyOnce() {
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        ApiClient client = new ApiClient("http://example.invalid", path -> {
            hits.incrementAndGet();
            if (path.equals("/api/companies")) {
                return "[{\"id\":55,\"market_id\":56,\"name\":\"月港控股\","
                        + "\"risk_level\":3,\"status\":\"交易中\",\"latest_price\":42.38,"
                        + "\"change_pct\":1.5,\"available_shares\":100}]";
            }
            throw new IllegalStateException("unexpected " + path);
        });
        ApiClient.Result r = client.fetch();
        assertTrue(r.ok());
        assertEquals(ApiClient.Health.OK, r.health());
        assertEquals(1, r.companies().size());
        assertEquals(56, r.companies().get(0).marketId());
        assertEquals(55, r.companies().get(0).apiId());
        assertEquals(1, hits.get());
    }
}
