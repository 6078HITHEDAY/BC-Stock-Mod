package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 买卖账本探针。测试期一直 INFO，带确认序号和距确认的毫秒。
 * 不 import {@code net.minecraft}。
 */
public final class LedgerProbe {

    private static final AtomicLong SEQ = new AtomicLong();
    private static volatile long currentSeq;
    private static volatile long confirmAtMs;

    private LedgerProbe() {
    }

    /** 点确认时调一次。返回本笔序号。 */
    public static long beginConfirm() {
        long seq = SEQ.incrementAndGet();
        currentSeq = seq;
        confirmAtMs = System.currentTimeMillis();
        return seq;
    }

    public static long currentSeq() {
        return currentSeq;
    }

    public static void info(String msg, Object... args) {
        long seq = currentSeq;
        long elapsed = (confirmAtMs <= 0L) ? 0L : Math.max(0L, System.currentTimeMillis() - confirmAtMs);
        Object[] all = new Object[2 + (args == null ? 0 : args.length)];
        all[0] = seq;
        all[1] = elapsed;
        if (args != null && args.length > 0) {
            System.arraycopy(args, 0, all, 2, args.length);
        }
        BcStockLog.info("账本 #{} +{}ms " + msg, all);
    }

    /** 测试用。 */
    public static void resetForTest() {
        currentSeq = 0L;
        confirmAtMs = 0L;
    }
}
