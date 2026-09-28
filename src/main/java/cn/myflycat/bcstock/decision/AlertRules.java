package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.ui.FloorGauge;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L1 提醒规则。纯函数，<b>不 import {@code net.minecraft}</b>。
 *
 * <p>地板规则用服务端 {@code distance_pct}（公司名 → 距地板百分比）。
 * 不在 map 里的公司不跑地板规则（未知 ≠ 安全）。真机待验证。
 */
public final class AlertRules {

    public static final double DEFAULT_CHANGE_PCT = 5.0;
    public static final double FLOOR_DISTANCE_MAX_PCT = FloorGauge.DANGER_DISTANCE_PCT;
    public static final double PNL_PCT_ABS = 20.0;

    public enum Kind {
        CHANGE("涨跌异动"),
        FLOOR("近地板"),
        PNL("盈亏异动");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public record Alert(Kind kind, String company, String message) {
    }

    public record Switches(boolean master, boolean change, boolean floor, boolean pnl) {
        public static Switches allOff() {
            return new Switches(false, false, false, false);
        }
    }

    /** 已报过的 key：{@code generation|kind|company}。generation 是行情刷新世代，不是时钟相位。 */
    private final Set<String> fired = new HashSet<>();

    public void clearForTest() {
        fired.clear();
    }

    public int firedCount() {
        return fired.size();
    }

    /**
     * 评估一轮快照。同一刷新世代 + 规则 + 公司只报一次。
     *
     * @param generation        行情刷新世代（进服 / 聊天增量 / 20 分钟兜底成功后 +1）
     * @param distancePctByName 公司名 → 服务端 distance_pct（没有的公司不跑地板规则）
     */
    public List<Alert> evaluate(StockSnapshot snap, long generation, Switches switches,
                                Map<String, Double> distancePctByName) {
        List<Alert> out = new ArrayList<>();
        if (snap == null || switches == null || !switches.master()) {
            return List.of();
        }
        for (CompanyView c : snap.companies()) {
            if (c == null) {
                continue;
            }
            if (switches.change()) {
                maybeChange(out, generation, c);
            }
            if (switches.floor()) {
                Double dist = (distancePctByName == null) ? null : distancePctByName.get(c.name());
                maybeFloor(out, generation, c, dist);
            }
        }
        if (switches.pnl() && snap.holdingsKnown()) {
            for (HoldingView h : snap.heldOnly()) {
                maybePnl(out, generation, h);
            }
        }
        return List.copyOf(out);
    }

    private void maybeChange(List<Alert> out, long generation, CompanyView c) {
        if (c.bankrupt()) {
            return;
        }
        if (Double.isNaN(c.changePct())) {
            return;
        }
        if (Math.abs(c.changePct()) <= DEFAULT_CHANGE_PCT) {
            return;
        }
        String key = key(generation, Kind.CHANGE, c.name());
        if (!fired.add(key)) {
            return;
        }
        out.add(new Alert(Kind.CHANGE, c.name(),
                c.name() + " 涨跌 " + pct(c.changePct())));
    }

    private void maybeFloor(List<Alert> out, long generation, CompanyView c, Double distancePct) {
        if (c.bankrupt()) {
            return;
        }
        if (distancePct == null || Double.isNaN(distancePct)) {
            return;
        }
        if (distancePct >= FLOOR_DISTANCE_MAX_PCT) {
            return;
        }
        String key = key(generation, Kind.FLOOR, c.name());
        if (!fired.add(key)) {
            return;
        }
        out.add(new Alert(Kind.FLOOR, c.name(),
                c.name() + " 距地板 " + pct(distancePct)));
    }

    private void maybePnl(List<Alert> out, long generation, HoldingView h) {
        if (!h.averageBuyPriceKnown() || Double.isNaN(h.profitPct())) {
            return;
        }
        if (Math.abs(h.profitPct()) <= PNL_PCT_ABS) {
            return;
        }
        String key = key(generation, Kind.PNL, h.name());
        if (!fired.add(key)) {
            return;
        }
        out.add(new Alert(Kind.PNL, h.name(),
                h.name() + " 盈亏 " + pct(h.profitPct())));
    }

    static String key(long generation, Kind kind, String company) {
        return generation + "|" + kind.name() + "|" + company;
    }

    private static String pct(double v) {
        String sign = v > 0 ? "+" : "";
        return sign + String.format(Locale.ROOT, "%.2f%%", v);
    }
}
