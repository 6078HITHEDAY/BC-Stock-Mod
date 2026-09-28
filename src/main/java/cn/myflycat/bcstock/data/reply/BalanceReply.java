package cn.myflycat.bcstock.data.reply;

import cn.myflycat.bcstock.data.WalletView;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /bal} 回执。实测原文（2026-09-26）：
 * {@code [帕拉伦联邦中央银行] 资金: $45,108.13 帕元}
 *
 * <p>正则对着 {@code docs/commands.md} §8，要求有 {@code $} 和 {@code 帕元}，
 * 免得把别的带「资金」的广播误认成余额。
 */
public final class BalanceReply {

    private static final Pattern MONEY = Pattern.compile(
            "资金:\\s*\\$([\\d,]+\\.\\d{2})\\s*帕元");

    private BalanceReply() {
    }

    public static Optional<WalletView> parse(String line) {
        String n = ReplyText.normalize(line);
        Matcher m = MONEY.matcher(n);
        if (!m.find()) {
            return Optional.empty();
        }
        double amount = ReplyText.parsePlainNumber(m.group(1));
        if (Double.isNaN(amount)) {
            return Optional.empty();
        }
        return Optional.of(new WalletView(amount));
    }

    /** 在一组行里找第一条能解析的余额。 */
    public static Optional<WalletView> parse(List<String> lines) {
        if (lines == null) {
            return Optional.empty();
        }
        for (String line : lines) {
            Optional<WalletView> found = parse(line);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
