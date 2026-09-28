package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.TradeDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeOrderFlowTest {

    @AfterEach
    void tearDown() {
        TradeSettings.setEnabledForTest(false);
        AutoTradeSettings.resetForTest();
    }

    @Test
    @DisplayName("先写 Pending，聊天成功才改持仓；失败不改")
    void chatOkThenLedgerApply() {
        RecordingSender sender = new RecordingSender();
        AtomicLong clock = new AtomicLong(1_000_000L);
        CommandGateway g = new CommandGateway(clock::get, sender);
        LedgerStore ledger = LedgerStore.openMemory("order_" + System.nanoTime());
        SnapshotStore store = new SnapshotStore();
        Instant at = Instant.EPOCH;
        CompanyView c = company();
        store.updateCompanies(List.of(c), true, at);
        store.updateHoldings(List.of(), at);
        store.updateWallet(new WalletView(10_000.0), at);
        store.setLedger(ledger);
        g.setSnapshotStore(store);

        TradeDraft draft = TradeDraft.create(c, TradeDraft.Side.BUY, 2, store.get().wallet(), null).orElseThrow();
        assertTrue(g.trySendDraft(draft, OrderRecord.Origin.MANUAL));
        assertEquals(List.of("invest buy 56 2"), sender.sent);
        OrderRecord pendingOrSent = ledger.listAll().get(0);
        assertEquals(OrderRecord.Status.SENT, pendingOrSent.status());
        assertTrue(store.get().holdingsOrEmpty().isEmpty()
                || store.get().holdingsOrEmpty().stream().noneMatch(h -> h.shares() > 0),
                "回执前不改持仓");

        g.onChatLine("帕拉伦股市 > 您已成功购买了 2 股股票。");
        assertEquals(OrderRecord.Status.CHAT_OK, ledger.listAll().get(0).status());
        assertEquals(2, store.get().holdingOf("月港控股").shares());

        clock.addAndGet(CommandGateway.MIN_GAP_MS);
        TradeDraft sell = TradeDraft.create(c, TradeDraft.Side.SELL, 1,
                store.get().wallet(), store.get().holdingOf("月港控股")).orElseThrow();
        assertTrue(g.trySendDraft(sell, OrderRecord.Origin.MANUAL));
        g.onChatLine("帕拉伦股市 > 您不能购买已破产公司的股票。");
        assertEquals(2, store.get().holdingOf("月港控股").shares(), "失败回执不改持仓");
        assertEquals(OrderRecord.Status.CHAT_FAIL, ledger.listAll().get(1).status());
        ledger.close();
    }

    @Test
    @DisplayName("超时标 UNCONFIRMED，不重发，不改持仓")
    void timeoutUnconfirmedNoResend() {
        RecordingSender sender = new RecordingSender();
        AtomicLong clock = new AtomicLong(1_000_000L);
        CommandGateway g = new CommandGateway(clock::get, sender);
        LedgerStore ledger = LedgerStore.openMemory("to_" + System.nanoTime());
        SnapshotStore store = new SnapshotStore();
        store.setLedger(ledger);
        g.setSnapshotStore(store);
        CompanyView c = company();
        store.updateCompanies(List.of(c), true, Instant.EPOCH);
        store.updateWallet(new WalletView(10_000.0), Instant.EPOCH);
        TradeDraft draft = TradeDraft.create(c, TradeDraft.Side.BUY, 1, store.get().wallet(), null).orElseThrow();
        assertTrue(g.trySendDraft(draft, OrderRecord.Origin.MANUAL));
        clock.addAndGet(CommandGateway.TIMEOUT_MS);
        g.tick();
        assertEquals(OrderRecord.Status.UNCONFIRMED, ledger.listAll().get(0).status());
        assertEquals(1, sender.sent.stream().filter(s -> s.startsWith("invest buy")).count());
        assertTrue(store.get().holdingsOrEmpty().stream().noneMatch(HoldingView::held));
        ledger.close();
    }

    private static CompanyView company() {
        return new CompanyView("月港控股", 56, 56, 42.38, "42.38", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
    }

    private static final class RecordingSender implements CommandSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String command) {
            sent.add(command);
            return true;
        }
    }
}
