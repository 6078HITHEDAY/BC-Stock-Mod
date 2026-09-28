package cn.myflycat.bcstock.config;

import cn.myflycat.bcstock.BcStockLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置文件读写 + 校验 + {@code -D} 覆盖。纯逻辑，不 import {@code net.minecraft}。
 *
 * <p>优先级：<b>系统属性 {@code -D} &gt; 配置文件 &gt; 硬编码默认</b>。
 * 文件损坏 / 解析失败 → 整份回落 {@link BcStockConfig#safeDefaults()}，
 * 绝不允许坏文件把交易/采集/提醒「意外打开」。
 */
public final class ConfigLoader {

    public static final String RELATIVE_PATH = "config/bcstock.json";

    public static final String PROP_TRADE = "bcstock.trade.enabled";
    public static final String PROP_SHOW_BANKRUPT = "bcstock.board.showBankrupt";
    public static final String PROP_COLLECT = "bcstock.collect.enabled";
    public static final String PROP_ALERT = "bcstock.alert.enabled";
    public static final String PROP_ALERT_CHANGE = "bcstock.alert.change.enabled";
    public static final String PROP_ALERT_FLOOR = "bcstock.alert.floor.enabled";
    public static final String PROP_ALERT_PNL = "bcstock.alert.pnl.enabled";
    public static final String PROP_DECISION = "bcstock.decision.preset";
    public static final String PROP_AUTO_MODE = "bcstock.auto.mode";
    public static final String PROP_AUTO_MAX_TRADE = "bcstock.auto.maxPerTrade";
    public static final String PROP_AUTO_MAX_DAY = "bcstock.auto.maxPerDay";
    public static final String PROP_AUTO_KILL = "bcstock.auto.kill";
    public static final String PROP_AUTO_ALLOW_BUY = "bcstock.auto.allowBuy";
    public static final String PROP_AUTO_ALLOW_SELL = "bcstock.auto.allowSell";
    public static final String PROP_AUTO_COOLDOWN = "bcstock.auto.cooldownSec";
    public static final String PROP_AUTO_CASH_FLOOR = "bcstock.auto.cashFloor";
    public static final String PROP_AUTO_WHITELIST = "bcstock.auto.whitelist";
    public static final String PROP_API_BASE = "bcstock.api.base";
    public static final String PROP_API_TIMEOUT = "bcstock.api.timeoutMs";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigLoader() {
    }

    /**
     * 加载结果。{@code effective} 已含 {@code -D} 覆盖；
     * {@code parseFailed} 为 true 时已整份安全回落。
     */
    public record LoadResult(BcStockConfig fileConfig, BcStockConfig effective,
                             boolean created, boolean parseFailed, String message) {
    }

    /**
     * 读文件；不存在则写出默认并返回。损坏 → 安全默认 + 打日志。
     * 返回的 {@code effective} 已套上 {@code props} 覆盖。
     */
    public static LoadResult loadOrCreate(File file, PropertySource props) {
        PropertySource p = (props == null) ? PropertySource.empty() : props;
        if (file == null) {
            BcStockConfig safe = BcStockConfig.safeDefaults();
            return new LoadResult(safe, applyOverrides(safe, p), false, true,
                    "配置路径为空，已回落安全默认");
        }
        if (!file.isFile()) {
            BcStockConfig defaults = BcStockConfig.safeDefaults();
            String writeMsg = writeQuiet(file, defaults);
            BcStockConfig effective = applyOverrides(defaults, p);
            String msg = (writeMsg == null)
                    ? "首次运行，已生成 " + file.getAbsolutePath()
                    : "首次运行写配置失败：" + writeMsg + "，仍用内存默认";
            BcStockLog.info(msg);
            return new LoadResult(defaults, effective, true, false, msg);
        }
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            BcStockConfig parsed = parseLenient(raw);
            BcStockConfig effective = applyOverrides(parsed, p);
            return new LoadResult(parsed, effective, false, false, "ok");
        } catch (Exception e) {
            BcStockConfig safe = BcStockConfig.safeDefaults();
            String msg = "配置解析失败，已回落安全默认：" + e;
            BcStockLog.warn(msg);
            return new LoadResult(safe, applyOverrides(safe, p), false, true, msg);
        }
    }

    /**
     * 宽松解析：缺键 / 类型错用该项默认；整段非法 JSON 抛异常由调用方回落。
     */
    public static BcStockConfig parseLenient(String json) {
        if (json == null || json.isBlank()) {
            throw new JsonSyntaxException("空配置");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new JsonSyntaxException("非法 JSON", e);
        }
        BcStockConfig d = BcStockConfig.safeDefaults();
        BcStockConfig c = new BcStockConfig();
        c.board.showBankrupt = bool(root, "board", "showBankrupt", d.board.showBankrupt);
        c.collect.enabled = bool(root, "collect", "enabled", d.collect.enabled);
        c.alert.enabled = bool(root, "alert", "enabled", d.alert.enabled);
        c.alert.changeEnabled = bool(root, "alert", "changeEnabled", d.alert.changeEnabled);
        c.alert.floorEnabled = bool(root, "alert", "floorEnabled", d.alert.floorEnabled);
        c.alert.pnlEnabled = bool(root, "alert", "pnlEnabled", d.alert.pnlEnabled);
        c.trade.enabled = bool(root, "trade", "enabled", d.trade.enabled);
        c.auto.mode = str(root, "auto", "mode", d.auto.mode);
        c.auto.maxPerTrade = intVal(root, "auto", "maxPerTrade", d.auto.maxPerTrade);
        c.auto.maxPerDay = intVal(root, "auto", "maxPerDay", d.auto.maxPerDay);
        c.auto.kill = bool(root, "auto", "kill", d.auto.kill);
        c.auto.allowBuy = bool(root, "auto", "allowBuy", d.auto.allowBuy);
        c.auto.allowSell = bool(root, "auto", "allowSell", d.auto.allowSell);
        c.auto.cooldownSec = intVal(root, "auto", "cooldownSec", d.auto.cooldownSec);
        c.auto.cashFloor = doubleVal(root, "auto", "cashFloor", d.auto.cashFloor);
        c.auto.whitelist = str(root, "auto", "whitelist", d.auto.whitelist);
        if (c.auto.cooldownSec < 0) {
            c.auto.cooldownSec = d.auto.cooldownSec;
        }
        if (Double.isNaN(c.auto.cashFloor) || c.auto.cashFloor < 0) {
            c.auto.cashFloor = d.auto.cashFloor;
        }
        c.decision.preset = str(root, "decision", "preset", d.decision.preset);
        c.api.base = str(root, "api", "base", d.api.base);
        c.api.timeoutMs = intVal(root, "api", "timeoutMs", d.api.timeoutMs);
        if (c.api.timeoutMs < 1_000) {
            c.api.timeoutMs = d.api.timeoutMs;
        }
        if (c.api.base == null || c.api.base.isBlank()) {
            c.api.base = d.api.base;
        }
        return c;
    }

    /** {@code -D} 有设才覆盖；未设保留文件值。 */
    public static BcStockConfig applyOverrides(BcStockConfig file, PropertySource props) {
        BcStockConfig c = (file == null ? BcStockConfig.safeDefaults() : file).copy();
        PropertySource p = (props == null) ? PropertySource.empty() : props;
        String v;
        if ((v = p.get(PROP_TRADE)) != null) {
            c.trade.enabled = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_SHOW_BANKRUPT)) != null) {
            c.board.showBankrupt = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_COLLECT)) != null) {
            c.collect.enabled = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_ALERT)) != null) {
            c.alert.enabled = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_ALERT_CHANGE)) != null) {
            c.alert.changeEnabled = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_ALERT_FLOOR)) != null) {
            c.alert.floorEnabled = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_ALERT_PNL)) != null) {
            c.alert.pnlEnabled = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_DECISION)) != null) {
            c.decision.preset = v;
        }
        if ((v = p.get(PROP_AUTO_MODE)) != null) {
            c.auto.mode = v;
        }
        if ((v = p.get(PROP_AUTO_MAX_TRADE)) != null) {
            try {
                c.auto.maxPerTrade = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // 保留文件值
            }
        }
        if ((v = p.get(PROP_AUTO_MAX_DAY)) != null) {
            try {
                c.auto.maxPerDay = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // 保留文件值
            }
        }
        if ((v = p.get(PROP_AUTO_KILL)) != null) {
            c.auto.kill = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_AUTO_ALLOW_BUY)) != null) {
            c.auto.allowBuy = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_AUTO_ALLOW_SELL)) != null) {
            c.auto.allowSell = Boolean.parseBoolean(v);
        }
        if ((v = p.get(PROP_AUTO_COOLDOWN)) != null) {
            try {
                c.auto.cooldownSec = Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // 保留文件值
            }
        }
        if ((v = p.get(PROP_AUTO_CASH_FLOOR)) != null) {
            try {
                c.auto.cashFloor = Double.parseDouble(v.trim());
            } catch (NumberFormatException ignored) {
                // 保留文件值
            }
        }
        if ((v = p.get(PROP_AUTO_WHITELIST)) != null) {
            c.auto.whitelist = v;
        }
        if ((v = p.get(PROP_API_BASE)) != null && !v.isBlank()) {
            c.api.base = v;
        }
        if ((v = p.get(PROP_API_TIMEOUT)) != null) {
            try {
                int ms = Integer.parseInt(v.trim());
                if (ms >= 1_000) {
                    c.api.timeoutMs = ms;
                }
            } catch (NumberFormatException ignored) {
                // 保留文件值
            }
        }
        return c;
    }

    public static void save(File file, BcStockConfig config) throws IOException {
        if (file == null || config == null) {
            throw new IllegalArgumentException("file/config 不能为空");
        }
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(config, w);
            w.write('\n');
        }
    }

    private static String writeQuiet(File file, BcStockConfig config) {
        try {
            save(file, config);
            return null;
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static JsonObject obj(JsonObject root, String group) {
        if (root == null || !root.has(group) || !root.get(group).isJsonObject()) {
            return null;
        }
        return root.getAsJsonObject(group);
    }

    private static boolean bool(JsonObject root, String group, String key, boolean def) {
        JsonObject g = obj(root, group);
        if (g == null || !g.has(key) || g.get(key).isJsonNull()) {
            return def;
        }
        try {
            return g.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static int intVal(JsonObject root, String group, String key, int def) {
        JsonObject g = obj(root, group);
        if (g == null || !g.has(key) || g.get(key).isJsonNull()) {
            return def;
        }
        try {
            return g.get(key).getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static double doubleVal(JsonObject root, String group, String key, double def) {
        JsonObject g = obj(root, group);
        if (g == null || !g.has(key) || g.get(key).isJsonNull()) {
            return def;
        }
        try {
            return g.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static String str(JsonObject root, String group, String key, String def) {
        JsonObject g = obj(root, group);
        if (g == null || !g.has(key) || g.get(key).isJsonNull()) {
            return def;
        }
        try {
            String s = g.get(key).getAsString();
            return (s == null || s.isBlank()) ? def : s;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
