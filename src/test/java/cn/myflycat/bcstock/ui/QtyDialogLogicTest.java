package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import cn.myflycat.bcstock.decision.DecisionPreset;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QtyDialogLogicTest {

    @Test
    @DisplayName("★ 买三档 / 卖四档（含全部抛出）")
    void choiceLists() {
        assertEquals(3, QtyDialogLogic.choices(TradeDraft.Side.BUY).size());
        assertEquals(4, QtyDialogLogic.choices(TradeDraft.Side.SELL).size());
        assertTrue(QtyDialogLogic.choices(TradeDraft.Side.SELL)
                .contains(QtyDialogLogic.Choice.ALL));
        assertFalse(QtyDialogLogic.choices(TradeDraft.Side.BUY)
                .contains(QtyDialogLogic.Choice.ALL));
    }

    @Test
    @DisplayName("★ 自定义：空/非数字/0/超上限拒绝；合法通过")
    void parseCustom() {
        assertFalse(QtyDialogLogic.parseCustom("").ok());
        assertFalse(QtyDialogLogic.parseCustom("abc").ok());
        assertFalse(QtyDialogLogic.parseCustom("0").ok());
        assertFalse(QtyDialogLogic.parseCustom("-1").ok());
        var over = QtyDialogLogic.parseCustom(String.valueOf(TradeSettings.MAX_CUSTOM_QTY + 1));
        assertFalse(over.ok());
        assertTrue(over.error().contains(String.valueOf(TradeSettings.MAX_CUSTOM_QTY)));
        assertTrue(QtyDialogLogic.parseCustom("7").ok());
        assertEquals(7, QtyDialogLogic.parseCustom("7").qty());
        assertTrue(QtyDialogLogic.parseCustom(String.valueOf(TradeSettings.MAX_CUSTOM_QTY)).ok());
    }

    @Test
    @DisplayName("★ 全部抛出：持仓 0 不解析；超 MAX 仍允许")
    void sellAll() {
        assertTrue(QtyDialogLogic.resolve(QtyDialogLogic.Choice.ALL, TradeDraft.Side.SELL, 0)
                .isEmpty());
        OptionalInt big = QtyDialogLogic.resolve(QtyDialogLogic.Choice.ALL, TradeDraft.Side.SELL,
                TradeSettings.MAX_CUSTOM_QTY + 50L);
        assertTrue(big.isPresent());
        assertEquals(TradeSettings.MAX_CUSTOM_QTY + 50, big.getAsInt());

        CompanyView live = new CompanyView("月港控股", 56, 55, 42.38, "42.38", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API);
        WalletView rich = new WalletView(1_000_000);
        HoldingView many = new HoldingView("月港控股", 56, "", TradeSettings.MAX_CUSTOM_QTY + 50L,
                1, Double.NaN);
        assertTrue(TradeDraft.create(live, TradeDraft.Side.SELL, TradeSettings.MAX_CUSTOM_QTY + 50,
                rich, many).isPresent(), "全部抛出可超过 MAX_CUSTOM_QTY");
        assertTrue(TradeDraft.create(live, TradeDraft.Side.SELL, 10, rich, null).isEmpty(),
                "持仓 0 卖出不进确认框");
    }

    @Test
    @DisplayName("买入自定义默认数量跟档位走；卖出不预填")
    void defaultCustomQtyFollowsPreset() {
        assertEquals(10, QtyDialogLogic.defaultCustomQty(TradeDraft.Side.BUY, DecisionPreset.DEFAULT));
        assertEquals(1, QtyDialogLogic.defaultCustomQty(TradeDraft.Side.BUY, DecisionPreset.CONSERVATIVE));
        assertEquals(100, QtyDialogLogic.defaultCustomQty(TradeDraft.Side.BUY, DecisionPreset.AGGRESSIVE));
        assertEquals(0, QtyDialogLogic.defaultCustomQty(TradeDraft.Side.SELL, DecisionPreset.DEFAULT));
    }
}
