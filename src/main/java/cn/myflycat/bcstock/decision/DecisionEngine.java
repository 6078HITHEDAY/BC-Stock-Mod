package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import java.util.Locale;

/**
 * 用 {@link DecisionPreset} 门槛给出买 / 卖 / 观望。纯函数，不 import {@code net.minecraft}。
 *
 * <p>先卖后买。地板未知、风险未知、破产、不可交易 → 观望。未知不当 0。
 */
public final class DecisionEngine {

    public enum Side {
        HOLD,
        BUY,
        SELL
    }

    public record Advice(Side side, int qty, String reason) {

        public Advice {
            if (side == null) {
                side = Side.HOLD;
            }
            if (reason == null) {
                reason = "";
            }
            if (qty < 0) {
                qty = 0;
            }
        }

        public static Advice hold(String reason) {
            return new Advice(Side.HOLD, 0, reason);
        }

        public static Advice buy(int qty, String reason) {
            return new Advice(Side.BUY, qty, reason);
        }

        public static Advice sell(int qty, String reason) {
            return new Advice(Side.SELL, qty, reason);
        }

        /** 盘面前缀。观望为空。 */
        public String prefix() {
            return switch (side) {
                case BUY -> "买 ";
                case SELL -> "卖 ";
                case HOLD -> "";
            };
        }

        public String label() {
            return switch (side) {
                case BUY -> "买";
                case SELL -> "卖";
                case HOLD -> "观望";
            };
        }

        /** 详情行。 */
        public String detailLine() {
            if (side == Side.HOLD) {
                return reason.isBlank() ? "建议: 观望" : "建议: 观望　" + reason;
            }
            return "建议: " + label() + " " + qty + "　" + reason;
        }
    }

    private DecisionEngine() {
    }

    public static Advice advise(CompanyView company, HoldingView holding,
                                FloorView floor, DecisionPreset preset) {
        DecisionPreset p = (preset == null) ? DecisionPreset.DEFAULT : preset;
        if (company == null) {
            return Advice.hold("公司未知");
        }
        Advice sell = maybeSell(holding, p);
        if (sell != null) {
            return sell;
        }
        return maybeBuy(company, floor, p);
    }

    private static Advice maybeSell(HoldingView holding, DecisionPreset preset) {
        if (holding == null || !holding.held()) {
            return null;
        }
        if (!holding.averageBuyPriceKnown() || Double.isNaN(holding.profitPct())) {
            return null;
        }
        double pnl = holding.profitPct();
        int qty = sharesAsQty(holding.shares());
        if (pnl >= preset.takeProfitPct()) {
            return Advice.sell(qty, "浮盈 " + signedPct(pnl) + " ≥ " + barePct(preset.takeProfitPct()));
        }
        if (pnl <= preset.stopLossPct()) {
            return Advice.sell(qty, "浮亏 " + signedPct(pnl) + " ≤ " + barePct(preset.stopLossPct()));
        }
        return null;
    }

    private static Advice maybeBuy(CompanyView company, FloorView floor, DecisionPreset preset) {
        if (company.bankrupt()) {
            return Advice.hold("已破产");
        }
        if (!company.tradable()) {
            return Advice.hold("不可交易");
        }
        if (floor == null || !floor.known() || Double.isNaN(floor.distancePct())) {
            return Advice.hold("地板未知");
        }
        int risk = company.risk();
        if (risk <= 0) {
            return Advice.hold("风险未知");
        }
        if (risk > preset.maxRisk()) {
            return Advice.hold("风险 " + risk + " > " + preset.maxRisk());
        }
        double dist = floor.distancePct();
        if (dist > preset.entryDistancePct()) {
            return Advice.hold("距地板 " + barePct(dist) + " > " + barePct(preset.entryDistancePct()));
        }
        return Advice.buy(preset.defaultBuyQty(),
                "距地板 " + barePct(dist) + " ≤ " + barePct(preset.entryDistancePct())
                        + "；风险 " + risk + " ≤ " + preset.maxRisk());
    }

    static int sharesAsQty(long shares) {
        if (shares <= 0) {
            return 0;
        }
        if (shares > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) shares;
    }

    static String barePct(double value) {
        if (Double.isNaN(value)) {
            return "--";
        }
        String body = String.format(Locale.ROOT, "%.2f", value);
        if (body.endsWith(".00")) {
            body = body.substring(0, body.length() - 3);
        }
        return body + "%";
    }

    static String signedPct(double value) {
        if (Double.isNaN(value)) {
            return "--";
        }
        String sign = value > 0 ? "+" : "";
        return sign + barePct(value);
    }
}
