package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.CommandSender;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFlowTest {

    @AfterEach
    void resetSwitch() {
        TradeSettings.setEnabledForTest(false);
        AutoTradeSettings.resetForTest();
    }

    @Test
    @DisplayName("开关默认关：合法草稿也不进确认框")
    void defaultDisabled() {
        assertFalse(TradeSettings.enabled());
        TradeFlow flow = new TradeFlow();
        assertFalse(flow.tryOpen(live(), TradeDraft.Side.BUY, 1, rich(), null));
        assertTrue(flow.pending().isEmpty());
    }

    @Test
    @DisplayName("★ 确认框取消则一个字节都不发")
    void cancelSendsNothing() {
        TradeSettings.setEnabledForTest(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(new AtomicLong(1_000_000L)::get, sender);
        TradeFlow flow = new TradeFlow();
        assertTrue(flow.tryOpen(live(), TradeDraft.Side.BUY, 10, rich(), null));
        assertTrue(flow.pending().isPresent());
        flow.cancel();
        assertFalse(flow.confirm(g));
        assertTrue(sender.sent.isEmpty(), "Esc 取消不许发出任何命令");
        assertTrue(flow.pending().isEmpty());
    }

    @Test
    @DisplayName("回车才发一条 invest buy {market_id} {qty}")
    void confirmSendsOnce() {
        TradeSettings.setEnabledForTest(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(new AtomicLong(1_000_000L)::get, sender);
        TradeFlow flow = new TradeFlow();
        assertTrue(flow.tryOpen(live(), TradeDraft.Side.BUY, 100, rich(), null));
        assertTrue(flow.confirm(g));
        assertEquals(List.of("invest buy 56 100"), sender.sent);
        assertTrue(flow.pending().isPresent(), "等回执前草稿还在");
        assertEquals(TradeFlow.Phase.SUBMITTING, flow.phase());
        assertFalse(flow.shouldClose());
    }

    @Test
    @DisplayName("★ 卖出入口：确认后发 invest sell")
    void confirmSell() {
        TradeSettings.setEnabledForTest(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(new AtomicLong(1_000_000L)::get, sender);
        TradeFlow flow = new TradeFlow();
        HoldingView held = new HoldingView("月港控股", 56, "", 10, 423.80, Double.NaN);
        assertTrue(flow.tryOpen(live(), TradeDraft.Side.SELL, 10, rich(), held));
        assertTrue(flow.confirm(g));
        assertEquals(List.of("invest sell 56 10"), sender.sent);
    }

    @Test
    @DisplayName("查价后不足 1s 点确认必须发出 buy，sent=false 不得当成交关")
    void confirmAfterLiveQuoteSendsWithinMinGap() {
        TradeSettings.setEnabledForTest(true);
        AtomicLong now = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(now::get, sender);
        LiveQuoteFlow q = new LiveQuoteFlow(now::get);
        q.tick(g, 56);
        for (String line : cn.myflycat.bcstock.data.reply.CmdSamples.lines("company-info-56.txt")) {
            g.onChatLine(line);
        }
        q.tick(g, 56);
        assertEquals(LiveQuoteFlow.Phase.READY, q.phase());

        TradeFlow flow = new TradeFlow();
        assertTrue(flow.tryOpen(live(), TradeDraft.Side.BUY, 10, rich(), null));
        assertTrue(flow.confirm(g));
        assertTrue(sender.sent.contains("invest buy 56 10"), "查价后立刻确认必须发出买单");
        assertEquals(TradeFlow.Phase.SUBMITTING, flow.phase());
        assertFalse(flow.shouldClose(), "sent 之后等回执，不得关");

        g.onChatLine("帕拉伦股市 > 您已成功购买了 10 股股票。");
        flow.tick(g);
        assertEquals(TradeFlow.Phase.FILLED, flow.phase());
        assertTrue(flow.shouldClose());
    }

    @Test
    @DisplayName("服务端拒绝：确认框 FAILED，shouldClose=false")
    void serverRejectStaysOpen() {
        TradeSettings.setEnabledForTest(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(new AtomicLong(1_000_000L)::get, sender);
        TradeFlow flow = new TradeFlow();
        assertTrue(flow.tryOpen(live(), TradeDraft.Side.BUY, 1, rich(), null));
        assertTrue(flow.confirm(g));
        g.onChatLine("帕拉伦股市 > 您不能购买已破产公司的股票。");
        flow.tick(g);
        assertEquals(TradeFlow.Phase.FAILED, flow.phase());
        assertFalse(flow.shouldClose());
        assertTrue(flow.failReason().contains("破产"));
    }

    @Test
    @DisplayName("★ auto.kill=true 时确认不发命令")
    void killBlocksConfirm() {
        TradeSettings.setEnabledForTest(true);
        AutoTradeSettings.setKillForTest(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(new AtomicLong(1_000_000L)::get, sender);
        TradeFlow flow = new TradeFlow();
        assertTrue(flow.tryOpen(live(), TradeDraft.Side.BUY, 1, rich(), null));
        assertFalse(flow.confirm(g));
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    @DisplayName("确认框含交税行：公司/编号/单价×数量/交税/余额/回车 Esc")
    void confirmCopy() {
        TradeDraft d = TradeDraft.create(live(), TradeDraft.Side.BUY, 10, rich(), null).orElseThrow();
        List<String> lines = TradeConfirmDialog.lines(d, LiveQuoteFlow.Phase.FAILED);
        assertEquals("确认买入", lines.get(0));
        assertTrue(lines.get(1).contains("月港控股"));
        assertTrue(lines.get(1).contains("#56"));
        assertTrue(lines.get(2).contains("42.38"));
        assertTrue(lines.get(2).contains("10"));
        assertTrue(lines.get(2).contains("423.80"));
        assertEquals("交税 --", lines.get(3));
        assertTrue(lines.get(4).contains("10000.00") || lines.get(4).contains("10000"));
        assertEquals("回车确认　Esc 取消", lines.get(5));
    }

    @Test
    @DisplayName("蒸汽平台确认框：交税 6.77 (6.87%)")
    void confirmCopySteamTax() {
        CompanyView steam = new CompanyView("蒸汽平台", 49, 48, 98.49, "98.49", Double.NaN, "",
                -2.28, Double.NaN, CompanyView.STATUS_TRADING, 3, 59000, CompanyView.Source.API);
        TradeDraft d = TradeDraft.create(steam, TradeDraft.Side.BUY, 1, rich(), null, 105.26)
                .orElseThrow();
        List<String> lines = TradeConfirmDialog.lines(d, LiveQuoteFlow.Phase.READY);
        assertTrue(lines.get(2).contains("98.49"));
        assertTrue(lines.get(2).contains("105.26"));
        assertEquals("交税 6.77 (6.87%)", lines.get(3));
        assertEquals("正在查价…", TradeConfirmDialog.lines(d, LiveQuoteFlow.Phase.QUOTING).get(3));
    }

    private static CompanyView live() {
        return new CompanyView("月港控股", 56, 55, 42.38, "42.38", Double.NaN, "",
                1.5, -60.63, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
    }

    private static WalletView rich() {
        return new WalletView(10_000);
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
