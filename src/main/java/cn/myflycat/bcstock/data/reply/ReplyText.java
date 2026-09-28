package cn.myflycat.bcstock.data.reply;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令回执的共用清洗与切块。规则对着 {@code docs/commands.md} §8，
 * 不是对着第三方插件抄的。
 */
public final class ReplyText {

    /** {@code §} 颜色码。游戏里 getString() 偶尔还留着，先剥掉再匹配。 */
    private static final Pattern FORMAT_CODE = Pattern.compile("§.");

    /**
     * 块开始。实测原文：
     * {@code -=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-}
     */
    private static final Pattern BLOCK_START =
            Pattern.compile("^[-=]{6,}\\s*\\[帕拉伦股市\\].*");

    /** 块结束：一长串 {@code -=}，中间没有 {@code [帕拉伦股市]}。 */
    private static final Pattern BLOCK_END =
            Pattern.compile("^[-=]{10,}$");

    /** 错误 / 提示。前缀是识别「这是回执不是广播」的关键。 */
    public static final String ERROR_PREFIX = "帕拉伦股市 > ";

    /**
     * 空仓短回执（真机 2026-09-27）：{@code 帕拉伦股市 > 您没有任何股票。}
     * 不是解析失败——服务端确认零持仓，应用空列表覆盖本地快照。
     */
    public static final String NO_HOLDINGS_SUFFIX = "您没有任何股票";

    /**
     * 全服价格刷新广播（真机 latest.log）：{@code 帕拉伦股市 > 所有商业股票已更新。}
     * 末尾句号可有可无。
     */
    public static final String MARKET_UPDATED_BODY = "所有商业股票已更新";

    private static final Pattern PRICE = Pattern.compile(
            "价格:\\s*([\\d,]+(?:\\.\\d+)?)\\s*([KMB])?",
            Pattern.CASE_INSENSITIVE);

    private ReplyText() {
    }

    /** 剥颜色码、行首的 MCC {@code ▌}、首尾空白。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = FORMAT_CODE.matcher(raw).replaceAll("");
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '▌' || s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(i).trim();
    }

    public static List<String> normalizeAll(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            out.add(normalize(line));
        }
        return out;
    }

    public static boolean isBlockStart(String line) {
        return BLOCK_START.matcher(normalize(line)).matches();
    }

    public static boolean isBlockEnd(String line) {
        String n = normalize(line);
        return BLOCK_END.matcher(n).matches() && !n.contains("[帕拉伦股市]");
    }

    public static boolean isError(String line) {
        return normalize(line).startsWith(ERROR_PREFIX);
    }

    /**
     * 价格刷新广播。必须在当成错误前缀之前认出来，不能塞进当前命令的 collected。
     */
    public static boolean isMarketUpdated(String line) {
        String n = normalize(line);
        if (!n.startsWith(ERROR_PREFIX)) {
            return false;
        }
        String rest = n.substring(ERROR_PREFIX.length()).trim();
        if (rest.endsWith("。") || rest.endsWith(".")) {
            rest = rest.substring(0, rest.length() - 1).trim();
        }
        return MARKET_UPDATED_BODY.equals(rest);
    }

    /**
     * 「您没有任何股票」——带 {@link #ERROR_PREFIX}，但是成功空仓，不是失败。
     */
    public static boolean isNoHoldingsMessage(String line) {
        String n = normalize(line);
        return n.startsWith(ERROR_PREFIX) && n.contains(NO_HOLDINGS_SUFFIX);
    }

    /**
     * 读 {@code 价格: 1.54 K} / {@code 价格: 42.38}。
     * 单位后缀前有空格；换算后的真实值。读不到返回 {@code NaN}。
     */
    public static double parsePrice(String text) {
        if (text == null) {
            return Double.NaN;
        }
        Matcher m = PRICE.matcher(text);
        if (!m.find()) {
            return Double.NaN;
        }
        double number = parsePlainNumber(m.group(1));
        if (Double.isNaN(number)) {
            return Double.NaN;
        }
        String unit = m.group(2);
        if (unit == null || unit.isBlank()) {
            return number;
        }
        return switch (unit.toUpperCase()) {
            case "K" -> number * 1_000.0;
            case "M" -> number * 1_000_000.0;
            case "B" -> number * 1_000_000_000.0;
            default -> number;
        };
    }

    /** 去千分位逗号再 parse。读不到返回 {@code NaN}。 */
    public static double parsePlainNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** 从 {@code 价格: 1.54 K} 里抽出原始文本（界面以后要显示缩写时用）。 */
    public static String priceRaw(String text) {
        Matcher m = PRICE.matcher(text == null ? "" : text);
        if (!m.find()) {
            return "";
        }
        String unit = m.group(2);
        if (unit == null || unit.isBlank()) {
            return m.group(1);
        }
        return m.group(1) + " " + unit.toUpperCase();
    }
}
