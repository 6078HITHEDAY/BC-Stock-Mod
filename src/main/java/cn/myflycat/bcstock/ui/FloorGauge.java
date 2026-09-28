package cn.myflycat.bcstock.ui;

/**
 * 地板条位置 / 颜色。纯函数。
 *
 * <p>服务端 {@code GET /api/floors} 直接给 floor / distance_pct 后，
 * {@link #MODE} 默认 {@link Mode#FLOOR}。某公司不在 floors 里 → 未知，不许当 0 / 安全。
 */
public final class FloorGauge {

    public enum Mode { FLOOR, HISTORY_LOW }

    /** 服务端已给真地板；某公司无数据时 UI 显示未知。 */
    public static final Mode MODE = Mode.FLOOR;

    /** 距地板 &lt; 1% 视为危险（与 B2 提醒规则一致）。 */
    public static final double DANGER_DISTANCE_PCT = 1.0;

    private FloorGauge() {
    }

    /**
     * 当前价在 [low, high] 上的 0..1 位置。低/高无效 → NaN。
     * 等于 low → 0；等于 high → 1；中间线性。
     */
    public static double position(double price, double low, double high) {
        if (Double.isNaN(price) || Double.isNaN(low) || Double.isNaN(high) || !(high > low)) {
            return Double.NaN;
        }
        if (price <= low) {
            return 0.0;
        }
        if (price >= high) {
            return 1.0;
        }
        return (price - low) / (high - low);
    }

    public static String label() {
        return MODE == Mode.FLOOR ? "距地板" : "距历史最低价";
    }

    public static boolean danger(double position) {
        return !Double.isNaN(position) && position <= 0.01;
    }

    /** 服务端 distance_pct 危险：&lt; 1% 或 in_danger_zone。 */
    public static boolean dangerDistance(double distancePct, boolean inDangerZone) {
        if (inDangerZone) {
            return true;
        }
        return !Double.isNaN(distancePct) && distancePct < DANGER_DISTANCE_PCT;
    }

    /** 危险区闪烁：时间取模，不起线程。 */
    public static boolean blinkOn(long nowMs) {
        return (nowMs % 1000L) < 500L;
    }

    public static int barColor(double position, long nowMs) {
        if (danger(position)) {
            return blinkOn(nowMs) ? UiPalette.WARN : UiPalette.BLINK_OFF;
        }
        if (Double.isNaN(position)) {
            return UiPalette.MUTED;
        }
        if (position >= 0.75) {
            return UiPalette.UP;
        }
        if (position <= 0.25) {
            return UiPalette.DOWN;
        }
        return UiPalette.MUTED;
    }

    /**
     * 按服务端 distance_pct 上色。NaN → 未知色；危险闪烁；
     * ≥10% 偏安全绿；≤3% 偏警戒红。
     */
    public static int barColorForDistance(double distancePct, boolean inDangerZone, long nowMs) {
        if (Double.isNaN(distancePct)) {
            return UiPalette.MUTED;
        }
        if (dangerDistance(distancePct, inDangerZone)) {
            return blinkOn(nowMs) ? UiPalette.WARN : UiPalette.BLINK_OFF;
        }
        if (distancePct >= 10.0) {
            return UiPalette.UP;
        }
        if (distancePct <= 3.0) {
            return UiPalette.DOWN;
        }
        return UiPalette.MUTED;
    }
}
