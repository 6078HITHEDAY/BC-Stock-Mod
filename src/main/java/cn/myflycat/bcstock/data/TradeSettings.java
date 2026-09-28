package cn.myflycat.bcstock.data;

/**
 * 下单总开关与数量上限。默认关——关着时界面点击必须完全没反应。
 * 正式使用要人显式打开；测试用 {@link #setEnabledForTest}。
 *
 * <p>生产值由 {@link cn.myflycat.bcstock.config.ConfigRuntime} 在启动时灌入
 * （配置文件 + {@code -Dbcstock.trade.enabled} 覆盖）。本类静态初值是安全默认 false，
 * <b>不</b>在 class-load 时读系统属性，避免抢在配置加载之前。
 *
 * <p>{@link #MAX_CUSTOM_QTY} 是全项目唯一的自定义数量上限来源
 * （{@link cn.myflycat.bcstock.ui.TradeDraft} 与 {@link CommandGateway} 都读这里，
 * 不许再写第二个 1000）。
 */
public final class TradeSettings {

    /**
     * 自定义数量上限（防手抖）。1/10/100 快捷档仍可用；
     * 手输超过此值必须拒绝并说明，不许静默夹取。
     */
    public static final int MAX_CUSTOM_QTY = 1000;

    /** 默认关。面向用户：改 {@code config/bcstock.json} 或 ModMenu；验收仍可用 {@code -D}。 */
    private static volatile boolean enabled = false;

    private TradeSettings() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabledForTest(boolean value) {
        enabled = value;
    }

    /** 配置层灌入。 */
    public static void applyFromConfig(boolean value) {
        enabled = value;
    }
}
