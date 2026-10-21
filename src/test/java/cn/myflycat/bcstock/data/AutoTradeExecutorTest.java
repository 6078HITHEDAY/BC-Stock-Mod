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
        AutoTradeExecutor.resetPending();
    }

    @AfterEach
    void tearDown() {
        TradeSettings.setEnabledForTest(false);
        AutoTradeSettings.resetForTest();
        AutoTradeExecutor.resetPending();
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
    @DisplayName("★ LIMITED 正常路径：先查实时价，回执到后按实时价发一条 buy")
    void limitedQuotesThenSendsOneBuy() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        // 阶段 1：只发查价，不下单
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertEquals(List.of("invest company info 7"), sent, "自动单先查实时价（2026-10-21 拍板）");
        assertTrue(AutoTradeExecutor.pendingForTest());
        // 阶段 2：喂同一家公司的查价回执 → tick 里按实时价下单
        for (String line : companyInfoLines(7, 61.76)) {
            gateway.onChatLine(line);
        }
        AutoTradeExecutor.tick(store, gateway, clock.get());
        assertEquals(List.of("invest company info 7", "invest buy 7 10"), sent,
                "preset 默认 10 股；查价后同公司下单免 MIN_GAP（既有设计）");
        assertFalse(AutoTradeExecutor.pendingForTest());
    }

    @Test
    @DisplayName("★ 查价回执串台（别的公司）→ 不下单，等下个 tick/超时")
    void mismatchedQuoteDoesNotTrade() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        for (String line : companyInfoLines(55, 61.76)) {
            gateway.onChatLine(line);
        }
        AutoTradeExecutor.tick(store, gateway, clock.get());
        assertEquals(List.of("invest company info 7"), sent, "串台回执不触发下单");
        assertTrue(AutoTradeExecutor.pendingForTest());
    }

    @Test
    @DisplayName("★ 查价超时 → 放弃本轮，不下单")
    void quoteTimeoutAborts() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        AutoTradeExecutor.tick(store, gateway, clock.get()); // 未超时，仍在等
        assertTrue(AutoTradeExecutor.pendingForTest());
        clock.set(NOW + AutoTradeExecutor.QUOTE_GIVE_UP_MS + 1);
        AutoTradeExecutor.tick(store, gateway, clock.get());
        assertFalse(AutoTradeExecutor.pendingForTest(), "超时放弃");
        assertEquals(List.of("invest company info 7"), sent, "没查到价就不下单");
    }

    @Test
    @DisplayName("★ 实时价比牌价高：余额按实时价重夹，买不起就放弃")
    void expensiveLiveQuoteCapsQty() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        // 钱包 1_000：牌价 61.76 建议买 10（按牌价够），实时价 200 → floor(1000/200)=5
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        for (String line : companyInfoLines(7, 200.0)) {
            gateway.onChatLine(line);
        }
        AutoTradeExecutor.tick(store, gateway, clock.get());
        assertEquals(List.of("invest company info 7", "invest buy 7 5"), sent,
                "按实时价重夹数量：floor(1000/200)=5");
    }

    @Test
    @DisplayName("★ 断线复位清在途意图")
    void resetPendingClearsIntent() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setModeForTest(AutoTradeSettings.Mode.LIMITED);
        assertFalse(AutoTradeExecutor.tryOnce(store, floors, gateway, NOW));
        assertTrue(AutoTradeExecutor.pendingForTest());
        AutoTradeExecutor.resetPending();
        assertFalse(AutoTradeExecutor.pendingForTest());
        AutoTradeExecutor.tick(store, gateway, clock.get());
        assertEquals(List.of("invest company info 7"), sent, "复位后 tick 不补发");
    }

    /** company-info 回执黄金样本的参数化版（对齐 cmd-samples/company-info-55.txt 结构）。 */
    private static List<String> companyInfoLines(int marketId, double price) {
        return List.of(
                "-=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-",
                "月港控股",
                "Id: " + marketId,
                "状态: 交易中",
                "价格: " + price,
                "风险等级: 1",
                "可用股数: 64000",
                "历史记录: ",
                "",
                "  +3.627%",
                "  -5.855%",
                "",
                "[Buy Stocks]",
                "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-");
    }
}
