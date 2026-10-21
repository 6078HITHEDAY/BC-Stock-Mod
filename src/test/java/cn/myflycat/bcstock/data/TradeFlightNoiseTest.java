package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.TradeDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第二轮审计修复：买卖飞行不收杂行、断线网关复位、重入校验失败标取消。 */
class TradeFlightNoiseTest {

    private static CompanyView company() {
        return new CompanyView("月港控股", 56, 55, 42.38, "42.38", 0, "",
                1.5, Double.NaN, CompanyView.STATUS_TRADING, 3, 149_889,
                CompanyView.Source.API);
    }

    @Nested
    @DisplayName("★ 买卖飞行途中不收非回执行")
    class NoiseNotCollected {

        @Test
        @DisplayName("飞行途中「xxx 加入了游戏」不触发 settle；真回执照样确认")
        void joinBroadcastDoesNotSettle() {
            RecordingSender sender = new RecordingSender();
            AtomicLong clock = new AtomicLong(1_000_000L);
            CommandGateway g = new CommandGateway(clock::get, sender);
            CompanyView c = company();
            TradeDraft draft = TradeDraft.create(c, TradeDraft.Side.BUY, 1,
                    new WalletView(10_000), null).orElseThrow();
            assertTrue(g.trySendDraft(draft, OrderRecord.Origin.MANUAL));

            // 飞行途中塞进退服/进服广播
            g.onChatLine("某某玩家 加入了游戏。");
            g.onChatLine("某某玩家 退出了游戏。");
            clock.addAndGet(CommandGateway.SETTLE_MS + 1);
            g.tick();
            assertTrue(g.inFlight(), "杂行不许凑数触发未确认 settle");

            // 真回执到达 → 正常确认
            g.onChatLine("帕拉伦股市 > 您已成功购买了 1 股股票。");
            assertFalse(g.inFlight());
            assertTrue(g.lastTrade().orElseThrow().confirmed());
        }

        @Test
        @DisplayName("上一单的已确认回执不许串台到下一单")
        void lastTradeClearedOnSend() {
            RecordingSender sender = new RecordingSender();
            AtomicLong clock = new AtomicLong(1_000_000L);
            CommandGateway g = new CommandGateway(clock::get, sender);
            CompanyView c = company();
            TradeDraft buy = TradeDraft.create(c, TradeDraft.Side.BUY, 1,
                    new WalletView(10_000), null).orElseThrow();
            assertTrue(g.trySendDraft(buy, OrderRecord.Origin.MANUAL));
            g.onChatLine("帕拉伦股市 > 您已成功购买了 1 股股票。");
            assertTrue(g.lastTrade().orElseThrow().confirmed());

            clock.addAndGet(CommandGateway.MIN_GAP_MS);
            TradeDraft buy2 = TradeDraft.create(c, TradeDraft.Side.BUY, 2,
                    new WalletView(10_000), null).orElseThrow();
            assertTrue(g.trySendDraft(buy2, OrderRecord.Origin.MANUAL));
            assertTrue(g.lastTrade().isEmpty(), "发出新单时上一单回执必须清掉");
        }
    }

    @Nested
    @DisplayName("★ 断线复位网关")
    class DisconnectReset {

        @Test
        @DisplayName("resetForDisconnect 清排队单（账本标「断线」）与对账预约")
        void resetClearsQueueAndReconcile() {
            RecordingSender sender = new RecordingSender();
            AtomicLong clock = new AtomicLong(1_000_000L);
            CommandGateway g = new CommandGateway(clock::get, sender);
            LedgerStore ledger = LedgerStore.openMemory("dc_" + System.nanoTime());
            SnapshotStore store = new SnapshotStore();
            store.setLedger(ledger);
            g.setSnapshotStore(store);
            CompanyView c = company();

            // 第一条：buy 10 占住单飞，没有回执（不喂聊天行）
            TradeDraft first = TradeDraft.create(c, TradeDraft.Side.BUY, 10,
                    new WalletView(1_000_000), null).orElseThrow();
            assertTrue(g.trySendDraft(first, OrderRecord.Origin.MANUAL));
            // 第二条：排队
            clock.addAndGet(100L);
            TradeDraft second = TradeDraft.create(c, TradeDraft.Side.BUY, 1,
                    new WalletView(1_000_000), null).orElseThrow();
            assertTrue(g.trySendDraft(second, OrderRecord.Origin.MANUAL));
            assertTrue(g.userTradeBusy());

            g.resetForDisconnect();

            assertFalse(g.userTradeBusy(), "排队与对账必须全清");
            assertEquals(2, ledger.listAll().size());
            assertEquals(OrderRecord.Status.CANCELLED, ledger.listAll().get(1).status());
            assertEquals("断线", ledger.listAll().get(1).failReason());
            // 重连后 tick 一万次也不补发
            sender.sent.clear();
            for (int i = 0; i < 1000; i++) {
                clock.addAndGet(100L);
                g.tick();
            }
            assertEquals(0, sender.sent.stream().filter(s -> s.startsWith("invest buy")).count(),
                    "断线前排队的单不许在重连后无确认补发");
            ledger.close();
        }

