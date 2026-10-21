package cn.myflycat.bcstock.data;

/**
 * 非有限值的唯一判据。所有落盘路径（{@link SnapshotJournal}、{@link MarketCache}）
 * 必须走这里，不许各写一份——2026-09-27 的线上崩溃就是两份防线漂移出来的。
 *
 * <p>约定：价格类字段非有限 → {@code 0.0}（「没有可显示的数」，与界面一致）；
 * 涨跌/市值/均价/当前价值类字段非有限 → {@code null}（JSON 里不写，读回是 NaN「未知」）。
 * 绝不允许 {@code NaN} / {@code ±Infinity} 出现在 Gson 产物里——Gson 会直接抛
 * {@code IllegalArgumentException}，把整轮刷新打断。
 *
 * <p>不引用 {@code net.minecraft}，可离线测。
 */
public final class FiniteValues {

    private FiniteValues() {
    }

    /** 价格类：非有限按 0（界面「—」的既有约定），有限原样返回。 */
    public static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    /** 可选数值类：非有限按 {@code null}（= 未知），有限原样装箱。 */
    public static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    /** 读回：{@code null} 或非有限 → {@code NaN}（未知）。 */
    public static double orNaN(Double value) {
        return (value != null && Double.isFinite(value)) ? value : Double.NaN;
    }
}
