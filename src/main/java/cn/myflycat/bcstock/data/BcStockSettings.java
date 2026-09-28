package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.decision.DecisionPreset;

/**
 * 客户端设置。生产值由 {@link cn.myflycat.bcstock.config.ConfigRuntime} 灌入
 * （{@code config/bcstock.json} + {@code -D} 覆盖）。静态初值是安全默认，
 * 不在 class-load 时读系统属性。
 *
 * <p>键位覆盖预留：K（盘面）/ H（HUD）已走 Fabric 标准 {@code KeyBinding}。
 */
public final class BcStockSettings {

    private static volatile boolean showBankrupt = true;
    private static volatile boolean collectEnabled = false;
    private static volatile boolean alertEnabled = false;
    private static volatile boolean alertChangeEnabled = false;
    private static volatile boolean alertFloorEnabled = false;
    private static volatile boolean alertPnlEnabled = false;
    private static volatile DecisionPreset decisionPreset = DecisionPreset.DEFAULT;

    private BcStockSettings() {
    }

    public static void applyFromConfig(boolean showBankruptValue, boolean collect,
                                       boolean alert, boolean alertChange, boolean alertFloor,
                                       boolean alertPnl, DecisionPreset preset) {
        showBankrupt = showBankruptValue;
        collectEnabled = collect;
        alertEnabled = alert;
        alertChangeEnabled = alertChange;
        alertFloorEnabled = alertFloor;
        alertPnlEnabled = alertPnl;
        decisionPreset = (preset == null) ? DecisionPreset.DEFAULT : preset;
    }

    public static boolean showBankrupt() {
        return showBankrupt;
    }

    public static void setShowBankruptForTest(boolean value) {
        showBankrupt = value;
    }

    public static boolean collectEnabled() {
        return collectEnabled;
    }

    public static void setCollectEnabledForTest(boolean value) {
        collectEnabled = value;
    }

    public static boolean alertEnabled() {
        return alertEnabled;
    }

    public static void setAlertEnabledForTest(boolean value) {
        alertEnabled = value;
    }

    public static boolean alertChangeEnabled() {
        return alertChangeEnabled;
    }

    public static void setAlertChangeEnabledForTest(boolean value) {
        alertChangeEnabled = value;
    }

    public static boolean alertFloorEnabled() {
        return alertFloorEnabled;
    }

    public static void setAlertFloorEnabledForTest(boolean value) {
        alertFloorEnabled = value;
    }

    public static boolean alertPnlEnabled() {
        return alertPnlEnabled;
    }

    public static void setAlertPnlEnabledForTest(boolean value) {
        alertPnlEnabled = value;
    }

    /** 三档决策参数。{@link cn.myflycat.bcstock.decision.DecisionEngine} 读这张表。 */
    public static DecisionPreset decisionPreset() {
        return decisionPreset;
    }

    public static void setDecisionPresetForTest(DecisionPreset value) {
        decisionPreset = (value == null) ? DecisionPreset.DEFAULT : value;
    }
}
