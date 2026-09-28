package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.data.reply.CmdSamples;
import cn.myflycat.bcstock.ui.StockHud;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 买卖 + 对账 + JSONL 读写全链路（离线，对照真机日志）。
 */
class TradeReconcileFlowDebugTest {

    @Test
    @DisplayName("卖空失败→空仓短回执→drain→HUD 清幽灵持仓")
    void emptyPortfolioShortReplyClearsHud() {
        AtomicLong now = new AtomicLong(1_000_000L);
        java.util.ArrayList<String> sent = new java.util.ArrayList<>();
        CommandGateway g = new CommandGateway(now::get, cmd -> {
            sent.add(cmd);
            return true;
        });
        SnapshotStore store = new SnapshotStore();
        HoldingView ghost = new HoldingView(
                "月港控股", 56, "", 111L, 4703.98, Double.NaN);
        store.updateHoldings(List.of(ghost), Instant.EPOCH);
        assertEquals(1, store.get().heldOnly().size());

        assertTrue(g.trySendSell(56, 111));
        g.onChatLine("帕拉伦股市 > 您没有足够的股票。");
        assertTrue(g.lastTrade().isPresent());
        assertTrue(g.lastTrade().get().error());

        now.addAndGet(CommandGateway.RECONCILE_DELAY_MS);
        g.tick();
        assertTrue(sent.contains("invest portfolio"));
        g.onChatLine("帕拉伦股市 > 您没有任何股票。");

        assertTrue(g.lastHoldings().isPresent());
        assertTrue(g.lastHoldings().get().isEmpty());

        BoardOpenPull.drainToStore(g, store, Instant.EPOCH);
        assertEquals(0, store.get().heldOnly().size(), "drain 后 heldOnly 必须为 0");
        assertTrue(store.get().holdingsKnown());
        assertTrue(store.get().holdingsOrEmpty().isEmpty());

        int hudHeld = (int) StockHud.lines(store.get(), Instant.EPOCH).stream()
                .filter(l -> l.text().contains("月港控股"))
                .count();
        assertEquals(0, hudHeld, "HUD 不许再画月港控股");
    }

    @Test
    @DisplayName("买入成功→对账 portfolio/bal→drain→JSONL 读写一致")
    void buyReconcileWritesHoldingsAndWalletToJsonl(@TempDir Path dir) {
        AtomicLong now = new AtomicLong(2_000_000L);
        java.util.ArrayList<String> sent = new java.util.ArrayList<>();
        CommandGateway g = new CommandGateway(now::get, cmd -> {
            sent.add(cmd);
            return true;
        });
        Path jsonl = dir.resolve("snapshots.jsonl");
        SnapshotJournal journal = new SnapshotJournal(jsonl.toFile());
        SnapshotStore store = new SnapshotStore();
        store.setJournal(journal);

        assertTrue(g.trySendBuy(49, 1));
        g.onChatLine("帕拉伦股市 > 您已成功购买了 1 股股票。");
        assertTrue(g.lastTrade().isPresent());
        assertFalse(g.lastTrade().get().error());
        assertTrue(g.lastTrade().get().confirmed(), "真机成功文案必须 confirmed");

        now.addAndGet(Math.max(CommandGateway.RECONCILE_DELAY_MS, CommandGateway.MIN_GAP_MS));
        g.tick();
        assertTrue(sent.contains("invest portfolio"), "应对账发 portfolio");
        for (String line : CmdSamples.lines("portfolio-held.txt")) {
            g.onChatLine(line);
        }
        assertTrue(g.lastHoldings().isPresent());
        assertEquals(1, g.lastHoldings().get().stream().filter(HoldingView::held).count());

        now.addAndGet(CommandGateway.MIN_GAP_MS);
        g.tick();
        assertTrue(sent.contains("bal"), "应对账发 bal");
        g.onChatLine(CmdSamples.firstLine("bal.txt"));
        assertTrue(g.lastBalance().isPresent());

        BoardOpenPull.drainToStore(g, store, Instant.EPOCH);
        assertEquals(1, store.get().heldOnly().size());
        assertEquals("月港控股", store.get().heldOnly().get(0).name());
        assertTrue(store.get().wallet().known());

        assertTrue(Files.isRegularFile(jsonl));
        SnapshotJournal reader = new SnapshotJournal(jsonl.toFile());
        StockSnapshot loaded = reader.last().orElseThrow();
        assertTrue(loaded.holdingsKnown());
        assertEquals(1, loaded.heldOnly().size());
        assertEquals("月港控股", loaded.heldOnly().get(0).name());
        assertEquals(111, loaded.heldOnly().get(0).shares());
        assertTrue(loaded.wallet().known());

        int hudHeld = (int) StockHud.lines(store.get(), Instant.EPOCH).stream()
                .filter(l -> l.text().contains("月港控股"))
                .count();
        assertEquals(1, hudHeld);
    }
}
