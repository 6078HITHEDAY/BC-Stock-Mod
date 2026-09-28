package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.CompanyView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /invest companies} 回执。对着 {@code docs/commands.md} §5.2：
 * 首页 16 家，每家名称 / 状态 / 价格 / 风险等级 / {@code [Details]}，<b>没有 Id</b>。
 *
 * <p>涨跌幅这条回执里没有 → {@link CompanyView#changePct} 填 {@code NaN}，
 * 由刷新协调器用上一口价算本轮涨跌。
 */
public final class CompaniesReply {

    private static final Pattern STATUS = Pattern.compile("状态:\\s*(.+)");
    private static final Pattern RISK = Pattern.compile("风险等级:\\s*(\\d)");

    private CompaniesReply() {
    }

    /**
     * @return 解析成功且至少一家 → 列表；错误前缀或对不上 → empty
     */
    public static Optional<List<CompanyView>> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        List<String> normalized = ReplyText.normalizeAll(lines);
        for (String line : normalized) {
            if (ReplyText.isError(line)) {
                return Optional.empty();
            }
        }
        boolean sawBlock = false;
        for (String line : normalized) {
            if (ReplyText.isBlockStart(line)) {
                sawBlock = true;
                break;
            }
        }
        if (!sawBlock) {
            return Optional.empty();
        }

        List<CompanyView> out = new ArrayList<>();
        String name = null;
        String status = "";
        double price = Double.NaN;
        String priceRaw = "";
        int risk = 0;

        for (String line : normalized) {
            if (line.isEmpty() || ReplyText.isBlockStart(line) || ReplyText.isBlockEnd(line)) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
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
                if (name != null && !name.isBlank() && !Double.isNaN(price)) {
                    out.add(new CompanyView(
                            name,
                            CompanyView.ID_UNKNOWN,
                            CompanyView.ID_UNKNOWN,
                            price,
                            priceRaw,
                            0.0,
                            "",
                            Double.NaN,
                            Double.NaN,
                            status,
                            risk,
                            CompanyView.SHARES_UNKNOWN,
                            CompanyView.Source.COMMAND));
                }
                name = null;
                status = "";
                price = Double.NaN;
                priceRaw = "";
                risk = 0;
                continue;
            }
            if (!line.contains("帕拉伦股市")
                    && !line.regionMatches(true, 0, "Id:", 0, 3)
                    && !line.startsWith("状态:")
                    && !line.startsWith("价格:")
                    && !line.startsWith("风险")
                    && !line.startsWith("可用股数")
                    && !line.startsWith("历史记录")) {
                name = line;
                status = "";
                price = Double.NaN;
                priceRaw = "";
                risk = 0;
            }
        }

        if (out.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(out));
    }
}
