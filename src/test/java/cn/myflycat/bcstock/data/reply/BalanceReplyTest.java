package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.WalletView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceReplyTest {

    @Test
    @DisplayName("实测 /bal 原文 → 45108.13")
    void parsesMeasuredBalance() {
        Optional<WalletView> got = BalanceReply.parse(CmdSamples.firstLine("bal.txt"));
        assertTrue(got.isPresent());
        assertTrue(got.get().known());
        assertEquals(45108.13, got.get().balance(), 1e-9);
    }

    @Test
    @DisplayName("带 MCC 行首 ▌ 和颜色码也能认")
    void stripsJunkPrefix() {
        Optional<WalletView> got = BalanceReply.parse(
                "▌§a[帕拉伦联邦中央银行] 资金: $45,108.13 帕元");
        assertTrue(got.isPresent());
        assertEquals(45108.13, got.get().balance(), 1e-9);
    }

    @Test
    @DisplayName("对不上的行是 empty，不是余额 0")
    void unknownIsEmptyNotZero() {
        assertTrue(BalanceReply.parse("月港控股").isEmpty());
        assertTrue(BalanceReply.parse(List.of()).isEmpty());
        assertTrue(BalanceReply.parse((String) null).isEmpty());
    }
}
