package cn.myflycat.bcstock.probe;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 同因日志节流：同一 key 在 {@code minGapMs} 内只放行一行。
 * 参照 {@code CommandGateway.REJECT_LOG_MIN_GAP_MS} 的既有做法——只限频，不删日志。
 *
 * <p>背景（稳定性计划 S6）：{@code InvestProbe} 每开一个非股市界面就打一行，
 * 聊天栏 / 背包 / 其它 mod 的界面都会刷屏，把真正有用的行埋掉。
 *
 * <p>纯逻辑，可离线测；不引用 {@code net.minecraft}。
 */
public final class LogThrottle {

    /** 默认 15 秒——与命令网关拒绝日志同一量级。 */
    public static final long DEFAULT_GAP_MS = 15_000L;

    private final long minGapMs;
    private final LongSupplier clock;
    private final Map<String, Long> lastAt = new HashMap<>();

    public LogThrottle() {
        this(DEFAULT_GAP_MS, System::currentTimeMillis);
    }

    public LogThrottle(long minGapMs, LongSupplier clock) {
        this.minGapMs = Math.max(0L, minGapMs);
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
    }

    /** true = 这一行该打；false = 同一 key 刚刚打过，压掉。 */
    public synchronized boolean allow(String key) {
        String k = (key == null) ? "" : key;
        long now = clock.getAsLong();
        Long last = lastAt.get(k);
        if (last != null && now - last < minGapMs) {
            return false;
        }
        lastAt.put(k, now);
        return true;
    }

    /** 测试 / 换服时清空历史。 */
    public synchronized void reset() {
        lastAt.clear();
    }

    /** 清理超过一个窗口的 key，防止不同标题无限累积（标题种类很多时才有意义）。 */
    public synchronized void prune() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> it = lastAt.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() >= minGapMs) {
                it.remove();
            }
        }
    }
}
