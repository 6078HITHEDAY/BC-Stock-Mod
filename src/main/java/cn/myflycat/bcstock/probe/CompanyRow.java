package cn.myflycat.bcstock.probe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从公司格子的 name + lore 里抠出来的一行。
 *
 * <p><b>这一版只做「按标签取原文」，不做任何数值解释。</b>换句话说：
 * <ul>
 *   <li>不把 {@code 139.20 M} 换算成 {@code 1.392e8}（单位前有空格，M=1e6 / K=1e3）</li>
 *   <li>不把 {@code ↓ 60.63%} 变成 {@code -60.63}（箭头表正负，数字本身不带符号）</li>
 *   <li>不处理 {@code ↓ NaN%}（空仓时出现，要当 0 还是忽略，得先确定语义）</li>
 * </ul>
 * 这些换算属于 M1，而且必须拿<b>真实的游戏内 dump</b> 当样本再动——{@code data/gui/} 那几份是
 * MCC 的拼接格式（name 与 lore 用 {@code " | "} 缝在一起），跟 {@code ItemStack} 的真实结构不完全
 * 同构，照着它写数值解析会踩坑。M0 的目标只是证明「读得到」。
 *
 * <p>字段值都可能是 null（格式变了、字段缺失、格子不是公司）。
 * {@link #looksLikeCompany()} 是判断「这格是不是一家公司」的依据。
 *
 * @param slotIndex     在 {@code ScreenHandler.slots} 里的下标
 * @param displayName   公司名，<b>已剥掉尾部那个 {@code [ ↑ 1.18% ]}</b>
 * @param changeText    {@code ↑ 1.18%} / {@code ↓ 28.61%} / {@code ↓ NaN%} 原文，无符号语义
 * @param status        {@code 交易中} / {@code 已破产}
 * @param riskText      {@code 风险度: 2} 的取值部分
 * @param priceText     {@code 股票单价: 29.53} 的取值部分，<b>可能带 K/M 后缀</b>
 * @param marketCapText {@code 市值: 4.72 M} 的取值部分
 * @param totalChangeText {@code 历史总涨跌: ↓ 60.63%} 的取值部分
 */
public record CompanyRow(
        int slotIndex,
        String displayName,
        String changeText,
        String status,
        String riskText,
        String priceText,
        String marketCapText,
        String totalChangeText) {

    /** 名字尾部的涨跌幅，形如 {@code 药水 - 联邦健保 [ ↑ 1.18% ]}。 */
    private static final Pattern NAME_WITH_CHANGE = Pattern.compile("^(.*?)\\s*\\[\\s*(.*?)\\s*]\\s*$");

    /**
     * 从槽位快照抠字段。非公司格子（玻璃板、入口按钮）返回 null。
     *
     * @param slot 已经带 tooltip 的快照
     */
    public static CompanyRow from(ProbedSlot slot) {
        if (slot.empty()) {
            return null;
        }

        String rawName = slot.itemName();
        String displayName = rawName;
        String changeText = null;
        Matcher m = NAME_WITH_CHANGE.matcher(rawName);
        if (m.matches()) {
            displayName = m.group(1).trim();
            changeText = m.group(2).trim();
        }

        String status = null;
        String risk = null;
        String price = null;
        String cap = null;
        String total = null;

        for (String raw : slot.tooltip()) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (status == null && (line.equals("交易中") || line.equals("已破产"))) {
                status = line;
                continue;
            }
            if (risk == null) {
                risk = valueOf(line, "风险度");
                if (risk != null) {
                    continue;
                }
            }
            if (price == null) {
                price = valueOf(line, "股票单价");
                if (price != null) {
                    continue;
                }
            }
            if (cap == null) {
                cap = valueOf(line, "市值");
                if (cap != null) {
                    continue;
                }
            }
            if (total == null) {
                total = valueOf(line, "历史总涨跌");
            }
        }

        CompanyRow row = new CompanyRow(
                slot.index(), displayName, changeText, status, risk, price, cap, total);

        // 认公司的依据：带单价或风险度。玻璃板、入口按钮都没有这两样。
        return row.looksLikeCompany() ? row : null;
    }

    /**
     * 取 {@code <标签>: <值>} 里的值部分。标签不匹配返回 null。
     *
     * <p>用 startsWith 而不是 equals/正则，是因为标签后面可能还有别的修饰；
     * 冒号同时认全角与半角，服务端用的是半角（实测），但别赌。
     */
    private static String valueOf(String line, String label) {
        if (!line.startsWith(label)) {
            return null;
        }
        int colon = line.indexOf(':', label.length());
        if (colon < 0) {
            colon = line.indexOf('：', label.length());
        }
        if (colon < 0) {
            return null;
        }
        String value = line.substring(colon + 1).trim();
        return value.isEmpty() ? null : value;
    }

    /** 这行看起来是不是一家公司（而不是装饰玻璃板 / 入口按钮）。 */
    public boolean looksLikeCompany() {
        return priceText != null || riskText != null;
    }

    /** 一行紧凑摘要，给 M0 的验收日志用。 */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(slotIndex).append("  ").append(displayName);
        append(sb, changeText);
        append(sb, status);
        append(sb, riskText == null ? null : "风险 " + riskText);
        append(sb, priceText == null ? null : "单价 " + priceText);
        append(sb, marketCapText == null ? null : "市值 " + marketCapText);
        append(sb, totalChangeText == null ? null : "总涨跌 " + totalChangeText);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (part != null) {
            sb.append(" | ").append(part);
        }
    }

    /** 内容签名，用来判断两次 dump 之间容器有没有真的变。 */
    String signaturePart() {
        return slotIndex + ":" + displayName + ":" + changeText + ":" + priceText
                + ":" + riskText + ":" + marketCapText + ":" + totalChangeText + ":" + status;
    }
}
