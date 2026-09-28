package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.DegradePolicy;

/**
 * 底栏文案拆段（纯函数）。渲染与命中共用同一套字符串，避免点偏。
 * 人话，不许出现 JVM 参数名 / {@code -D} / 类名。
 */
public final class BottomBarLabels {

    /** 排序按钮宽。 */
    public static final int SORT_BTN_W = 100;
    /** 筛选相对 originX 的起点（排序宽 + 间距）。 */
    public static final int FILTER_BTN_X = 108;
    /** 筛选按钮宽。 */
    public static final int FILTER_BTN_W = 100;
    /** 底栏按钮高（原版默认 20，18 会让贴图溢出、点到列表空区）。 */
    public static final int BTN_H = 20;
    /** 筛选右侧空隙。 */
    public static final int STATUS_GAP = 8;
    /**
     * 状态文案（数据年龄等）相对 {@code originX} 的偏移：
     * 筛选右缘 + 空隙 = {@code 108 + 100 + 8 = 216}。
     */
    public static final int STATUS_AFTER_FILTER_X = FILTER_BTN_X + FILTER_BTN_W + STATUS_GAP;

    private BottomBarLabels() {
    }

    /** 筛选按钮右侧的状态文案 X。 */
    public static int statusTextX(int originX) {
        return originX + STATUS_AFTER_FILTER_X;
    }

    /**
     * 状态文案是否画得下：右缘不得侵入 {@code rightLimit}（交易提示 / 页码区）。
     */
    public static boolean statusFits(int originX, int textWidth, int rightLimit) {
        if (textWidth <= 0 || rightLimit <= originX) {
            return false;
        }
        return statusTextX(originX) + textWidth <= rightLimit;
    }

    /**
     * 底栏状态前缀（筛选右侧）。有本地数据年龄时，单次 API 失败显示年龄而不是「API 不可用」。
     *
     * @param dataAgeLabel {@link cn.myflycat.bcstock.data.MarketDataAge#formatLabel}；可空
     */
    public static String prefix(DegradePolicy policy, boolean narrow, String dataAgeLabel) {
        if (policy == DegradePolicy.READ_ONLY) {
            return "只读模式";
        }
        if (policy == DegradePolicy.NO_API) {
            if (dataAgeLabel != null && !dataAgeLabel.isBlank()) {
                return dataAgeLabel;
            }
            return "API 不可用，仅显示本地数据";
        }
        if (narrow) {
            return "窗口过窄，已隐藏 涨跌/风险/成本/盈亏";
        }
        // FULL：若有年龄且偏旧，仍可轻提示（可选）；默认安静
        return "";
    }

    /** 兼容旧调用：无年龄文案。 */
    public static String prefix(DegradePolicy policy, boolean narrow) {
        return prefix(policy, narrow, "");
    }

    /**
     * 交易总开关关闭时的底栏提示。开着返回空串。
     */
    public static String tradeHint(boolean tradeEnabled) {
        return tradeEnabled ? "" : "交易未启用";
    }

    public static String sortText(BoardInteraction.Sort sort) {
        String label = (sort == null) ? BoardInteraction.Sort.DEFAULT.label : sort.label;
        return "排序: " + label;
    }

    public static String filterText(BoardInteraction.Filter filter) {
        String label = (filter == null) ? BoardInteraction.Filter.ALL.label : filter.label;
        return "筛选: " + label;
    }

    /**
     * 底栏右下角计数。盘面没有分页，所以这里不装页码：说的是「这一档有几家、为什么是这么多」。
     * 全部档点明含多少家破产，排除破产档点明已排除，只看持有档点明仅持仓。
     * 空列表如实写 0 家，不再拿 {@code Math.max(1, …)} 编一个分母出来。
     *
     * @param selectedIndex 选中行下标，{@code -1} 表示没选中
     */
    public static String countText(BoardInteraction.Filter filter, int total,
                                   int bankruptVisible, int selectedIndex) {
        int t = Math.max(0, total);
        StringBuilder sb = new StringBuilder();
        if (t > 0 && selectedIndex >= 0) {
            sb.append("第 ").append(Math.min(selectedIndex + 1, t)).append(" 行 · ");
        }
        sb.append("共 ").append(t).append(" 家");
        BoardInteraction.Filter f = (filter == null) ? BoardInteraction.Filter.ALL : filter;
        if (f == BoardInteraction.Filter.NO_BANKRUPT) {
            sb.append("（已排除破产）");
        } else if (f == BoardInteraction.Filter.ALL) {
            if (bankruptVisible > 0) {
                sb.append("（含 ").append(bankruptVisible).append(" 家已破产）");
            }
        } else {
            sb.append("（仅持仓）");
        }
        return sb.toString();
    }

    /** 短写法：只报总数。右端挤不下（窄窗）时退成这个。 */
    public static String countTextShort(int total) {
        return "共 " + Math.max(0, total) + " 家";
    }

    /** 计数左缘是否还留在底栏按钮区右侧（越了就得换短写法，否则压到筛选按钮上）。 */
    public static boolean countFits(int originX, int textWidth, int rightEdge) {
        if (textWidth <= 0) {
            return false;
        }
        return rightEdge - textWidth >= originX + FILTER_BTN_X + FILTER_BTN_W + 4;
    }

    /** 这一档筛完是空的：给一句人话，别让人对着空白猜是界面坏了。 */
    public static String emptyHint(BoardInteraction.Filter filter) {
        BoardInteraction.Filter f = (filter == null) ? BoardInteraction.Filter.ALL : filter;
        return switch (f) {
            case HELD -> "你当前没有持仓";
            case NO_BANKRUPT -> "没有非破产公司";
            case ALL -> "暂无数据";
        };
    }

    public static String gap() {
        return "   ";
    }
}
