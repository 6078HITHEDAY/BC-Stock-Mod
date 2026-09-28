package cn.myflycat.bcstock.probe;

/**
 * 股市相关的容器界面种类。
 *
 * <p>识别靠<b>标题子串</b>，不靠窗口格数或 {@code ScreenHandlerType}：服务端用的是普通箱子 GUI
 * （官方文档口径见 {@code docs/gui-invest.md}），类型区分不出来，唯一稳定的特征是标题。
 * 用 {@code contains} 而不是 {@code equals}，是因为标题可能带颜色/装饰前缀。
 *
 * <p>三个标题都是 2026-09-26 实测值（{@code data/gui/}），不是推测。
 */
public enum StockContainerKind {

    /** 主界面，16 家公司占 #10–#16 / #19–#25 / #28–#29。 */
    COMPANY_LIST("公司列表"),

    /** 个人持仓 / 成本 / 资产——<b>唯一</b>能读到个人数据的地方（API 的个人资金线已死）。 */
    PORTFOLIO("您的投资组合"),

    /** 只有「股市更新」「公司破产」两个开关。 */
    NOTIFICATION("通知设置"),

    /** 不是股市容器（含玩家自己的背包界面）。默认不 dump，避免把个人装备写进日志。 */
    UNKNOWN("");

    private final String titleFragment;

    StockContainerKind(String titleFragment) {
        this.titleFragment = titleFragment;
    }

    /** 用于匹配的标题子串；{@link #UNKNOWN} 为空串。 */
    public String titleFragment() {
        return titleFragment;
    }

    /** 是否是股市容器。 */
    public boolean isStock() {
        return this != UNKNOWN;
    }

    /**
     * 按标题认容器。
     *
     * @param title 容器标题（{@code Screen.getTitle().getString()} 的结果，已经去掉颜色码）；可为 null
     */
    public static StockContainerKind of(String title) {
        if (title == null || title.isEmpty()) {
            return UNKNOWN;
        }
        for (StockContainerKind kind : VALUES) {
            if (title.contains(kind.titleFragment)) {
                return kind;
            }
        }
        return UNKNOWN;
    }

    /** 枚举常量表。{@code UNKNOWN} 的 titleFragment 是空串，{@code contains("")} 恒真，必须排除。 */
    private static final StockContainerKind[] VALUES = {
        COMPANY_LIST, PORTFOLIO, NOTIFICATION,
    };
}
