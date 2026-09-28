package cn.myflycat.bcstock.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 排序 / 筛选 / 命中。纯函数，不引用 {@code net.minecraft}。
 */
public final class BoardInteraction {

    public enum Sort {
        DEFAULT("默认"),
        CHANGE("涨跌"),
        RISK("风险"),
        PROFIT("盈亏");

        public final String label;

        Sort(String label) {
            this.label = label;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum Filter {
        ALL("全部"),
        HELD("只看持有"),
        NO_BANKRUPT("排除破产");

        public final String label;

        Filter(String label) {
            this.label = label;
        }

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private BoardInteraction() {
    }

    /**
     * 开盘默认筛选项。设置关掉「显示破产公司」时从「排除破产」起，
     * 仍可切到「全部」看破产行——设置项不再把「全部」也清空。
     */
    public static Filter defaultFilter(boolean showBankrupt) {
        return showBankrupt ? Filter.ALL : Filter.NO_BANKRUPT;
    }

    public static List<BoardRow> apply(List<BoardRow> src, Sort sort, Filter filter) {
        return apply(src, sort, filter, true);
    }

    /**
     * {@code showBankrupt} 已不再在这里藏行：破产只由 {@link Filter#NO_BANKRUPT} 排除。
     * 参数保留是为了旧调用点还能编过，传入 false 时「全部」仍列出破产。
     */
    public static List<BoardRow> apply(List<BoardRow> src, Sort sort, Filter filter,
                                       boolean showBankrupt) {
        List<BoardRow> out = new ArrayList<>();
        if (src != null) {
            for (BoardRow r : src) {
                if (keep(r, filter)) {
                    out.add(r);
                }
            }
        }
        if (sort == Sort.CHANGE) {
            out.sort(Comparator.comparingDouble((BoardRow r) -> nanLast(r.sortChange())).reversed());
        } else if (sort == Sort.RISK) {
            out.sort(Comparator.comparingInt(BoardRow::sortRisk).reversed());
        } else if (sort == Sort.PROFIT) {
            out.sort(Comparator.comparingDouble((BoardRow r) -> nanLast(r.sortProfit())).reversed());
        }
        return List.copyOf(out);
    }

    private static boolean keep(BoardRow r, Filter filter) {
        if (filter == Filter.HELD) {
            return r.held();
        }
        if (filter == Filter.NO_BANKRUPT) {
            return !r.bankrupt();
        }
        return true;
    }

    private static double nanLast(double v) {
        return Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
    }
}
