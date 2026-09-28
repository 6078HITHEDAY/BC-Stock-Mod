package cn.myflycat.bcstock.data.reply;

import java.util.List;
import java.util.Optional;

/**
 * {@code /invest buy|sell} 回执。
 *
 * <p>真机 2026-09-27 实测成功文案也带 {@link ReplyText#ERROR_PREFIX}，
 * 必须先认成功再当错误，否则会误报「交易回执是错误前缀」。
 */
public final class TradeReply {

    private TradeReply() {
    }

    /**
     * @return 认出成功 / 失败 → {@code Optional.of}；对不上 → empty
     */
    public static Optional<Result> parse(List<String> lines) {
        if (lines == null) {
            return Optional.empty();
        }
        for (String line : ReplyText.normalizeAll(lines)) {
            if (isSuccess(line)) {
                return Optional.of(new Result(true, line));
            }
            if (ReplyText.isError(line)) {
                return Optional.of(new Result(false, line));
            }
        }
        return Optional.empty();
    }

    /**
     * 真机成功：
     * {@code 帕拉伦股市 > 您已成功购买了 1 股股票。} /
     * {@code 帕拉伦股市 > 您已成功以 105.26 的价格出售了 1 股股票。}
     */
    public static boolean isSuccess(String line) {
        String n = ReplyText.normalize(line);
        if (!n.startsWith(ReplyText.ERROR_PREFIX)) {
            return false;
        }
        return n.contains("您已成功购买")
                || (n.contains("您已成功") && n.contains("出售"));
    }

    public record Result(boolean ok, String message) {
    }
}
