package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.WalletView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeDraftTest {

    private static CompanyView trading() {
        return new CompanyView("月港控股", 56, 55, 42.38, "42.38", Double.NaN, "",
                1.5, -60.63, CompanyView.STATUS_TRADING, 3, 100, CompanyView.Source.API);
    }

    @Test
    @DisplayName("预计金额 = price × 数量，两位小数；命令用 market_id")
    void estimateAndCommand() {
        TradeDraft d = TradeDraft.create(trading(), TradeDraft.Side.BUY, 10,
                new WalletView(10_000), null).orElseThrow();
        assertEquals(423.80, d.estimated(), 1e-9);
        assertEquals(10_000 - 423.80, d.walletAfter(), 1e-9);
        assertEquals(42.38, d.boardPrice(), 1e-9);
        assertTrue(Double.isNaN(d.livePrice()));
        assertEquals("invest buy 56 10", d.command());
        assertFalse(d.command().contains("55"), "不许拿 apiId 拼命令");
    }

    @Test
    @DisplayName("蒸汽平台黄金样本：牌价 98.49 / 实时 105.26 → 预计 105.26、交税 6.77")
    void steamPlatformLiveTax() {
        CompanyView steam = new CompanyView("蒸汽平台", 49, 48, 98.49, "98.49", Double.NaN, "",
                -2.28, Double.NaN, CompanyView.STATUS_TRADING, 3, 59000, CompanyView.Source.API);
        WalletView wallet = new WalletView(51_281.51);
        TradeDraft d = TradeDraft.create(steam, TradeDraft.Side.BUY, 1, wallet, null, 105.26)
                .orElseThrow();
        assertEquals(105.26, d.estimated(), 1e-9);
        assertEquals(6.77, d.tax(), 1e-9);
        assertEquals(6.87, d.taxPct(), 0.005);
        assertEquals(51_281.51 - 105.26, d.walletAfter(), 1e-9);
        assertTrue(d.canAfford());

        assertTrue(TradeDraft.create(steam, TradeDraft.Side.BUY, 1, new WalletView(100), null)
                .isPresent(), "只有牌价时应按 98.49 过门槛");
        assertTrue(TradeDraft.create(steam, TradeDraft.Side.BUY, 1, new WalletView(100), null, 105.26)
                .isEmpty(), "有实时价时按 105.26 拒");
    }

    @Test
    @DisplayName("卖出交税固定 0")
    void sellTaxIsZero() {
        CompanyView live = trading();
        HoldingView held = new HoldingView("月港控股", 56, "", 10, 423.80, Double.NaN);
        TradeDraft d = TradeDraft.create(live, TradeDraft.Side.SELL, 10, new WalletView(10_000),
                held, 50.00).orElseThrow();
        assertEquals(0.0, d.tax(), 1e-9);
        assertEquals(500.00, d.estimated(), 1e-9);
    }

    @Test
    @DisplayName("★ 超上限 / 破产 / 余额不足 / 余额未知 / 没编号 → 不进确认框；自定义 7 放行")
    void gatesReject() {
        CompanyView live = trading();
        WalletView rich = new WalletView(10_000);
        assertTrue(TradeDraft.create(live, TradeDraft.Side.BUY, 7, rich, null).isPresent(),
                "自定义 1..MAX 应放行");
        assertTrue(TradeDraft.create(live, TradeDraft.Side.BUY, 1001, rich, null).isEmpty(),
                "超过 MAX_CUSTOM_QTY 拒");
        assertTrue(TradeDraft.create(live, TradeDraft.Side.BUY, 0, rich, null).isEmpty());

        CompanyView dead = new CompanyView("红苹果短剧", 58, 57, 1.0, "1.00", Double.NaN, "",
                Double.NaN, Double.NaN, CompanyView.STATUS_BANKRUPT, 0,
                CompanyView.SHARES_UNKNOWN, CompanyView.Source.API);
        assertTrue(TradeDraft.create(dead, TradeDraft.Side.BUY, 1, rich, null).isEmpty());

        assertTrue(TradeDraft.create(live, TradeDraft.Side.BUY, 100, new WalletView(10), null).isEmpty());
        assertTrue(TradeDraft.create(live, TradeDraft.Side.BUY, 1, WalletView.UNKNOWN, null).isEmpty());

        CompanyView noId = new CompanyView("月港控股", CompanyView.ID_UNKNOWN, 55, 42.38, "42.38",
                Double.NaN, "", 1.5, -60.63, CompanyView.STATUS_TRADING, 3, 100,
                CompanyView.Source.COMMAND);
        assertFalse(noId.tradable());
        assertTrue(TradeDraft.create(noId, TradeDraft.Side.BUY, 1, rich, null).isEmpty());
    }

    @Test
    @DisplayName("卖出持股不够 → 拒")
    void sellNeedsShares() {
        CompanyView live = trading();
        WalletView rich = new WalletView(10_000);
        assertTrue(TradeDraft.create(live, TradeDraft.Side.SELL, 10, rich, null).isEmpty());
        HoldingView few = new HoldingView("月港控股", 56, "", 1, 42.38, Double.NaN);
        assertTrue(TradeDraft.create(live, TradeDraft.Side.SELL, 10, rich, few).isEmpty());
        HoldingView enough = new HoldingView("月港控股", 56, "", 10, 423.80, Double.NaN);
        assertTrue(TradeDraft.create(live, TradeDraft.Side.SELL, 10, rich, enough).isPresent());
    }
}
