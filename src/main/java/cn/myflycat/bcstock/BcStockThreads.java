package cn.myflycat.bcstock;

import java.util.concurrent.ThreadFactory;

/**
 * 后台线程的统一创建点：命名 + daemon + <b>兜底 uncaught handler</b>。
 *
 * <p>2026-09-27 线上 5 次 {@code Uncaught exception in thread "bcstock-api" /
 * "bcstock-market-poll"} 全是裸线程/裸线程池：异常直接冒到 {@code ThreadGroup}，
 * 日志里只有一行栈，没有线程语义，也没法说清是哪一轮刷新作废了。
 * 从这里建线程，异常统一进 {@link BcStockLog#error}，并带上线程名。
 *
 * <p>只做线程生命周期，不引用 {@code net.minecraft}，可离线测。
 */
public final class BcStockThreads {

    private BcStockThreads() {
    }

    /** 建一个 daemon 线程并挂好 uncaught handler。 */
    public static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler(BcStockThreads::logUncaught);
        return t;
    }

    /** 线程池工厂：每个线程同名、daemon、带 uncaught handler。 */
    public static ThreadFactory daemonFactory(String name) {
        return body -> daemon(name, body);
    }

    /** 测试可直接调用：必须只记日志、不抛。 */
    static void logUncaught(Thread thread, Throwable error) {
        String name = (thread == null) ? "unknown" : thread.getName();
        try {
            BcStockLog.error("Uncaught exception in thread \"{}\"", name, error);
        } catch (RuntimeException ignored) {
            // 兜底处理器自己绝不能再抛。
        }
    }
}
