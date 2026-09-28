package cn.myflycat.bcstock.data;

/**
 * 行情「最近一次成功写入」的时刻（内存）。渲染只读这里，不许碰磁盘。
 *
 * <p>来源可以是当次 API 成功，也可以是启动时从 {@code market-cache.json} 恢复。
 * 超过 {@link #STALE_MS} 仍继续显示，但文案更醒目——一凡宁可看旧数据也不要空白。
 */
public final class MarketDataAge {

    /** 6 小时：再旧也继续显示，只是标注「较旧」。 */
    public static final long STALE_MS = 6L * 60L * 60L * 1000L;

    private static volatile long companiesAtMs = Long.MIN_VALUE;

    private MarketDataAge() {
    }

    public static void setCompaniesAtMs(long ms) {
        companiesAtMs = ms;
    }

    public static long companiesAtMs() {
        return companiesAtMs;
    }

    public static boolean known() {
        return companiesAtMs > 0L;
    }

    /** 测试复位。 */
    public static void resetForTest() {
        companiesAtMs = Long.MIN_VALUE;
    }

    /**
     * 人话年龄。未知 → 空串。
     * 例：{@code 数据 8 分钟前} / {@code 数据较旧（8 小时前）}。
     */
    public static String formatLabel(long nowMs) {
        if (!known()) {
            return "";
        }
        long age = Math.max(0L, nowMs - companiesAtMs);
        String human = humanize(age);
        if (age >= STALE_MS) {
            return "数据较旧（" + human + "前）";
        }
        return "数据 " + human + "前";
    }

    static String humanize(long ageMs) {
        long sec = ageMs / 1000L;
        if (sec < 60) {
            return Math.max(1, sec) + " 秒";
        }
        long min = sec / 60L;
        if (min < 60) {
            return min + " 分钟";
        }
        long hour = min / 60L;
        if (hour < 48) {
            return hour + " 小时";
        }
        long day = hour / 24L;
        return day + " 天";
    }
}
