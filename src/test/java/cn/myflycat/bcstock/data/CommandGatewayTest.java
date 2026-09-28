package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.data.reply.CmdSamples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关的节流是唯一防止我们变成刷屏怪的东西，必须有测试。
 */
class CommandGatewayTest {

    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        long get() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    private static final class RecordingSender implements CommandSender {
        final List<String> sent = new ArrayList<>();
        boolean allow = true;

        @Override
        public boolean send(String command) {
            if (!allow) {
                return false;
            }
            sent.add(command);
            return true;
        }
    }

    private static CommandGateway gateway(FakeClock clock, RecordingSender sender) {
        return new CommandGateway(clock::get, sender);
    }

    @Nested
    @DisplayName("★ 单飞 / 间隔 / 冷却")
    class Throttle {

        @Test
        @DisplayName("★ 同时只能飞一条：第二条当场被拒，sender 只收到第一条")
        void singleFlight() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBalance());
            assertTrue(g.inFlight());
            assertFalse(g.trySendPortfolio(), "单飞中不许再发");
            assertEquals(List.of("bal"), sender.sent);
        }

        @Test
        @DisplayName("★ 两条命令间隔不足 1 秒被拒")
        void minGap() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBalance());
            feed(g, CmdSamples.lines("bal.txt"));
            assertTrue(g.lastBalance().isPresent());
            assertFalse(g.inFlight());

            clock.advance(999);
            assertFalse(g.trySendPortfolio(), "999ms 不够 MIN_GAP");
            assertEquals(1, sender.sent.size());

            clock.advance(1);
            assertTrue(g.trySendPortfolio(), "满 1000ms 才放行");
            assertEquals(List.of("bal", "invest portfolio"), sender.sent);
        }

        @Test
        @DisplayName("★ 同一采集命令 15 分钟内不重复发")
        void collectCooldown() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBalance());
            feed(g, CmdSamples.lines("bal.txt"));
            clock.advance(CommandGateway.MIN_GAP_MS);
            assertFalse(g.trySendBalance(), "同一 /bal 15 分钟内不许再发");
            assertEquals(1, sender.sent.size());

            clock.advance(CommandGateway.COLLECT_COOLDOWN_MS);
            assertTrue(g.trySendBalance());
            assertEquals(2, sender.sent.size());
        }

        @Test
        @DisplayName("★ COMPANY_INFO 冷却按公司：55 之后 2 秒可以发 56，再发 55 被拒")
        void companyInfoCooldownIsPerId() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendCompanyInfo(55));
            feed(g, CmdSamples.lines("company-info-55.txt"));
            clock.advance(2_000);
            assertTrue(g.trySendCompanyInfo(56), "另一家公司的详情不是重发");
            feed(g, CmdSamples.lines("company-info-56.txt"));
            clock.advance(CommandGateway.MIN_GAP_MS);
            assertFalse(g.trySendCompanyInfo(55), "同一家 15 分钟内不许再查");
            assertEquals(List.of("invest company info 55", "invest company info 56"), sender.sent);
            assertTrue(g.trySendCompanyInfoLive(55), "下单查价绕过采集冷却");
            assertEquals(3, sender.sent.size());
        }
    }

    @Nested
    @DisplayName("★ 超时不重试")
    class Timeout {

        @Test
        @DisplayName("★ 5 秒没回执 → empty，并且不会自己再发一次")
        void timeoutReturnsEmptyAndDoesNotResend() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendPortfolio());
            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick();

            assertTrue(g.lastTimedOut());
            assertTrue(g.lastHoldings().isEmpty());
            assertFalse(g.inFlight());
            assertEquals(1, sender.sent.size(), "超时后绝不重试");
        }
    }

    @Nested
    @DisplayName("解析走网关")
    class ThroughGateway {

        @Test
        @DisplayName("灌入实测 /bal 行，lastBalance 是 45108.13")
        void balance() {
            FakeClock clock = new FakeClock();
            CommandGateway g = gateway(clock, new RecordingSender());
            assertTrue(g.trySendBalance());
            feed(g, CmdSamples.lines("bal.txt"));
            assertEquals(45108.13, g.lastBalance().orElseThrow().balance(), 1e-9);
        }

        @Test
        @DisplayName("灌入实测持仓块")
        void portfolio() {
            FakeClock clock = new FakeClock();
            CommandGateway g = gateway(clock, new RecordingSender());
            assertTrue(g.trySendPortfolio());
            feed(g, CmdSamples.lines("portfolio-held.txt"));
            assertEquals(1, g.lastHoldings().orElseThrow().size());
            assertEquals("月港控股", g.lastHoldings().orElseThrow().get(0).name());
        }

        @Test
        @DisplayName("块开始之前的系统消息不进 collected")
        void dropsNoiseBeforeBlockStart() {
            FakeClock clock = new FakeClock();
            CommandGateway g = gateway(clock, new RecordingSender());
            assertTrue(g.trySendPortfolio());
            g.onChatLine("* wsmhhh暂时离开了。");
            feed(g, CmdSamples.lines("portfolio-held.txt"));
            assertEquals("月港控股", g.lastHoldings().orElseThrow().get(0).name());
        }

        @Test
        @DisplayName("★ 请求 56 却收到 Id:55 的块 → 丢掉")
        void companyIdMismatchDropped() {
            FakeClock clock = new FakeClock();
            CommandGateway g = gateway(clock, new RecordingSender());
            assertTrue(g.trySendCompanyInfo(56));
            feed(g, CmdSamples.lines("company-info-55.txt"));
            assertTrue(g.lastCompany().isEmpty());
            assertFalse(g.inFlight());
        }

        @Test
        @DisplayName("sendChatCommand 不带前导 /")
        void commandHasNoLeadingSlash() {
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(new FakeClock(), sender);
            assertTrue(g.trySendCompanyInfo(56));
            assertEquals("invest company info 56", sender.sent.get(0));
        }

        @Test
        @DisplayName("带前导 / 的命令直接拒")
        void leadingSlashRejected() {
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(new FakeClock(), sender);
            assertFalse(g.trySend(CommandGateway.Kind.BAL, "/bal", -1));
            assertTrue(sender.sent.isEmpty());
        }

        @Test
        @DisplayName("sender 发不出去 → 不进单飞、不记冷却")
        void senderFailureDoesNotStartFlight() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            sender.allow = false;
            CommandGateway g = gateway(clock, sender);
            assertFalse(g.trySendBalance());
            assertFalse(g.inFlight());
            sender.allow = true;
            assertTrue(g.trySendBalance(), "没真正发出去，冷却不该生效");
        }

        @Test
        @DisplayName("灌入 companies 块 → lastCompanies 3 家；绕过采集冷却")
        void companiesParseAndBypassCooldown() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);
            assertTrue(g.trySendCompanies());
            assertEquals("invest companies", sender.sent.get(0));
            feed(g, CmdSamples.lines("companies.txt"));
            assertEquals(3, g.lastCompanies().orElseThrow().size());
            assertEquals("联邦健保", g.lastCompanies().orElseThrow().get(0).name());

            clock.advance(CommandGateway.MIN_GAP_MS);
            assertTrue(g.trySendCompanies(), "COMPANIES 不走 15 分钟采集冷却");
            assertEquals(2, sender.sent.size());
        }

        @Test
        @DisplayName("companies 5s 不超时，15s 才超时")
        void companiesTimeoutIsFifteenSeconds() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);
            assertTrue(g.trySendCompanies());
            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick();
            assertTrue(g.inFlight(), "5s 对 160+ 行不够");
            clock.advance(CommandGateway.COMPANIES_TIMEOUT_MS - CommandGateway.TIMEOUT_MS);
            g.tick();
            assertTrue(g.lastTimedOut());
            assertFalse(g.inFlight());
            assertTrue(g.lastCompanies().isEmpty());
        }

        @Test
        @DisplayName("刷新广播不进当前命令 collected，且不在飞也会通知")
        void marketUpdatedNotCollected() {
            FakeClock clock = new FakeClock();
            CommandGateway g = gateway(clock, new RecordingSender());
            int[] hits = {0};
            g.setOnMarketUpdated(() -> hits[0]++);
            g.onChatLine("帕拉伦股市 > 所有商业股票已更新。");
            assertEquals(1, hits[0]);

            assertTrue(g.trySendPortfolio());
            g.onChatLine("帕拉伦股市 > 所有商业股票已更新。");
            feed(g, CmdSamples.lines("portfolio-held.txt"));
            assertEquals("月港控股", g.lastHoldings().orElseThrow().get(0).name());
            assertEquals(2, hits[0]);
        }
    }

    @Nested
    @DisplayName("★ A5 下单：超时不重发 / 超上限拒 / 不套采集冷却")
    class Trade {

        @Test
        @DisplayName("★ 买卖超时只标未确认，绝不重发同一条 buy")
        void timeoutNeverResendsBuy() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBuy(56, 1));
            assertEquals(List.of("invest buy 56 1"), sender.sent);

            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick();

            assertTrue(g.lastTrade().isPresent());
            assertFalse(g.lastTrade().get().confirmed(), "成功格式从未实测，不许标确认");
            assertFalse(g.lastTrade().get().error());
            long buyCount = sender.sent.stream().filter(s -> s.startsWith("invest buy")).count();
            assertEquals(1, buyCount, "超时后绝不重发 buy（对账 portfolio 不算重发）");

            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick();
            clock.advance(CommandGateway.MIN_GAP_MS);
            g.tick();
            buyCount = sender.sent.stream().filter(s -> s.startsWith("invest buy")).count();
            assertEquals(1, buyCount, "后续 tick 也不许自己再买一次");
        }

        @Test
        @DisplayName("★ 买入超过 MAX_CUSTOM_QTY / 编号非法 → 拒；自定义档放行")
        void overLimitRejected() {
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(new FakeClock(), sender);
            assertFalse(g.trySendBuy(56, TradeSettings.MAX_CUSTOM_QTY + 1));
            assertFalse(g.trySendBuy(56, 0));
            assertFalse(g.trySendBuy(-1, 1));
            assertTrue(sender.sent.isEmpty());
            assertTrue(g.lastTrade().isEmpty());
            assertTrue(g.trySendBuy(56, 2));
            assertEquals(List.of("invest buy 56 2"), sender.sent);
        }

        @Test
        @DisplayName("买卖不走 15 分钟采集冷却，间隔满 1 秒就能再发")
        void tradeSkipsCollectCooldown() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBuy(56, 1));
            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick(); // 买卖超时；可能已触发对账 portfolio
            // 排空对账：portfolio 超时 → 可能再发 bal → 再超时
            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick();
            clock.advance(CommandGateway.MIN_GAP_MS);
            g.tick();
            clock.advance(CommandGateway.TIMEOUT_MS);
            g.tick();
            clock.advance(CommandGateway.MIN_GAP_MS);
            assertTrue(g.trySendBuy(56, 10), "买卖不许套 /bal 那条 15 分钟冷却");
            assertEquals("invest buy 56 10", sender.sent.get(sender.sent.size() - 1));
        }

        @Test
        @DisplayName("发完 3 秒后自动对账 portfolio，不是重发 buy")
        void reconcilePortfolioAfterTrade() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBuy(56, 1));
            g.onChatLine("从未实测的成功样子");
            clock.advance(CommandGateway.SETTLE_MS);
            g.tick();
            assertEquals(1, sender.sent.size());
            clock.advance(CommandGateway.RECONCILE_DELAY_MS - CommandGateway.SETTLE_MS);
            g.tick();
            assertTrue(sender.sent.contains("invest portfolio"));
            assertEquals(1, sender.sent.stream().filter(s -> s.startsWith("invest buy")).count());
        }

        @Test
        @DisplayName("对账 portfolio 绕过 15 分钟采集冷却（开盘已拉过仍能对账）")
        void reconcileBypassesPortfolioCooldown() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendPortfolio());
            feed(g, CmdSamples.lines("portfolio-empty.txt"));
            clock.advance(CommandGateway.MIN_GAP_MS);
            assertFalse(g.trySendPortfolio(), "普通采集仍受冷却");

            assertTrue(g.trySendSell(56, 1));
            g.onChatLine("从未实测的成功样子");
            clock.advance(CommandGateway.SETTLE_MS);
            g.tick();
            clock.advance(CommandGateway.RECONCILE_DELAY_MS - CommandGateway.SETTLE_MS);
            g.tick();
            long portfolios = sender.sent.stream().filter(s -> s.equals("invest portfolio")).count();
            assertEquals(2, portfolios, "对账必须再发一次 portfolio（免冷却）");
        }

        @Test
        @DisplayName("对账遇 MIN_GAP 不取消预约，稍后重试成功")
        void reconcileRetriesAfterMingap() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendSell(56, 1));
            g.onChatLine("从未实测的成功样子");
            clock.advance(CommandGateway.SETTLE_MS);
            g.tick();

            // 推到对账前 500ms 再发 bal，占住 lastAnySentAt
            clock.advance(CommandGateway.RECONCILE_DELAY_MS - CommandGateway.SETTLE_MS - 500L);
            assertTrue(g.trySendBalance());
            feed(g, CmdSamples.lines("bal.txt"));
            assertEquals(0, sender.sent.stream().filter(s -> s.equals("invest portfolio")).count());

            clock.advance(500L); // 正好到对账点，但距 bal 仅 500ms < MIN_GAP
            g.tick();
            assertEquals(0, sender.sent.stream().filter(s -> s.equals("invest portfolio")).count(),
                    "刚发完 bal 时对账应因 MIN_GAP 暂缓");
            assertTrue(g.reconcilePendingForTest(), "预约不得被清掉");

            clock.advance(CommandGateway.MIN_GAP_MS);
            g.tick();
            assertTrue(sender.sent.contains("invest portfolio"), "间隔满后应对账发出");
        }

        @Test
        @DisplayName("对账收到「您没有任何股票」→ lastHoldings 空列表（清 HUD）")
        void reconcileNoHoldingsClearsLastHoldings() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendSell(56, 1));
            g.onChatLine("帕拉伦股市 > 您没有足够的股票。");
            assertFalse(g.inFlight());
            clock.advance(CommandGateway.RECONCILE_DELAY_MS);
            g.tick();
            assertTrue(sender.sent.contains("invest portfolio"));
            g.onChatLine("帕拉伦股市 > 您没有任何股票。");
            assertTrue(g.lastHoldings().isPresent(), "必须是确认空仓，不是 unknown");
            assertTrue(g.lastHoldings().get().isEmpty());
        }

        @Test
        @DisplayName("命令串用 market_id，不是 apiId")
        void commandUsesMarketId() {
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(new FakeClock(), sender);
            assertTrue(g.trySendBuy(56, 10));
            assertEquals("invest buy 56 10", sender.sent.get(0));
            assertFalse(sender.sent.get(0).contains(" 55 "));
        }

        @Test
        @DisplayName("company info 刚回来：不足 1s 的 buy 立刻发出，不排队死等")
        void buyRightAfterQuoteSkipsMinGap() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);
            assertTrue(g.trySendCompanyInfoLive(56));
            feed(g, CmdSamples.lines("company-info-56.txt"));
            assertFalse(g.inFlight());
            assertTrue(g.trySendBuy(56, 10));
            assertTrue(sender.sent.contains("invest buy 56 10"), "查价后立刻买单必须发出");
            assertEquals(2, sender.sent.size());
        }

        @Test
        @DisplayName("间隔未满且不是刚查价：买单先排队，tick 后发出")
        void buyQueuesOnMinGapThenFlushes() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);
            assertTrue(g.trySendBalance());
            feed(g, CmdSamples.lines("bal.txt"));
            assertTrue(g.trySendBuy(56, 1), "排队也算接受");
            assertFalse(sender.sent.contains("invest buy 56 1"));
            assertTrue(g.userTradeBusy());
            clock.advance(CommandGateway.MIN_GAP_MS);
            g.tick();
            assertTrue(sender.sent.contains("invest buy 56 1"));
        }

        @Test
        @DisplayName("已实测错误前缀 → error；其它回执一律未确认")
        void knownErrorOnlyElseUnconfirmed() {
            FakeClock clock = new FakeClock();
            RecordingSender sender = new RecordingSender();
            CommandGateway g = gateway(clock, sender);

            assertTrue(g.trySendBuy(56, 1));
            g.onChatLine("帕拉伦股市 > 您不能购买已破产公司的股票。");
            assertTrue(g.lastTrade().isPresent());
            assertTrue(g.lastTrade().get().error());
            assertFalse(g.lastTrade().get().confirmed());
            assertFalse(g.inFlight());

            clock.advance(CommandGateway.MIN_GAP_MS);
            assertTrue(g.trySendSell(56, 1));
            g.onChatLine("随便一行从未实测的成功样子");
            clock.advance(CommandGateway.SETTLE_MS);
            g.tick();
            assertTrue(g.lastTrade().isPresent());
            assertFalse(g.lastTrade().get().error());
            assertFalse(g.lastTrade().get().confirmed(), "不许编成功格式");
            assertTrue(g.lastTrade().get().message().contains("未确认"));
        }
    }

    @Test
    @DisplayName("★ 连续 N 次拒绝：日志条数有上界（不是 N）")
    void rejectLogsAreRateLimited() {
        FakeClock clock = new FakeClock();
        RecordingSender sender = new RecordingSender();
        sender.allow = false;
        CommandGateway g = gateway(clock, sender);
        g.resetRejectLogCountForTest();
        int n = 200;
        for (int i = 0; i < n; i++) {
            assertFalse(g.trySendBalance());
        }
        assertTrue(g.rejectLogCountForTest() < n, "日志条数必须 < N");
        assertEquals(1, g.rejectLogCountForTest(), "同因同窗口只打 1 行");
        clock.advance(CommandGateway.REJECT_LOG_MIN_GAP_MS);
        assertFalse(g.trySendBalance());
        assertEquals(2, g.rejectLogCountForTest(), "间隔过后允许再打一行");
    }

    private static void feed(CommandGateway g, List<String> lines) {
        for (String line : lines) {
            g.onChatLine(line);
        }
        g.tick();
    }
}
