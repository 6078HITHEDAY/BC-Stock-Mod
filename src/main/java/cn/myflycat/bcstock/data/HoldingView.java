package cn.myflycat.bcstock.data;

/**
 * 一笔持仓。数据只来自聊天命令 {@code /invest portfolio}（GUI 持仓行同构）。
 *
 * <p><b>持股数减到 0 的行不会消失</b>（实测：卖光「联邦投资集团」1 股后行还在，
 * {@code 持股数: 0} / {@code 当前价值: 0.00} / {@code 平均买入价: 264.11} 仍保留）。
 * 所以判断「是否持有」必须看 {@link #held()}，<b>不能看「行在不在」</b>。
 *
 * <p>{@code /invest portfolio} 的回执里<b>没有 companyId</b>，所以这里的
 * {@code marketId} 通常就是 {@link CompanyView#ID_UNKNOWN}——要靠公司名去
 * {@link StockSnapshot} 里查 {@link CompanyView} 才拿得到可下单的编号。
 *
 * <p>本类不引用任何 {@code net.minecraft} 类型，可离线测。
 */
public record HoldingView(
        String name,
        int marketId,
        String status,
        long shares,
        double currentValue,
        double averageBuyPrice) {

    public HoldingView {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("公司名不能为空");
        }
    }

    /** 真正持有（持股数 > 0）。持股 0 的「墓碑行」返回 false。 */
    public boolean held() {
        return shares > 0;
    }

    public boolean marketIdKnown() {
        return marketId >= 0;
    }

    /** 均价已知。聊天 {@code /invest portfolio} 回执没有这个字段，解析器必须填 {@code NaN}。 */
    public boolean averageBuyPriceKnown() {
        return !Double.isNaN(averageBuyPrice);
    }

    /** 成本 = 持股数 × 平均买入价。均价未知 → {@code NaN}。 */
    public double cost() {
        if (!averageBuyPriceKnown()) {
            return Double.NaN;
        }
        return shares * averageBuyPrice;
    }

    /**
     * 浮动盈亏。持股 0 时是 <b>0</b>，不是 NaN；
     * 持股 &gt; 0 且均价未知 → {@code NaN}（「不知道」不是「赚了全部市值」）。
     */
    public double profit() {
        if (!held()) {
            return 0.0;
        }
        if (!averageBuyPriceKnown()) {
            return Double.NaN;
        }
        return currentValue - cost();
    }

    /**
     * 相对成本的涨跌幅（%）。持股 0 或成本为 0 时返回 <b>0</b>，不是 NaN；
     * 均价未知 → {@code NaN}。未知必须先于「成本 ≤ 0」判断——{@code NaN <= 0} 为 false。
     */
    public double profitPct() {
        if (!held()) {
            return 0.0;
        }
        if (!averageBuyPriceKnown()) {
            return Double.NaN;
        }
        double c = cost();
        if (c <= 0) {
            return 0.0;
        }
        return profit() / c * 100.0;
    }

    public HoldingView withMarketId(int newMarketId) {
        return new HoldingView(name, newMarketId, status, shares, currentValue, averageBuyPrice);
    }
}
