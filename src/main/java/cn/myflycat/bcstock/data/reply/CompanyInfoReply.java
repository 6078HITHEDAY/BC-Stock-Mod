package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.CompanyView;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /invest company info <market_id>} 回执。
 *
 * <p>★ {@code Id:} 必须和请求的 {@code market_id} 对得上，对不上就丢——
 * 回执是多行慢慢来的，串台时宁可不要，也不能把 PR雪地 当成月港控股。
 *
 * <p>聊天回执没有 API {@code id}，所以 {@link CompanyView#apiId} 是
 * {@link CompanyView#ID_UNKNOWN}。
 */
public final class CompanyInfoReply {

    private static final Pattern ID = Pattern.compile("Id:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS = Pattern.compile("状态:\\s*(.+)");
    private static final Pattern RISK = Pattern.compile("风险等级:\\s*(\\d)");
    private static final Pattern AVAILABLE = Pattern.compile("可用股数:\\s*(\\d+)");
    private static final Pattern HISTORY_LINE = Pattern.compile("^\\s*([+-]\\d+(?:\\.\\d+)?)%\\s*$");

    private CompanyInfoReply() {
    }

    public static Optional<CompanyView> parse(List<String> lines, int expectedMarketId) {
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        List<String> normalized = ReplyText.normalizeAll(lines);
        for (String line : normalized) {
            if (ReplyText.isError(line)) {
                return Optional.empty();
            }
        }

        Integer id = null;
        String name = null;
        String status = "";
        double price = Double.NaN;
        String priceRaw = "";
        int risk = 0;
        long available = CompanyView.SHARES_UNKNOWN;

        for (String line : normalized) {
            if (line.isEmpty() || ReplyText.isBlockStart(line) || ReplyText.isBlockEnd(line)) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                continue;
            }
            if (line.startsWith("历史记录")) {
                continue;
            }
            if (line.regionMatches(true, 0, "Id:", 0, 3)) {
                Matcher idMatch = ID.matcher(line);
                if (idMatch.find()) {
                    try {
                        id = Integer.parseInt(idMatch.group(1));
                    } catch (NumberFormatException ignored) {
                        return Optional.empty();
                    }
                }
                continue;
            }
            Matcher statusMatch = STATUS.matcher(line);
            if (line.startsWith("状态:") && statusMatch.find()) {
                status = statusMatch.group(1).trim();
                continue;
            }
            if (line.startsWith("价格:")) {
                price = ReplyText.parsePrice(line);
                priceRaw = ReplyText.priceRaw(line);
                continue;
            }
            Matcher riskMatch = RISK.matcher(line);
            if (line.startsWith("风险等级:") && riskMatch.find()) {
                risk = Integer.parseInt(riskMatch.group(1));
                continue;
            }
            Matcher availMatch = AVAILABLE.matcher(line);
            if (line.startsWith("可用股数:") && availMatch.find()) {
                available = Long.parseLong(availMatch.group(1));
                continue;
            }
            if (HISTORY_LINE.matcher(line).matches()) {
                continue;
            }
            if (name == null
                    && !line.contains("帕拉伦股市")
                    && !line.startsWith("Id:")
                    && !line.startsWith("状态:")
                    && !line.startsWith("价格:")
                    && !line.startsWith("风险")
                    && !line.startsWith("可用股数")) {
                name = line;
            }
        }

        if (id == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        if (id != expectedMarketId) {
            return Optional.empty();
        }
        if (Double.isNaN(price)) {
            return Optional.empty();
        }

        // 历史 5 条是「最近几次涨跌」，不是「当前涨跌幅」。当前涨跌未知 → NaN。
        return Optional.of(new CompanyView(
                name,
                id,
                CompanyView.ID_UNKNOWN,
                price,
                priceRaw,
                0.0,
                "",
                Double.NaN,
                Double.NaN,
                status,
                risk,
                available,
                CompanyView.Source.COMMAND));
    }
}
