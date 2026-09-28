package cn.myflycat.bcstock.data;

import java.util.Arrays;
import java.util.Locale;

/**
 * 一家公司的 K 线收盘价序列。折线只取 close（OHLC 通常四值相同，见 api-reference）。
 * 不引用 {@code net.minecraft}。
 */
public final class KlineSeries {

    private final int apiId;
    private final String interval;
    private final double[] closes;
    private final long[] times;

    public KlineSeries(int apiId, String interval, double[] closes, long[] times) {
        if (closes == null || times == null || closes.length != times.length) {
            throw new IllegalArgumentException("closes/times 长度必须一致");
        }
        this.apiId = apiId;
        this.interval = (interval == null || interval.isBlank()) ? "15m" : interval;
        this.closes = Arrays.copyOf(closes, closes.length);
        this.times = Arrays.copyOf(times, times.length);
    }

    public int apiId() {
        return apiId;
    }

    public String interval() {
        return interval;
    }

    public int pointCount() {
        return closes.length;
    }

    public double[] closes() {
        return Arrays.copyOf(closes, closes.length);
    }

    public long[] times() {
        return Arrays.copyOf(times, times.length);
    }

    public double maxClose() {
        if (closes.length == 0) {
            return Double.NaN;
        }
        double m = closes[0];
        for (double v : closes) {
            if (v > m) {
                m = v;
            }
        }
        return m;
    }

    public double minClose() {
        if (closes.length == 0) {
            return Double.NaN;
        }
        double m = closes[0];
        for (double v : closes) {
            if (v < m) {
                m = v;
            }
        }
        return m;
    }

    /**
     * 把每个收盘价映射到图表矩形内的像素点（左上为原点向下为正）。
     * 点数必须等于 {@link #pointCount()}。
     */
    public int[][] screenPoints(int x, int y, int w, int h) {
        int n = closes.length;
        int[][] out = new int[n][2];
        if (n == 0 || w <= 1 || h <= 1) {
            return out;
        }
        double lo = minClose();
        double hi = maxClose();
        double span = hi - lo;
        if (!(span > 0)) {
            span = 1.0;
        }
        for (int i = 0; i < n; i++) {
            int px = (n == 1) ? x : x + (int) Math.round((double) i * (w - 1) / (n - 1));
            double t = (closes[i] - lo) / span;
            int py = y + (h - 1) - (int) Math.round(t * (h - 1));
            out[i][0] = px;
            out[i][1] = py;
        }
        return out;
    }

    public String maxLabel() {
        return format(maxClose());
    }

    public String minLabel() {
        return format(minClose());
    }

    private static String format(double v) {
        if (Double.isNaN(v)) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
