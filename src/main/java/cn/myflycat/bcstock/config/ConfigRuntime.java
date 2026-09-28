package cn.myflycat.bcstock.config;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.BcStockSettings;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.decision.DecisionPreset;
import java.io.File;
import java.io.IOException;

/**
 * 进程内生效配置。启动读一次；ModMenu 保存后再次 {@link #apply}。
 * 不 import {@code net.minecraft}。
 */
public final class ConfigRuntime {

    private static volatile BcStockConfig effective = BcStockConfig.safeDefaults();
    private static volatile File configFile;
    private static volatile boolean bootstrapped;

    private ConfigRuntime() {
    }

    /** 从 {@code runDirectory/config/bcstock.json} 加载并推到各 Settings。 */
    public static synchronized void bootstrap(File runDirectory) {
        File dir = (runDirectory == null) ? new File(".") : runDirectory;
        File file = new File(dir, ConfigLoader.RELATIVE_PATH);
        configFile = file;
        ConfigLoader.LoadResult r = ConfigLoader.loadOrCreate(file, PropertySource.system());
        apply(r.effective());
        bootstrapped = true;
        if (r.parseFailed()) {
            BcStockLog.warn("配置启动：{}", r.message());
        } else {
            BcStockLog.info("配置已加载：{}（created={}）", file.getAbsolutePath(), r.created());
        }
    }

    /** 把一份配置推到 TradeSettings / BcStockSettings / AutoTradeSettings。 */
    public static synchronized void apply(BcStockConfig config) {
        BcStockConfig c = (config == null) ? BcStockConfig.safeDefaults() : config;
        effective = c;
        TradeSettings.applyFromConfig(c.trade.enabled);
        BcStockSettings.applyFromConfig(
                c.board.showBankrupt,
                c.collect.enabled,
                c.alert.enabled,
                c.alert.changeEnabled,
                c.alert.floorEnabled,
                c.alert.pnlEnabled,
                DecisionPreset.parse(c.decision.preset));
        AutoTradeSettings.applyFromConfig(
                AutoTradeSettings.Mode.parse(c.auto.mode),
                c.auto.maxPerTrade,
                c.auto.maxPerDay,
                c.auto.kill,
                c.auto.allowBuy,
                c.auto.allowSell,
                c.auto.cooldownSec,
                c.auto.cashFloor,
                c.auto.whitelist);
    }

    /** 当前生效配置（含 -D 覆盖后的值）。只读用途请 {@link #snapshot()}。 */
    public static BcStockConfig get() {
        return effective;
    }

    /** 拷贝一份，供 GUI 编辑。 */
    public static BcStockConfig snapshot() {
        return effective.copy();
    }

    public static File configFile() {
        return configFile;
    }

    public static boolean bootstrapped() {
        return bootstrapped;
    }

    public static String apiBase() {
        String b = effective.api.base;
        return (b == null || b.isBlank()) ? BcStockConfig.safeDefaults().api.base : b;
    }

    public static int timeoutMs() {
        int ms = effective.api.timeoutMs;
        return ms < 1_000 ? 15_000 : ms;
    }

    /**
     * GUI 保存：写回文件（文件里是用户勾选的值，不含 -D），
     * 再对「文件值 + 当前 -D」重算生效配置。
     */
    public static synchronized void saveFromGui(BcStockConfig fileValues) {
        BcStockConfig toWrite = (fileValues == null) ? BcStockConfig.safeDefaults() : fileValues.copy();
        File file = configFile;
        if (file != null) {
            try {
                ConfigLoader.save(file, toWrite);
                BcStockLog.info("配置已保存：{}", file.getAbsolutePath());
            } catch (IOException e) {
                BcStockLog.warn("配置保存失败：{}", e.toString());
            }
        }
        apply(ConfigLoader.applyOverrides(toWrite, PropertySource.system()));
    }

    /** 测试复位。 */
    public static synchronized void resetForTest() {
        effective = BcStockConfig.safeDefaults();
        configFile = null;
        bootstrapped = false;
        apply(effective);
    }
}
