package cn.myflycat.bcstock.data;

/**
 * 钱包余额。数据来自聊天命令 {@code /bal}（实测：
 * {@code [帕拉伦联邦中央银行] 资金: $45,108.13 帕元}）。
 *
 * <p>公开 API 的个人资金线<b>已死</b>（{@code latest_cash_at} 停在 2026-07-08），
 * 所以这是唯一来源；拿不到就是 {@link #UNKNOWN}，<b>不许拿旧值顶上</b>——
 * 下单前的「余额够不够」判断宁可拒绝，不能算错。
 */
public record WalletView(double balance) {

    /** 余额未知。用 NaN 而不是 0/负数，避免被当成"余额 0 元"而误判"钱不够"。 */
    public static final WalletView UNKNOWN = new WalletView(Double.NaN);

    public boolean known() {
        return !Double.isNaN(balance);
    }

    /** 已知就返回余额，未知返回 0（只给"显示成 0.00"这种场合用，不要拿去做判断）。 */
    public double orZero() {
        return known() ? balance : 0.0;
    }
}
