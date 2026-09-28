package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.decision.DecisionPreset;
import java.util.List;
import java.util.OptionalInt;

/**
 * 数量弹窗纯逻辑。不 import {@code net.minecraft}。
 * 渲染路径只读结果；发命令必须等确认框回车。
 */
public final class QtyDialogLogic {

    public enum Choice {
        Q1("1"),
        Q10("10"),
        CUSTOM("自定义"),
        ALL("全部抛出");

        public final String label;

        Choice(String label) {
            this.label = label;
        }
    }

    public record ParseResult(boolean ok, int qty, String error) {
        public static ParseResult ok(int qty) {
            return new ParseResult(true, qty, null);
        }

        public static ParseResult fail(String error) {
            return new ParseResult(false, 0, error);
        }
    }

    private QtyDialogLogic() {
    }

    /**
     * 买入自定义框的默认数量 = 当前档 {@link DecisionPreset#defaultBuyQty()}，夹到上限。
     * 卖出不预填（避免手滑）。
     */
    public static int defaultCustomQty(TradeDraft.Side side, DecisionPreset preset) {
        if (side != TradeDraft.Side.BUY) {
            return 0;
        }
        DecisionPreset p = (preset == null) ? DecisionPreset.DEFAULT : preset;
        int qty = p.defaultBuyQty();
        if (qty < 1) {
            return 0;
        }
        return Math.min(qty, TradeSettings.MAX_CUSTOM_QTY);
    }

    public static List<Choice> choices(TradeDraft.Side side) {
        if (side == TradeDraft.Side.SELL) {
            return List.of(Choice.Q1, Choice.Q10, Choice.CUSTOM, Choice.ALL);
        }
        return List.of(Choice.Q1, Choice.Q10, Choice.CUSTOM);
    }

    /**
     * 解析手输数量。空 / 非数字 / ≤0 → 无效；超过 {@link TradeSettings#MAX_CUSTOM_QTY}
     * → 拒绝并说明（不许静默夹取）。
     */
    public static ParseResult parseCustom(String text) {
        if (text == null || text.isBlank()) {
            return ParseResult.fail("请输入数量");
        }
        String t = text.trim();
        int qty;
        try {
            qty = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return ParseResult.fail("数量无效");
        }
        if (qty <= 0) {
            return ParseResult.fail("数量无效");
        }
        if (qty > TradeSettings.MAX_CUSTOM_QTY) {
            return ParseResult.fail("超过上限 " + TradeSettings.MAX_CUSTOM_QTY + " 股");
        }
        return ParseResult.ok(qty);
    }

    /**
     * 档位 → 股数。{@link Choice#ALL} 用持仓股数；持仓 ≤0 → empty。
     * 全部抛出允许超过 {@link TradeSettings#MAX_CUSTOM_QTY}（卖自己的仓，不是手抖买入）。
     */
    public static OptionalInt resolve(Choice choice, TradeDraft.Side side, long heldShares) {
        if (choice == null || side == null) {
            return OptionalInt.empty();
        }
        return switch (choice) {
            case Q1 -> OptionalInt.of(1);
            case Q10 -> OptionalInt.of(10);
            case CUSTOM -> OptionalInt.empty(); // 走 parseCustom
            case ALL -> {
                if (side != TradeDraft.Side.SELL || heldShares <= 0) {
                    yield OptionalInt.empty();
                }
                if (heldShares > Integer.MAX_VALUE) {
                    yield OptionalInt.empty();
                }
                yield OptionalInt.of((int) heldShares);
            }
        };
    }
}
