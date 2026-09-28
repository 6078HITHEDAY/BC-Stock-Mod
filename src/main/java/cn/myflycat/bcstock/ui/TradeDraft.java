package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import java.util.Locale;
import java.util.Optional;

/**
 * 一笔下单草稿。金额与命令串都是纯函数，可离线测。
 * 不引用 {@code net.minecraft}，也不引用 {@code CommandGateway}。
 *
 * <p>数量：1/10/100 快捷档，或自定义 1..{@link TradeSettings#MAX_CUSTOM_QTY}。
 * 卖出「全部抛出」可超过该上限（持仓天然约束）。
 *
 * <p>{@code boardPrice} 是盘面/API 牌价；{@code livePrice} 是
 * {@code /invest company info} 的实时价（未知为 {@code NaN}）。
 * 预计金额与买入门槛用实时价，没有实时价才退回牌价。
 */
public record TradeDraft(
        Side side,
        String name,
        int marketId,
        double boardPrice,
        double livePrice,
        int qty,
        double wallet) {

    public enum Side {
        BUY("buy", "买入"),
        SELL("sell", "卖出");

        public final String verb;
        public final String title;

        Side(String verb, String title) {
            this.verb = verb;
            this.title = title;
        }
    }

    /** 快捷档（弹窗按钮仍展示 1/10；100 保留兼容）。 */
    public static final int[] ALLOWED_QTY = {1, 10, 100};

    /**
     * 买入：1..{@link TradeSettings#MAX_CUSTOM_QTY}。
     * 卖出：qty≥1（上限由持仓约束；全部抛出可 &gt; MAX）。
     */
    public static boolean allowedQty(int qty, Side side) {
        if (qty < 1 || side == null) {
            return false;
        }
        if (side == Side.BUY) {
            return qty <= TradeSettings.MAX_CUSTOM_QTY;
        }
        return true;
    }

    /** 盘面单价（确认框「单价」行）。 */
    public double price() {
        return boardPrice;
    }

    public boolean liveKnown() {
        return liveKnown(livePrice);
    }

    /** 成交用价：有实时价用实时，否则用牌价。 */
    public double fillPrice() {
        return liveKnown() ? livePrice : boardPrice;
    }

    public double estimated() {
        return round2(fillPrice() * qty);
    }

    public double walletAfter() {
        return side == Side.BUY ? round2(wallet - estimated()) : round2(wallet + estimated());
    }

    /**
     * 买入交税 = max(0, 实时×qty − 牌价×qty)。
     * 卖出固定 0。实时价未知 → {@code NaN}。
     */
    public double tax() {
        if (side == Side.SELL) {
            return 0.0;
        }
        if (!liveKnown()) {
            return Double.NaN;
        }
        return round2(Math.max(0.0, livePrice * qty - boardPrice * qty));
    }

    /** 交税 / (牌价×qty) × 100。未知 → {@code NaN}。 */
    public double taxPct() {
        double base = boardPrice * qty;
        double t = tax();
        if (!(base > 0) || Double.isNaN(t)) {
            return Double.NaN;
        }
        return t / base * 100.0;
    }

    public boolean canAfford() {
        return side != Side.BUY || wallet >= estimated();
    }

    public TradeDraft withLivePrice(double live) {
        return new TradeDraft(side, name, marketId, boardPrice, live, qty, wallet);
    }

    /** 不带前导 /，参数是 market_id。 */
    public String command() {
        return "invest " + side.verb + " " + marketId + " " + qty;
    }

    public static Optional<TradeDraft> create(CompanyView company, Side side, int qty,
                                              WalletView wallet, HoldingView holding) {
        return create(company, side, qty, wallet, holding, Double.NaN);
    }

    public static Optional<TradeDraft> create(CompanyView company, Side side, int qty,
                                              WalletView wallet, HoldingView holding,
                                              double livePrice) {
        if (company == null || side == null) {
            return Optional.empty();
        }
        if (!allowedQty(qty, side)) {
            return Optional.empty();
        }
        if (!company.tradable()) {
            return Optional.empty();
        }
        if (!company.priceKnown()) {
            return Optional.empty();
        }
        if (wallet == null || !wallet.known()) {
            return Optional.empty();
        }
        double fill = liveKnown(livePrice) ? livePrice : company.price();
        double est = round2(fill * qty);
        if (side == Side.BUY && wallet.balance() < est) {
            return Optional.empty();
        }
        if (side == Side.SELL && (holding == null || holding.shares() < qty)) {
            return Optional.empty();
        }
        return Optional.of(new TradeDraft(
                side, company.name(), company.marketId(), company.price(), livePrice, qty,
                wallet.balance()));
    }

    public static String money(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static boolean liveKnown(double live) {
        return !Double.isNaN(live) && live > 0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
