package cn.myflycat.bcstock.ui;

/**
 * 买卖按钮是否可点（纯逻辑，离线可测）。
 * 对应红线：{@code trade.enabled=false} 时灰显无反应；破产不能买；无持仓不能卖。
 */
public final class TradeButtonState {

    private TradeButtonState() {
    }

    public static boolean buyActive(boolean tradeEnabled, boolean bankrupt) {
        return tradeEnabled && !bankrupt;
    }

    public static boolean sellActive(boolean tradeEnabled, boolean held) {
        return tradeEnabled && held;
    }
}
