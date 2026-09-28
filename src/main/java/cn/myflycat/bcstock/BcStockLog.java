package cn.myflycat.bcstock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全 mod 唯一的日志出口：每一行都带 {@value #PREFIX} 前缀，方便事后从
 * {@code logs/latest.log} 里 grep 出来。
 *
 * <p>消息体用 slf4j 的 {@code {}} 占位符，不要自己拼字符串——参数会被延迟求值，
 * 探测这种"每格一行、平时不打印"的场景下开销可以忽略。
 */
public final class BcStockLog {

    /** 日志前缀。写死成常量，避免各处手打不一致。 */
    public static final String PREFIX = "[bcstock]";

    private static final Logger LOGGER = LoggerFactory.getLogger("bcstock");

    private BcStockLog() {
    }

    public static void info(String msg, Object... args) {
        LOGGER.info(PREFIX + " " + msg, args);
    }

    public static void warn(String msg, Object... args) {
        LOGGER.warn(PREFIX + " " + msg, args);
    }

    public static void error(String msg, Object... args) {
        LOGGER.error(PREFIX + " " + msg, args);
    }

    public static void debug(String msg, Object... args) {
        LOGGER.debug(PREFIX + " " + msg, args);
    }
}
