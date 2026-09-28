package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认屏状态机（纯逻辑）：Esc 不发、Enter 才发、开关关着全 inactive。
 */
class TradeConfirmGateTest {

    @AfterEach
    void reset() {
        TradeSettings.setEnabledForTest(false);
        AutoTradeSettings.resetForTest();
    }

    @Test
    @DisplayName("★ Esc/cancel 不发命令")
    void cancelSendsNothing() {
        TradeSettings.setEnabledForTest(true);
        List<String> sent = new ArrayList<>();
        CommandGateway gw = gateway(sent);
        TradeFlow flow = new TradeFlow();
        assertTrue(flow.tryOpen(company(), TradeDraft.Side.BUY, 1, wallet(), null));
        flow.cancel();
        assertFalse(flow.confirm(gw));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ Enter/confirm 才发；trade 关着不发")
    void confirmSendsOnce() {
        TradeSettings.setEnabledForTest(true);
        List<String> sent = new ArrayList<>();
        CommandGateway gw = gateway(sent);
        TradeFlow flow = new TradeFlow();
        TradeDraft draft = TradeDraft.create(company(), TradeDraft.Side.BUY, 10, wallet(), null).orElseThrow();
        assertTrue(flow.tryOpenDraft(draft));
        assertTrue(flow.confirm(gw));
        assertEquals(List.of("invest buy 56 10"), sent);

        sent.clear();
        TradeSettings.setEnabledForTest(false);
        assertFalse(flow.tryOpenDraft(draft));
        assertFalse(flow.confirm(gw));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("数量弹窗源码：TextField 用 addDrawableChild，不许 fill 盖住输入框")
    void qtyDialogDoesNotCoverTextField() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/myflycat/bcstock/ui/QtyDialogScreen.java"));
        assertTrue(src.contains("addDrawableChild(field)"), "自定义数量必须 addDrawableChild");
        assertTrue(src.contains("TextFieldWidget"), "必须用原版 TextFieldWidget");
        // 回归：自绘半透明/不透明面板盖控件的老写法
        assertFalse(src.contains("qtyPanel"), "不许再自绘 qtyPanel 盖住输入框");
        assertFalse(src.contains("drawQtyDialog"), "不许再走自绘数量弹窗");
        // render 里若 fill 盖满屏会盖住子控件：禁止在 render 里对输入区域大面积 fill
        assertFalse(src.contains("fill(0, 0"), "不许全屏 fill 盖住 TextField");
    }

    private static CompanyView company() {
        return new CompanyView("月港控股", 56, 55, 42.38, "42.38",
                Double.NaN, "", 1.5, Double.NaN,
                CompanyView.STATUS_TRADING, 3, 1000, CompanyView.Source.API);
    }

    private static WalletView wallet() {
        return new WalletView(10_000);
    }

    private static CommandGateway gateway(List<String> sent) {
        CommandGateway gw = new CommandGateway();
        gw.setSender(cmd -> {
            sent.add(cmd);
            return true;
        });
        return gw;
    }
}
