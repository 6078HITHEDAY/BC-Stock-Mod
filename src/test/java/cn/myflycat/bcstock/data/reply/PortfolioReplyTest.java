package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioReplyTest {

    @Test
    @DisplayName("实测持仓块：月港控股 111 股 / 总值 4703.98")
    void parsesMeasuredHolding() {
        Optional<List<HoldingView>> got = PortfolioReply.parse(CmdSamples.lines("portfolio-held.txt"));
        assertTrue(got.isPresent());
        assertEquals(1, got.get().size());
        HoldingView h = got.get().get(0);
        assertEquals("月港控股", h.name());
        assertEquals(111, h.shares());
        assertEquals(4703.98, h.currentValue(), 1e-9);
        assertFalse(h.marketIdKnown(), "聊天持仓块没有 id");
        assertEquals(CompanyView.ID_UNKNOWN, h.marketId());
        assertTrue(h.held());
        assertFalse(h.averageBuyPriceKnown(), "聊天回执没有均价");
        assertTrue(Double.isNaN(h.profit()), "均价未知时盈亏必须是 NaN");
        assertTrue(Double.isNaN(h.profitPct()));
        assertTrue(h.profit() != 4703.98, "不许把全部市值算成浮盈");
    }

    @Test
    @DisplayName("块中间插进退服广播，公司名仍是月港控股")
    void ignoresPlayerStatusNoise() {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(CmdSamples.lines("portfolio-held.txt"));
        lines.add(3, "* wsmhhh暂时离开了。");
        Optional<List<HoldingView>> got = PortfolioReply.parse(lines);
        assertTrue(got.isPresent());
        assertEquals(1, got.get().size());
        assertEquals("月港控股", got.get().get(0).name());
    }

    @Test
    @DisplayName("空仓块 → 空列表（确认没有持仓），不是 empty")
    void emptyBlockIsConfirmedEmpty() {
        Optional<List<HoldingView>> got = PortfolioReply.parse(CmdSamples.lines("portfolio-empty.txt"));
        assertTrue(got.isPresent(), "解析成功");
        assertTrue(got.get().isEmpty(), "确认空仓");
    }

    @Test
    @DisplayName("错误前缀 → empty（不是空仓）")
    void errorPrefixIsFailure() {
        assertTrue(PortfolioReply.parse(CmdSamples.lines("error-invalid.txt")).isEmpty());
    }

    @Test
    @DisplayName("真机空仓短回执「您没有任何股票」→ 确认空列表")
    void noHoldingsShortReplyIsEmptyList() {
        Optional<List<HoldingView>> got = PortfolioReply.parse(
                List.of("帕拉伦股市 > 您没有任何股票。"));
        assertTrue(got.isPresent());
        assertTrue(got.get().isEmpty());
    }

    @Test
    @DisplayName("没有块开始 → empty")
    void noBlockIsFailure() {
        assertTrue(PortfolioReply.parse(List.of("月港控股", "股票: 1")).isEmpty());
        assertTrue(PortfolioReply.parse(List.of()).isEmpty());
    }
}
