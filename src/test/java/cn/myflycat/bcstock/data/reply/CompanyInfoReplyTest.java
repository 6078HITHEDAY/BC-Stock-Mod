package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.CompanyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyInfoReplyTest {

    @Test
    @DisplayName("实测 info 56 → 月港控股 / marketId 56 / 价 42.38 / 可用 149889")
    void parsesYuegang() {
        Optional<CompanyView> got = CompanyInfoReply.parse(CmdSamples.lines("company-info-56.txt"), 56);
        assertTrue(got.isPresent());
        CompanyView c = got.get();
        assertEquals("月港控股", c.name());
        assertEquals(56, c.marketId());
        assertEquals(CompanyView.ID_UNKNOWN, c.apiId(), "聊天回执没有 API id");
        assertEquals(42.38, c.price(), 1e-9);
        assertEquals(CompanyView.STATUS_TRADING, c.status());
        assertEquals(3, c.risk());
        assertEquals(149889, c.availableShares());
        assertTrue(c.tradable());
        assertTrue(Double.isNaN(c.changePct()), "历史 5 条不是当前涨跌幅");
        assertEquals(CompanyView.Source.COMMAND, c.source());
    }

    @Test
    @DisplayName("实测 info 55 → PR雪地 / marketId 55")
    void parsesPrXuedi() {
        Optional<CompanyView> got = CompanyInfoReply.parse(CmdSamples.lines("company-info-55.txt"), 55);
        assertTrue(got.isPresent());
        assertEquals("PR雪地", got.get().name());
        assertEquals(55, got.get().marketId());
        assertEquals(286.02, got.get().price(), 1e-9);
        assertEquals(64000, got.get().availableShares());
    }

    @Test
    @DisplayName("★ Id 对不上就丢——不能把 PR雪地 当成月港控股")
    void mismatchedIdIsDropped() {
        Optional<CompanyView> got = CompanyInfoReply.parse(CmdSamples.lines("company-info-56.txt"), 55);
        assertTrue(got.isEmpty(), "请求 55 却收到 Id: 56，必须丢");
    }

    @Test
    @DisplayName("错误前缀 → empty")
    void errorPrefixIsFailure() {
        assertTrue(CompanyInfoReply.parse(CmdSamples.lines("error-invalid.txt"), 25).isEmpty());
        assertTrue(CompanyInfoReply.parse(CmdSamples.lines("error-param.txt"), 0).isEmpty());
    }

    @Test
    @DisplayName("价格带 K 后缀要换算（commands.md 公司列表口径，info 同字段）")
    void priceWithKSuffix() {
        Optional<CompanyView> got = CompanyInfoReply.parse(List.of(
                "-=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-",
                "鹅城军工科技",
                "Id: 54",
                "状态: 交易中",
                "价格: 1.54 K",
                "风险等级: 5",
                "可用股数: 1",
                "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-"), 54);
        assertTrue(got.isPresent());
        assertEquals(1540.0, got.get().price(), 1e-9);
        assertEquals("1.54 K", got.get().priceRaw());
    }
}
