package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动下单编排层的闸门顺序：总开关 / 紧急停止 / advice / 网关忙 / 无地板 → 一律不发。
 * 唯一允许发出的那一条必须走 {@code Origin.AUTO} 的既有路径（红线 3：不改语义，只锁行为）。
 */
class AutoTradeExecutorTest {

    private static final long NOW = 1_000_000L;
    private static final String FLOORS = "{\"interval\":\"15m\",\"floors\":[{\"company_id\":10,"
            + "\"company_name\":\"月港控股\",\"risk_level\":1,\"current\":61.76,"
            + "\"floor\":60.0,\"danger_zone\":60.4,\"distance_pct\":1.0,"
            + "\"status\":\"floor_confirmed_low\"}],\"last_error\":null,\"poll_interval\":300}";

    private final AtomicLong clock = new AtomicLong(NOW);
    private final List<String> sent = new ArrayList<>();
    private SnapshotStore store;
    private FloorCache floors;
    private CommandGateway gateway;

    @BeforeEach
    void setUp() {
        CompanyView company = new CompanyView("月港控股", 7, 55, 61.76, "61.76",
                Double.NaN, "", 1.5, Double.NaN, CompanyView.STATUS_TRADING, 1, 1_000,
                CompanyView.Source.API);
        store = new SnapshotStore(StockSnapshot.empty()
                .withCompanies(List.of(company), true, Instant.EPOCH)
                .withWallet(new WalletView(1_000.0), Instant.EPOCH));
        ApiClient client = new ApiClient("http://example.test", path -> FLOORS);
        floors = new FloorCache(clock::get, client);
        floors.putForTest(FloorSnapshot.parse(FLOORS), NOW);
        gateway = new CommandGateway(clock::get, command -> {
            sent.add(command);
            return true;
        });
        gateway.setSnapshotStore(store);
        AutoTradeSettings.resetForTest();
        TradeSettings.setEnabledForTest(false);
    }

    @AfterEach
    void tearDown() {
        TradeSettings.setEnabledForTest(false);
        AutoTradeSettings.resetForTest();
    }

    @Test
    @DisplayName("★ 总开关关着 → 不发")
    void disabledNeverSends() {
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ advice（默认档）→ 只建议不发")
    void adviceNeverSends() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.ADVICE);
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ 紧急停止 → 不发")
    void killNeverSends() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        AutoTradeSettings.setKillForTest(true);
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ 网关单飞中 → 不发（不排队、不抢用户单）")
    void busyGatewayNeverSends() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        assertTrue(gateway.trySendBalance(), "先占住单飞");
        int before = sent.size();
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertEquals(before, sent.size());
    }

    @Test
    @DisplayName("★ 地板未知 → 不发（未知不当 0）")
    void unknownFloorNeverSends() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        FloorCache empty = new FloorCache(clock::get, null);
        // 未 put → EMPTY
        assertFalse(AutoTradeExecutor.tryOnce(store, empty, gateway, NOW));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ LIMITED 正常路径：发一条 buy，走既有命令格式")
    void limitedSendsOneBuy() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        assertTrue(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertEquals(List.of("invest buy 7 10"), sent, "preset 默认 10 股，LIMITED 限额内");
    }
}
