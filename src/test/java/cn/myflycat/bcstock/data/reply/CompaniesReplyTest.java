package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.CompanyView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompaniesReplyTest {

    @Test
    @DisplayName("§5.2 样本：3 家、K 后缀换算、破产价 0、没有 id")
    void parsesHomepageThree() {
        Optional<List<CompanyView>> got = CompaniesReply.parse(CmdSamples.lines("companies.txt"));
        assertTrue(got.isPresent());
        List<CompanyView> list = got.get();
        assertEquals(3, list.size());

        CompanyView a = list.get(0);
        assertEquals("联邦健保", a.name());
        assertEquals(CompanyView.ID_UNKNOWN, a.marketId());
        assertEquals(67.85, a.price(), 1e-9);
        assertEquals(CompanyView.STATUS_TRADING, a.status());
        assertEquals(2, a.risk());
        assertTrue(Double.isNaN(a.changePct()), "列表回执没有涨跌幅");
        assertEquals(CompanyView.Source.COMMAND, a.source());

        CompanyView b = list.get(1);
        assertEquals("鹅城军工科技", b.name());
        assertEquals(1540.0, b.price(), 1e-9);
        assertEquals("1.54 K", b.priceRaw());

        CompanyView c = list.get(2);
        assertEquals("舒芙蕾地产", c.name());
        assertEquals(CompanyView.STATUS_BANKRUPT, c.status());
        assertEquals(0.0, c.price(), 1e-9);
        assertTrue(c.bankrupt());
    }

    @Test
    @DisplayName("错误前缀 → empty")
    void errorPrefixIsFailure() {
        assertTrue(CompaniesReply.parse(CmdSamples.lines("error-invalid.txt")).isEmpty());
    }
}
