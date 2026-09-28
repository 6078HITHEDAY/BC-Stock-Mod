package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorCache;
import cn.myflycat.bcstock.data.FloorSnapshot;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.data.WalletView;
import cn.myflycat.bcstock.decision.DecisionPreset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockHudTest {

    @AfterEach
    void resetShared() {
        FloorCache.SHARED.clearForTest();
        LocalAlertBridge.clearForTest();
        cn.myflycat.bcstock.data.BcStockSettings.setDecisionPresetForTest(DecisionPreset.DEFAULT);
    }

    @Test
    @DisplayName("★ 相位：整点 :03 的下一拍是 :18，跨过 :48 到下一小时 :03")
    void phases() {
        Instant justBefore03 = ZonedDateTime.of(2026, 9, 26, 10, 2, 59, 0, RefreshPhase.ZONE).toInstant();
        assertEquals("00:01", RefreshPhase.mmss(RefreshPhase.millisUntil(justBefore03)));

        Instant at03 = ZonedDateTime.of(2026, 9, 26, 10, 3, 0, 0, RefreshPhase.ZONE).toInstant();
        assertEquals("15:00", RefreshPhase.mmss(RefreshPhase.millisUntil(at03)));

        Instant at48 = ZonedDateTime.of(2026, 9, 26, 10, 48, 0, 0, RefreshPhase.ZONE).toInstant();
        Instant next = RefreshPhase.next(at48);
        assertEquals(ZonedDateTime.of(2026, 9, 26, 11, 3, 0, 0, RefreshPhase.ZONE).toInstant(), next);

        Instant tenSec = ZonedDateTime.of(2026, 9, 26, 10, 17, 50, 0, RefreshPhase.ZONE).toInstant();
        assertEquals("00:10", RefreshPhase.mmss(RefreshPhase.millisUntil(tenSec)));
        List<StockHud.HudLine> gold = StockHud.lines(StockSnapshot.empty(), tenSec);
        assertEquals(UiPalette.GOLD, gold.get(0).color());
    }

    @Test
    @DisplayName("★ 最多 5 行，超过折叠 +N 家；数字跟快照走")
    void foldAndNumbers() {
        List<CompanyView> companies = new ArrayList<>();
        List<HoldingView> holdings = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String name = "公司" + i;
            companies.add(new CompanyView(name, i, i, 10 + i, (10 + i) + "", Double.NaN, "",
                    1.5, 1.5, CompanyView.STATUS_TRADING, 1, 10, CompanyView.Source.API));
            holdings.add(new HoldingView(name, i, "", 2, 20 + i, 10));
        }
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, companies, holdings, WalletView.UNKNOWN, true);
        Instant now = ZonedDateTime.of(2026, 9, 26, 10, 3, 0, 0, RefreshPhase.ZONE).toInstant();
        List<StockHud.HudLine> lines = StockHud.lines(snap, now);
        assertEquals("下次刷新 15:00", lines.get(0).text());
        assertEquals(7, lines.size(), "倒计时 + 5 行 + 折叠");
        assertTrue(lines.get(1).text().startsWith("公司1"));
        assertTrue(lines.get(1).text().contains("11.00"));
        assertTrue(lines.get(1).text().contains("+1.50%"));
        assertEquals("+1 家", lines.get(6).text());
        assertFalse(lines.stream().anyMatch(l -> l.text().startsWith("公司6")));
    }

    @Test
    @DisplayName("★ 渲染源码只读快照，不发命令")
    void sourceDoesNotSend() throws IOException {
        String src = Files.readString(Path.of("src/main/java/cn/myflycat/bcstock/ui/StockHud.java"));
        assertTrue(src.contains("HudElementRegistry.attachElementBefore"));
        assertTrue(src.contains("SnapshotStore.SHARED.get()"));
        assertFalse(src.contains("trySend"));
        assertFalse(src.contains("sendChatCommand"));
        assertFalse(src.contains("CommandGateway"));
        assertFalse(src.contains("graphics.fill"));
        assertFalse(src.contains(".blit"));
        String client = Files.readString(Path.of("src/main/java/cn/myflycat/bcstock/BcStockClient.java"));
        assertTrue(client.contains("StockHud.register()"));
    }

    @Test
    @DisplayName("持仓行标卖/买；额度内未持仓第一条买另占一行")
    void adviceMarks() throws Exception {
        String body = new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/floors-sample.json"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        FloorCache.SHARED.putForTest(FloorSnapshot.parse(body), System.currentTimeMillis());

        CompanyView yue = new CompanyView("月港控股", 56, 55, 42.38, "42.38", Double.NaN, "",
                1.5, 1.5, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
        CompanyView para = new CompanyView("帕拉伦联合储蓄", 10, 10, 61.76, "61.76", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 1, 100, CompanyView.Source.API);
        HoldingView win = new HoldingView("月港控股", 56, "", 10, 160.0, 10.0);
        StockSnapshot snap = new StockSnapshot(Instant.EPOCH, List.of(yue, para), List.of(win),
                WalletView.UNKNOWN, true);
        Instant now = ZonedDateTime.of(2026, 9, 26, 10, 3, 0, 0, RefreshPhase.ZONE).toInstant();
        List<StockHud.HudLine> lines = StockHud.lines(snap, now);
        assertTrue(lines.get(1).text().contains("月港控股"));
        assertTrue(lines.get(1).text().endsWith("  卖"), lines.get(1).text());
        assertEquals("买 帕拉伦联合储蓄", lines.get(2).text());
    }
}