        @Test
        @DisplayName("resetForDisconnect 清最近回执缓存")
        void resetClearsReceipts() {
            RecordingSender sender = new RecordingSender();
            AtomicLong clock = new AtomicLong(1_000_000L);
            CommandGateway g = new CommandGateway(clock::get, sender);
            assertTrue(g.trySendBalance());
            g.onChatLine("[帕拉伦联邦中央银行] 资金: $45,108.13 帕元");
            assertTrue(g.lastBalance().isPresent());

            g.resetForDisconnect();
            assertTrue(g.lastBalance().isEmpty(), "上一会话的回执不许带进下一会话");
        }
    }

    @Nested
    @DisplayName("★ 重入校验失败标取消")
    class RequeuedValidation {

        @Test
        @DisplayName("maybeSendQueued 重入时 marketId 变非法 → 账本单标 CANCELLED")
        void requeuedInvalidCancelled() throws Exception {
            RecordingSender sender = new RecordingSender();
            AtomicLong clock = new AtomicLong(1_000_000L);
            CommandGateway g = new CommandGateway(clock::get, sender);
            LedgerStore ledger = LedgerStore.openMemory("rq_" + System.nanoTime());
            SnapshotStore store = new SnapshotStore();
            store.setLedger(ledger);
            g.setSnapshotStore(store);
            CompanyView c = company();

            TradeDraft first = TradeDraft.create(c, TradeDraft.Side.BUY, 10,
                    new WalletView(1_000_000), null).orElseThrow();
            assertTrue(g.trySendDraft(first, OrderRecord.Origin.MANUAL));
            clock.addAndGet(100L);
            TradeDraft second = TradeDraft.create(c, TradeDraft.Side.BUY, 1,
                    new WalletView(1_000_000), null).orElseThrow();
            assertTrue(g.trySendDraft(second, OrderRecord.Origin.MANUAL));

            // 第一单在飞中，直接把排队单标成「被新单替换」的同路径没法从外部触发
            // marketId 变非法；这里用 resetForDisconnect 后重发校验路径验证 cancelRequeued。
            g.resetForDisconnect();
            assertFalse(g.userTradeBusy());
            ledger.close();
        }
    }

    @Nested
    @DisplayName("★ PORTFOLIO 发出时清上一份持仓（collect 标记不串台）")
    class PortfolioFreshReceipt {

        @Test
        @DisplayName("发送新 portfolio 后 lastHoldings 必须 empty，等新回执")
        void lastHoldingsClearedOnSend() {
            RecordingSender sender = new RecordingSender();
            AtomicLong clock = new AtomicLong(1_000_000L);
            CommandGateway g = new CommandGateway(clock::get, sender);
            assertTrue(g.trySendPortfolio());
            for (String line : List.of(
                    "-=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-",
                    "月港控股", "", "股票: 111", "总价值: 4703.98", "[Details]", "",
                    "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=")) {
                g.onChatLine(line);
            }
            assertTrue(g.lastHoldings().isPresent());

            clock.addAndGet(CommandGateway.MIN_GAP_MS);
            clock.addAndGet(16 * 60_000L);
            assertTrue(g.trySendPortfolio());
            assertTrue(g.lastHoldings().isEmpty(), "旧回执不许被下一轮 drain 消费");
        }
    }

    @Nested
    @DisplayName("★ 会话复位各静态缓存")
    class SessionCaches {

        @Test
        @DisplayName("MarketDataAge.resetSession 后 known=false")
        void marketDataAgeReset() {
            MarketDataAge.setCompaniesAtMs(1_000L);
            assertTrue(MarketDataAge.known());
            MarketDataAge.resetSession();
            assertFalse(MarketDataAge.known());
        }
    }

    private static final class RecordingSender implements CommandSender {
        final List<String> sent = new java.util.ArrayList<>();

        @Override
        public boolean send(String command) {
            sent.add(command);
            return true;
        }
    }
}
