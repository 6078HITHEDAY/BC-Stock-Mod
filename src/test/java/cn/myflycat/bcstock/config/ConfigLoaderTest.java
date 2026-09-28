package cn.myflycat.bcstock.config;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    File tmp;

    @Test
    @DisplayName("正常：完整 JSON 读出各项")
    void normalParse() {
        String json = """
                {
                  "board": {"showBankrupt": false},
                  "collect": {"enabled": true},
                  "alert": {"enabled": true, "changeEnabled": true, "floorEnabled": false, "pnlEnabled": true},
                  "trade": {"enabled": true},
                  "auto": {"mode": "limited", "maxPerTrade": 20, "maxPerDay": 50, "kill": true,
                           "allowBuy": false, "allowSell": true, "cooldownSec": 15, "cashFloor": 100.5,
                           "whitelist": "月港控股,56"},
                  "decision": {"preset": "aggressive"},
                  "api": {"base": "http://example.test", "timeoutMs": 12000}
                }
                """;
        BcStockConfig c = ConfigLoader.parseLenient(json);
        assertFalse(c.board.showBankrupt);
        assertTrue(c.collect.enabled);
        assertTrue(c.alert.enabled);
        assertTrue(c.alert.changeEnabled);
        assertFalse(c.alert.floorEnabled);
        assertTrue(c.alert.pnlEnabled);
        assertTrue(c.trade.enabled);
        assertEquals("limited", c.auto.mode);
        assertEquals(20, c.auto.maxPerTrade);
        assertEquals(50, c.auto.maxPerDay);
        assertTrue(c.auto.kill);
        assertFalse(c.auto.allowBuy);
        assertTrue(c.auto.allowSell);
        assertEquals(15, c.auto.cooldownSec);
        assertEquals(100.5, c.auto.cashFloor, 1e-9);
        assertEquals("月港控股,56", c.auto.whitelist);
        assertEquals("aggressive", c.decision.preset);
        assertEquals("http://example.test", c.api.base);
        assertEquals(12_000, c.api.timeoutMs);
    }

    @Test
    @DisplayName("缺键：用该项安全默认")
    void missingKeysUseDefaults() {
        BcStockConfig c = ConfigLoader.parseLenient("{\"trade\":{}}");
        assertFalse(c.trade.enabled);
        assertFalse(c.collect.enabled);
        assertEquals("advice", c.auto.mode);
        assertEquals(15_000, c.api.timeoutMs);
        assertTrue(c.board.showBankrupt);
        assertTrue(c.auto.allowBuy);
        assertTrue(c.auto.allowSell);
        assertEquals(60, c.auto.cooldownSec);
        assertEquals(0.0, c.auto.cashFloor, 1e-9);
        assertEquals("", c.auto.whitelist);
    }

    @Test
    @DisplayName("类型错：该项回落默认，其它键仍有效")
    void wrongTypesFallbackPerKey() {
        String json = """
                {
                  "trade": {"enabled": "yes"},
                  "auto": {"maxPerTrade": "很多", "mode": "full"},
                  "api": {"timeoutMs": "慢"}
                }
                """;
        BcStockConfig c = ConfigLoader.parseLenient(json);
        assertFalse(c.trade.enabled, "布尔类型错 → 默认 false");
        assertEquals(10, c.auto.maxPerTrade);
        assertEquals("full", c.auto.mode);
        assertEquals(15_000, c.api.timeoutMs);
    }

    @Test
    @DisplayName("文件损坏：整份安全回落，trade/collect/alert 全关")
    void corruptFileSafeFallback() throws Exception {
        File f = new File(tmp, "bcstock.json");
        Files.writeString(f.toPath(), "{ this is not json !!!");
        ConfigLoader.LoadResult r = ConfigLoader.loadOrCreate(f, PropertySource.empty());
        assertTrue(r.parseFailed());
        assertFalse(r.effective().trade.enabled);
        assertFalse(r.effective().collect.enabled);
        assertFalse(r.effective().alert.enabled);
        assertEquals("advice", r.effective().auto.mode);
        assertTrue(r.message().contains("配置解析失败") || r.message().contains("回落"));
    }

    @Test
    @DisplayName("★ -D 覆盖优先于配置文件")
    void systemPropertyOverridesFile() throws Exception {
        File f = new File(tmp, "bcstock.json");
        ConfigLoader.save(f, BcStockConfig.safeDefaults());
        Map<String, String> props = new HashMap<>();
        props.put(ConfigLoader.PROP_TRADE, "true");
        props.put(ConfigLoader.PROP_API_TIMEOUT, "20000");
        props.put(ConfigLoader.PROP_AUTO_MODE, "full");
        PropertySource src = props::get;
        ConfigLoader.LoadResult r = ConfigLoader.loadOrCreate(f, src);
        assertFalse(r.parseFailed());
        assertFalse(r.fileConfig().trade.enabled, "文件本身仍是默认关");
        assertTrue(r.effective().trade.enabled, "-D 打开交易");
        assertEquals(20_000, r.effective().api.timeoutMs);
        assertEquals("full", r.effective().auto.mode);
    }

    @Test
    @DisplayName("首次运行自动生成带默认值的配置文件")
    void firstRunCreatesFile() {
        File f = new File(tmp, "config/bcstock.json");
        assertFalse(f.exists());
        ConfigLoader.LoadResult r = ConfigLoader.loadOrCreate(f, PropertySource.empty());
        assertTrue(r.created());
        assertTrue(f.isFile());
        assertFalse(r.effective().trade.enabled);
    }
}
