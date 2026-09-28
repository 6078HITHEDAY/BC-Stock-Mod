package cn.myflycat.bcstock.ui;

/**
 * 配色与布局常量。不引用 {@code net.minecraft}，可离线测。
 *
 * <p>2026-09-27 一凡拍板：自绘部分倒向原版 16 色观感。
 * {@link #GOLD} <b>仅</b>用于 K 线折线与 HUD 倒计时警示，不再是界面主色。
 */
public final class UiPalette {

    /** 主文本（原版白）。 */
    public static final int TEXT = 0xFFFFFFFF;
    /** 次要文本 / 表头 / 提示（原版灰）。 */
    public static final int MUTED = 0xFFAAAAAA;
    /** 禁用 / 极次要（原版深灰）。 */
    public static final int DISABLED = 0xFF555555;
    /**
     * 警示黄（原版 §e）。降级提示、地板危险闪烁「亮」帧。
     * 注意：不是红；红见 {@link #DOWN}/{@link #ERROR}。
     */
    public static final int WARN = 0xFFFFFF55;
    /** 错误 / 跌（原版 §c 红）。 */
    public static final int ERROR = 0xFFFF5555;
    /** 涨（原版 §a 绿）。 */
    public static final int UP = 0xFF55FF55;
    /** 跌（同 {@link #ERROR}）。 */
    public static final int DOWN = 0xFFFF5555;
    /** 行 hover 半透明白叠加。 */
    public static final int HOVER = 0x20FFFFFF;
    /**
     * 仅 K 线折线与 HUD 倒计时 &lt;10s 警示。
     * <b>不是</b>界面主色（2026-09-27 改设计）。
     */
    public static final int GOLD = 0xFFCD9B5A;
    /**
     * 闪烁「暗」帧（原版黑）。地板危险条与 WARN 交替。
     * 替代已删除的自定义 {@code BG}。
     */
    public static final int BLINK_OFF = 0xFF000000;

    /** 全列宽：408 原七列 + 72 操作列。窄模式另算可见列之和。 */
    public static final int TABLE_W = 480;
    public static final int CELL = 18;
    public static final int TOP_BAR_H = 24;
    public static final int HEADER_H = 16;
    /** 底栏：原版按钮 20px + 上下各 2px，避免和全宽列表抢点击。 */
    public static final int BOTTOM_BAR_H = 24;
    public static final int PAD_X = 12;

    public static final String DEBUG_GRID_PROPERTY = "bcstock.ui.debugGrid";

    private UiPalette() {
    }

    public static int originX(int width) {
        return (width - TABLE_W) / 2;
    }

    /** 调试栅格默认关。开：{@code -Dbcstock.ui.debugGrid=true}。 */
    public static boolean debugGridEnabled() {
        return Boolean.getBoolean(DEBUG_GRID_PROPERTY);
    }
}
