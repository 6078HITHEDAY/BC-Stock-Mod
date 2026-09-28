package cn.myflycat.bcstock.decision;

/**
 * 三档决策参数表（纯数据）。<b>待真人确认</b>——数值先给一套保守默认，
 * 下一轮才绑到实际决策计算。不 import {@code net.minecraft}。
 *
 * <p>来源参考 {@code docs/decision-model.md} 调优结论，不是定论。
 */
public enum DecisionPreset {

    DEFAULT("default", 3.0, 50.0, -30.0, 10, 3),
    AGGRESSIVE("aggressive", 8.0, 100.0, -50.0, 100, 5),
    CONSERVATIVE("conservative", 1.0, 30.0, -15.0, 1, 2);

    private final String key;
    /** 出手门槛：距地板多少 % 内才算「够低」。 */
    private final double entryDistancePct;
    /** 止盈线：浮盈超过多少 % 卖。 */
    private final double takeProfitPct;
    /** 止损线：浮亏超过多少 % 割（负数）。 */
    private final double stopLossPct;
    /** 仓位：每笔默认买多少股。 */
    private final int defaultBuyQty;
    /** 风险过滤：只碰 risk ≤ N。 */
    private final int maxRisk;

    DecisionPreset(String key, double entryDistancePct, double takeProfitPct,
                   double stopLossPct, int defaultBuyQty, int maxRisk) {
        this.key = key;
        this.entryDistancePct = entryDistancePct;
        this.takeProfitPct = takeProfitPct;
        this.stopLossPct = stopLossPct;
        this.defaultBuyQty = defaultBuyQty;
        this.maxRisk = maxRisk;
    }

    public String key() {
        return key;
    }

    public double entryDistancePct() {
        return entryDistancePct;
    }

    public double takeProfitPct() {
        return takeProfitPct;
    }

    public double stopLossPct() {
        return stopLossPct;
    }

    public int defaultBuyQty() {
        return defaultBuyQty;
    }

    public int maxRisk() {
        return maxRisk;
    }

    public static DecisionPreset parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String k = raw.trim().toLowerCase();
        for (DecisionPreset p : values()) {
            if (p.key.equals(k)) {
                return p;
            }
        }
        return DEFAULT;
    }
}
