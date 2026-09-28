package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /invest portfolio} 回执。实测块（2026-09-26）：
 * <pre>
 * -=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-
 * 月港控股
 *
 * 股票: 111
 * 总价值: 4703.98
 * [Details]
 *
 * -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
 * </pre>
 *
 * <p>聊天回执<b>没有</b> {@code companyId}、没有平均买入价。
 * {@code marketId} 填 {@link CompanyView#ID_UNKNOWN}，均价填 {@code NaN}
 * （这条回执里就没有这个字段，不是「均价是 0」——GUI 叠上来才会有成本）。
 *
 * <p>空仓：整个块里没有任何公司 → 返回空列表（「确认没有持仓」），
 * 不是 {@code empty}（那表示解析失败 / 超时）。
 */
public final class PortfolioReply {

    private static final Pattern SHARES = Pattern.compile("股票:\\s*(\\d+)");
    private static final Pattern TOTAL = Pattern.compile("总价值:\\s*([\\d.]+)");

    private PortfolioReply() {
    }

    /**
     * @return 解析成功（含确认空仓）→ 列表；错误前缀或完全对不上 → empty
     */
    public static Optional<List<HoldingView>> parse(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        List<String> normalized = ReplyText.normalizeAll(lines);
        for (String line : normalized) {
            if (ReplyText.isNoHoldingsMessage(line)) {
                // 真机空仓不发块，只回这一句
                return Optional.of(List.of());
            }
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

        List<HoldingView> holdings = new ArrayList<>();
        String name = null;
        Long shares = null;
        Double value = null;

        for (String line : normalized) {
            if (line.isEmpty() || ReplyText.isBlockStart(line) || ReplyText.isBlockEnd(line)) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                continue;
            }
            if (line.startsWith("股票:")) {
                Matcher shareMatch = SHARES.matcher(line);
                if (shareMatch.find()) {
                    shares = parseLong(shareMatch.group(1));
                }
                continue;
            }
            Matcher totalMatch = TOTAL.matcher(line);
            if (line.startsWith("总价值:") && totalMatch.find()) {
                value = ReplyText.parsePlainNumber(totalMatch.group(1));
                if (name != null && shares != null && value != null && !Double.isNaN(value)) {
                    holdings.add(new HoldingView(
                            name, CompanyView.ID_UNKNOWN, "", shares, value, Double.NaN));
                }
                name = null;
                shares = null;
                value = null;
                continue;
            }
            if (!line.startsWith("股票:") && !line.startsWith("总价值:")
                    && !line.contains("帕拉伦股市")
                    && !isNoiseLine(line)) {
                name = line;
            }
        }
        return Optional.of(List.copyOf(holdings));
    }

    /** 进退服 / 暂离广播，不能当成公司名。 */
    private static boolean isNoiseLine(String line) {
        return line.startsWith("*")
                || line.contains("加入了游戏")
                || line.contains("退出了游戏")
                || line.contains("暂时离开");
    }

    private static Long parseLong(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
