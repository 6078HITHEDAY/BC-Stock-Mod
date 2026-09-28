package cn.myflycat.bcstock.data;

/**
 * 一家公司的一行视图。三条数据源（API / 聊天命令 / GUI 容器）都归一到这个形状，
 * <b>界面只认这一个类型</b>，换数据源不动界面。
 *
 * <p>数值一律存<b>换算后</b>的值（{@code 1.30 K} → {@code 1300.0}），原始文本另存
 * {@code *Raw} 字段——界面要显示缩写、算地板距离要用精确值，两者不能共用一份字符串。
 *
 * <p><b>「不知道」与「是 0」必须分开。</b>本记录用哨兵值表达「不知道」，不许用 0 冒充：
 * <ul>
 *   <li>{@code marketId} / {@code apiId}：{@code -1} = 未知。
 *       {@code market_id} 是数据库自增主键，公司增删会留空洞、整批换号
 *       （见 {@code docs/commands.md} §3），<b>只能每次从 API 现读，读不到就是 -1，
 *       不许拿上一次的值顶上</b>。</li>
 *   <li>{@code changePct} / {@code totalChangePct}：{@code NaN} = 未知。
 *       这两个量取 0 是合法值（{@code ↑ 0.00%}），不能用 0 当未知。</li>
 *   <li>{@code status}：空串 = 未知。</li>
 *   <li>{@code availableShares}：{@code -1} = 未知。</li>
 * </ul>
 *
 * <p>本类<b>不引用任何 {@code net.minecraft} 类型</b>，所以能脱离游戏跑离线测试。
 */
public record CompanyView(
        String name,
        int marketId,
        int apiId,
        double price,
        String priceRaw,
        double marketCap,
        String marketCapRaw,
        double changePct,
        double totalChangePct,
        String status,
        int risk,
        long availableShares,
        Source source) {

    /** 数据是哪条路来的。用来解释「这条价格为什么和那条不一样」。 */
    public enum Source { API, COMMAND, CONTAINER }

    /** 编号未知。 */
    public static final int ID_UNKNOWN = -1;

    /** 可用股数未知。 */
    public static final long SHARES_UNKNOWN = -1L;

    /** 状态的实测原文。 */
    public static final String STATUS_TRADING = "交易中";

    /** 状态的实测原文。 */
    public static final String STATUS_BANKRUPT = "已破产";

    public CompanyView {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("公司名不能为空");
        }
    }

    // ------------------------------------------------------------------
    // 判据（界面与执行层都只问这几个问题，不要各自写一遍）
    // ------------------------------------------------------------------

    /** 是否拿到了可下单用的编号。 */
    public boolean marketIdKnown() {
        return marketId >= 0;
    }

    /** 是否拿到了可请求 K 线的 API id（与 {@link #marketId} 不是同一套）。 */
    public boolean apiIdKnown() {
        return apiId >= 0;
    }

    /**
     * 能否下单。**这是 L2 一键买卖唯一的放行判据**：
     * 编号拿到了（当次从 API 读的）且公司没破产，才允许拼命令。
     * 破产公司点了也白点——实测服务器回 {@code 您不能购买已破产公司的股票。} 且不扣钱。
     */
    public boolean tradable() {
        return marketIdKnown() && !bankrupt();
    }

    public boolean bankrupt() {
        return STATUS_BANKRUPT.equals(status);
    }

    public boolean priceKnown() {
        return price > 0;
    }

    public boolean marketCapKnown() {
        return !Double.isNaN(marketCap) && marketCap > 0;
    }

    public boolean availableSharesKnown() {
        return availableShares >= 0;
    }

    // ------------------------------------------------------------------
    // 合并
    // ------------------------------------------------------------------

    /**
     * 用 {@code newer} 盖在自己身上：<b>逐字段「新的有效才覆盖」</b>。
     *
     * <p>为什么不是整条替换：一条命令回执只知道名字 + 价格 + 状态，
     * 不知道 {@code market_id}；而 API 只知道 {@code market_id} 和价格。
     * 整条替换会把另一条路辛苦拿到的字段冲掉。
     */
    public CompanyView overlay(CompanyView newer) {
        if (newer == null) {
            return this;
        }
        return new CompanyView(
                name,
                newer.marketIdKnown() ? newer.marketId : marketId,
                newer.apiId >= 0 ? newer.apiId : apiId,
                newer.priceKnown() ? newer.price : price,
                notBlank(newer.priceRaw) ? newer.priceRaw : priceRaw,
                newer.marketCapKnown() ? newer.marketCap : marketCap,
                notBlank(newer.marketCapRaw) ? newer.marketCapRaw : marketCapRaw,
                !Double.isNaN(newer.changePct) ? newer.changePct : changePct,
                !Double.isNaN(newer.totalChangePct) ? newer.totalChangePct : totalChangePct,
                notBlank(newer.status) ? newer.status : status,
                newer.risk > 0 ? newer.risk : risk,
                newer.availableSharesKnown() ? newer.availableShares : availableShares,
                newer.source != null ? newer.source : source);
    }

    /** 换一个数据源标签，字段不动。 */
    public CompanyView withSource(Source newSource) {
        return new CompanyView(name, marketId, apiId, price, priceRaw, marketCap, marketCapRaw,
                changePct, totalChangePct, status, risk, availableShares, newSource);
    }

    /** 换本轮涨跌幅，其余不动。聊天增量没有现成涨跌时用上一口价反推。 */
    public CompanyView withChangePct(double newChangePct) {
        return new CompanyView(name, marketId, apiId, price, priceRaw, marketCap, marketCapRaw,
                newChangePct, totalChangePct, status, risk, availableShares, source);
    }

    /**
     * 用上一口价算本轮 {@code changePct = (new-old)/old*100}。
     * 旧价未知则保持 {@code NaN}（{@link #overlay} 会留下原来的涨跌幅）。
     */
    public CompanyView withRoundChangeFrom(CompanyView previous) {
        if (previous == null || !(previous.price > 0) || !Double.isFinite(price) || price < 0) {
            return this;
        }
        return withChangePct((price - previous.price) / previous.price * 100.0);
    }

    /**
     * 换掉编号，其余不动。只该由 {@link StockSnapshot#withIdsFrom} 调用——
     * 那是唯一允许给编号赋值的地方。
     */
    public CompanyView withMarketId(int newMarketId) {
        return new CompanyView(name, newMarketId, apiId, price, priceRaw, marketCap, marketCapRaw,
                changePct, totalChangePct, status, risk, availableShares, source);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
