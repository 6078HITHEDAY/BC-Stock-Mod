package cn.myflycat.bcstock.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确认框文案（ui-spec §5.5）。纯函数。
 * 回车才发、Esc 取消——发送不在这里，避免 UI 自己拼命令。
 */
public final class TradeConfirmDialog {

    private TradeConfirmDialog() {
    }

    public static List<String> lines(TradeDraft draft) {
        return lines(draft, LiveQuoteFlow.Phase.FAILED);
    }

    public static List<String> lines(TradeDraft draft, LiveQuoteFlow.Phase quote) {
        return lines(draft, quote, null);
    }

    public static List<String> lines(TradeDraft draft, LiveQuoteFlow.Phase quote, String status) {
        if (draft == null) {
            return List.of();
        }
        LiveQuoteFlow.Phase phase = (quote == null) ? LiveQuoteFlow.Phase.FAILED : quote;
        String id = draft.marketId() >= 0 ? ("  #" + draft.marketId()) : "  #--";
        List<String> out = new ArrayList<>();
        out.add("确认" + draft.side().title);
        out.add(draft.name() + id);
        out.add("单价 " + TradeDraft.money(draft.boardPrice())
                + " × 数量 " + draft.qty()
                + " = 预计 " + TradeDraft.money(draft.estimated()));
        out.add(taxLine(draft, phase));
        out.add("当前余额 " + TradeDraft.money(draft.wallet())
                + " → 预计剩余 " + TradeDraft.money(draft.walletAfter()));
        if (!draft.canAfford()) {
            out.add("余额不足（按实时价）");
        }
        if (status != null && !status.isBlank()) {
            out.add(status);
        }
        out.add("回车确认　Esc 取消");
        return List.copyOf(out);
    }

    static String taxLine(TradeDraft draft, LiveQuoteFlow.Phase phase) {
        if (phase == LiveQuoteFlow.Phase.QUOTING) {
            return "正在查价…";
        }
        if (draft.side() == TradeDraft.Side.SELL) {
            return "交税 0.00";
        }
        if (phase == LiveQuoteFlow.Phase.FAILED || !draft.liveKnown()) {
            return "交税 --";
        }
        double tax = draft.tax();
        if (tax <= 0) {
            return "交税 0.00";
        }
        return "交税 " + TradeDraft.money(tax)
                + " (" + String.format(Locale.ROOT, "%.2f", draft.taxPct()) + "%)";
    }
}
