package cn.myflycat.bcstock.ui;

import java.util.Locale;

/**
 * 数值显示规则（{@code docs/design/ui-spec.md} §7）。不引用 {@code net.minecraft}。
 */
public final class BoardFormat {

    public static final String UNKNOWN = "--";
    public static final String DASH = "-";
    public static final String BANKRUPT = "已破产";

    private BoardFormat() {
    }

    /** {@code 1.30 K} 已经是真实值 1300 时显示 {@code 1300.00}；≥ 1e6 加千分位。 */
    public static String price(double value) {
        if (!(value > 0) || Double.isNaN(value)) {
            return UNKNOWN;
        }
        if (value >= 1_000_000d) {
            return String.format(Locale.ROOT, "%,.2f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static String pct(double value) {
        if (Double.isNaN(value)) {
            return UNKNOWN;
        }
        String sign = value > 0 ? "+" : "";
        return sign + String.format(Locale.ROOT, "%.2f%%", value);
    }

    public static String shares(long n) {
        if (n <= 0) {
            return DASH;
        }
        if (n >= 1_000_000L) {
            return String.format(Locale.ROOT, "%,d", n);
        }
        return Long.toString(n);
    }
}
