package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.BcStockSettings;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.decision.DecisionEngine;
import java.util.ArrayList;
import java.util.List;

/** 详情屏文案。不引用 {@code net.minecraft}。 */
public final class DetailLines {

    private DetailLines() {
    }

    public static List<String> of(CompanyView c, HoldingView h) {
        return of(c, h, null);
    }

    public static List<String> of(CompanyView c, HoldingView h, FloorView floor) {
        List<String> lines = new ArrayList<>();
        if (c == null) {
            return List.of();
        }
        String id = c.marketIdKnown() ? ("  #" + c.marketId()) : "  #--";
        lines.add(c.name() + id);
        lines.add("状态: " + (c.status().isBlank() ? BoardFormat.UNKNOWN : c.status()));
        lines.add("股票单价: " + (c.bankrupt() ? BoardFormat.BANKRUPT
                : (c.priceKnown() ? BoardFormat.price(c.price()) : BoardFormat.UNKNOWN)));
        if (c.marketCapKnown()) {
            lines.add("市值: " + (c.marketCapRaw().isBlank() ? BoardFormat.price(c.marketCap())
                    : c.marketCapRaw() + " (" + BoardFormat.price(c.marketCap()) + ")"));
        } else {
            lines.add("市值: " + BoardFormat.UNKNOWN);
        }
        lines.add("风险度: " + (c.risk() > 0 ? c.risk() + " / 5" : BoardFormat.UNKNOWN));
        lines.add("历史总涨跌: " + (Double.isNaN(c.totalChangePct())
                ? BoardFormat.UNKNOWN : BoardFormat.pct(c.totalChangePct())));
        lines.add(floorLine(floor));
        if (h != null && h.held()) {
            String avg = h.averageBuyPriceKnown() ? BoardFormat.price(h.averageBuyPrice()) : BoardFormat.UNKNOWN;
            String pnl = h.averageBuyPriceKnown() && !Double.isNaN(h.profitPct())
                    ? BoardFormat.pct(h.profitPct()) : BoardFormat.UNKNOWN;
            lines.add("持股 " + h.shares() + "　成本 " + avg + "　现值 "
                    + BoardFormat.price(h.currentValue()) + "　盈亏 " + pnl);
        } else {
            lines.add("持股 --　成本 --　现值 --　盈亏 --");
        }
        DecisionEngine.Advice advice = DecisionEngine.advise(c, h, floor, BcStockSettings.decisionPreset());
        lines.add(advice.detailLine());
        if (cn.myflycat.bcstock.data.TradeSettings.enabled()) {
            lines.add("点行内「买」「卖」选数量，回车确认");
        } else {
            lines.add("买卖入口已关闭");
        }
        return List.copyOf(lines);
    }

    /** 有服务端 distance_pct 才显示数字；否则未知（不许显示 0%）。 */
    public static String floorLine(FloorView floor) {
        if (floor == null || !floor.known()) {
            return FloorGauge.label() + ": " + BoardFormat.UNKNOWN;
        }
        return FloorGauge.label() + ": " + BoardFormat.pct(floor.distancePct());
    }

    public static double floorDistancePct(FloorView floor) {
        return (floor != null && floor.known()) ? floor.distancePct() : Double.NaN;
    }

    public static boolean floorInDanger(FloorView floor) {
        return floor != null && floor.known() && floor.inDangerZone();
    }
}
