package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.data.WalletView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRulesTest {

    @Test
    @DisplayName("★ 同一刷新世代同一规则同一公司只报一次；跨世代可再报")
    void dedupeByPhaseExactlyN() {
        AlertRules rules = new AlertRules();
        AlertRules.Switches sw = new AlertRules.Switches(true, true, false, false);
        CompanyView hot = company("月港控股", 6.0);
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, List.of(hot), List.of(),
                WalletView.UNKNOWN, true);

        List<AlertRules.Alert> a1 = rules.evaluate(snap, 100, sw, Map.of());
        assertEquals(1, a1.size());
        List<AlertRules.Alert> a2 = rules.evaluate(snap, 100, sw, Map.of());
        assertEquals(0, a2.size(), "同世代不重报");
        List<AlertRules.Alert> a3 = rules.evaluate(snap, 101, sw, Map.of());
        assertEquals(1, a3.size(), "新世代再报");
        assertEquals(2, rules.firedCount(), "总触发正好 2（两世代各 1）");
    }

    @Test
    @DisplayName("总开关关 / 子开关关 → 零触发")
    void masterAndSubSwitches() {
        AlertRules rules = new AlertRules();
        CompanyView hot = company("月港控股", 9.0);
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, List.of(hot), List.of(),
                WalletView.UNKNOWN, true);
        assertTrue(rules.evaluate(snap, 1, AlertRules.Switches.allOff(), Map.of()).isEmpty());
        assertTrue(rules.evaluate(snap, 1,
                new AlertRules.Switches(true, false, false, false), Map.of()).isEmpty());
    }

    @Test
    @DisplayName("地板规则：distance_pct < 1% 触发（真机待验证）")
    void floorRuleUsesDistancePct() {
        AlertRules rules = new AlertRules();
        CompanyView near = new CompanyView("月港控股", 56, 55, 42.38, "42.38", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API);
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, List.of(near), List.of(),
                WalletView.UNKNOWN, true);
        AlertRules.Switches sw = new AlertRules.Switches(true, false, true, false);
        List<AlertRules.Alert> alerts = rules.evaluate(snap, 1, sw, Map.of("月港控股", 0.90));
        assertEquals(1, alerts.size());
        assertEquals(AlertRules.Kind.FLOOR, alerts.get(0).kind());
        assertTrue(alerts.get(0).message().contains("距地板"));
        // 不在 map 里 → 不触发（未知 ≠ 安全，也不误报）
        assertTrue(rules.evaluate(snap, 2, sw, Map.of()).isEmpty());
        // ≥1% 不触发
        assertTrue(rules.evaluate(snap, 3, sw, Map.of("月港控股", 1.0)).isEmpty());
    }

    @Test
    @DisplayName("盈亏规则：|pnl| > 20%")
    void pnlRule() {
        AlertRules rules = new AlertRules();
        HoldingView h = new HoldingView("月港控股", 56, "", 10, 1500, 100);
        // profitPct = (1500-1000)/1000*100 = 50%
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, List.of(company("月港控股", 0)),
                List.of(h), WalletView.UNKNOWN, true);
        AlertRules.Switches sw = new AlertRules.Switches(true, false, false, true);
        assertEquals(1, rules.evaluate(snap, 1, sw, Map.of()).size());
    }

    @Test
    @DisplayName("已破产公司：CHANGE / FLOOR 都不报；交易中 |涨跌|>5% 仍报")
    void skipsBankruptChangeAndFloor() {
        AlertRules rules = new AlertRules();
        CompanyView dead = new CompanyView("WARpig", 1, 1, 0, "0.00", Double.NaN, "",
                -100, -100, CompanyView.STATUS_BANKRUPT, 5, 0, CompanyView.Source.API);
        CompanyView hot = company("月港控股", -6.2);
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, List.of(dead, hot), List.of(),
                WalletView.UNKNOWN, true);
        AlertRules.Switches sw = new AlertRules.Switches(true, true, true, false);
        List<AlertRules.Alert> alerts = rules.evaluate(snap, 1, sw, Map.of(
                "WARpig", 0.1,
                "月港控股", 2.0));
        assertEquals(1, alerts.size());
        assertEquals("月港控股", alerts.get(0).company());
        assertEquals(AlertRules.Kind.CHANGE, alerts.get(0).kind());
        assertTrue(rules.evaluate(snap, 1, sw, Map.of("WARpig", 0.1)).stream()
                .noneMatch(a -> "WARpig".equals(a.company())));
    }

    @Test
    @DisplayName("★ 提醒路径源码不许出现往服务器发包的 API")
    void sourceNeverSendsToServer() throws Exception {
        String alert = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/decision/AlertRules.java"));
        String bridge = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/LocalAlertBridge.java"));
        String boot = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/AlertBootstrap.java"));
        for (String src : List.of(alert, bridge, boot)) {
            assertFalse(src.contains(".sendChatMessage("));
            assertFalse(src.contains(".sendChatCommand("));
            assertFalse(src.contains(".sendMessage("));
            assertFalse(src.contains(".trySend"));
        }
        assertTrue(bridge.contains("getChatHud().addMessage"));
        assertFalse(alert.contains("import net.minecraft"));
    }

    private static CompanyView company(String name, double changePct) {
        return new CompanyView(name, 1, 1, 42, "42", Double.NaN, "",
                changePct, changePct, CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API);
    }
}
