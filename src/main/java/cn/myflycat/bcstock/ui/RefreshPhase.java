package cn.myflycat.bcstock.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * 行情刷新相位。实测落在每小时的 {@code :03 / :18 / :33 / :48}。
 * 时区钉死上海（服在国内），离线也能算准。
 */
public final class RefreshPhase {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int[] MINUTES = {3, 18, 33, 48};

    private RefreshPhase() {
    }

    public static Instant next(Instant now) {
        ZonedDateTime z = (now == null ? Instant.EPOCH : now).atZone(ZONE);
        for (int minute : MINUTES) {
            ZonedDateTime candidate = z.withMinute(minute).withSecond(0).withNano(0);
            if (candidate.isAfter(z)) {
                return candidate.toInstant();
            }
        }
        return z.plusHours(1).withMinute(MINUTES[0]).withSecond(0).withNano(0).toInstant();
    }

    /**
     * 不晚于 {@code now} 的最近一个相位时刻（整分 :03/:18/:33/:48）。
     * 采集与提醒去重都用 {@link #phaseId}，不要用飘的时间差。
     */
    public static Instant last(Instant now) {
        Instant n = (now == null) ? Instant.EPOCH : now;
        Instant nxt = next(n);
        ZonedDateTime zNext = nxt.atZone(ZONE);
        // 上一个相位 = next 往前一档
        int idx = -1;
        for (int i = 0; i < MINUTES.length; i++) {
            if (MINUTES[i] == zNext.getMinute()) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return n;
        }
        if (idx == 0) {
            return zNext.minusHours(1).withMinute(MINUTES[MINUTES.length - 1])
                    .withSecond(0).withNano(0).toInstant();
        }
        return zNext.withMinute(MINUTES[idx - 1]).withSecond(0).withNano(0).toInstant();
    }

    /** 相位编号：最近相位的 epoch 秒。同一轮刷新共享这个值。 */
    public static long phaseId(Instant now) {
        return last(now).getEpochSecond();
    }

    public static long millisUntil(Instant now) {
        Instant n = (now == null) ? Instant.EPOCH : now;
        return Math.max(0L, Duration.between(n, next(n)).toMillis());
    }

    public static String mmss(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        long mm = total / 60L;
        long ss = total % 60L;
        return String.format(Locale.ROOT, "%02d:%02d", mm, ss);
    }
}
