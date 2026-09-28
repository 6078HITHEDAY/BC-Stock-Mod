package cn.myflycat.bcstock.data.reply;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeReplyTest {

    @Test
    @DisplayName("实测错误前缀 → 失败结果（不是成功）")
    void measuredErrorIsFailure() {
        Optional<TradeReply.Result> got = TradeReply.parse(CmdSamples.lines("error-invalid.txt"));
        assertTrue(got.isPresent());
        assertFalse(got.get().ok());
    }

    @Test
    @DisplayName("真机买入成功（带错误前缀形态）→ ok")
    void measuredBuySuccess() {
        Optional<TradeReply.Result> got = TradeReply.parse(
                List.of("帕拉伦股市 > 您已成功购买了 1 股股票。"));
        assertTrue(got.isPresent());
        assertTrue(got.get().ok());
    }

    @Test
    @DisplayName("真机卖出成功（带错误前缀形态）→ ok")
    void measuredSellSuccess() {
        Optional<TradeReply.Result> got = TradeReply.parse(
                List.of("帕拉伦股市 > 您已成功以 105.26 的价格出售了 1 股股票。"));
        assertTrue(got.isPresent());
        assertTrue(got.get().ok());
    }

    @Test
    @DisplayName("空输入或未见过的文案 → empty")
    void unknownIsEmpty() {
        assertTrue(TradeReply.parse(List.of()).isEmpty());
        assertTrue(TradeReply.parse(null).isEmpty());
        assertTrue(TradeReply.parse(List.of("恭喜你买到了")).isEmpty());
    }
}
