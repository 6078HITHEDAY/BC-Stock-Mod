package cn.myflycat.bcstock.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自动化开关。默认 {@link Mode#ADVICE}（只提示、不发令）。
 * 生产值由 {@link cn.myflycat.bcstock.config.ConfigRuntime} 灌入。
 */
public final class AutoTradeSettings {

    public enum Mode {
        /** 只提示建议，买卖仍要人按确认框。 */
        ADVICE,
        /** 限额内免确认，仍先写待确认单再发令。 */
        LIMITED,
        /** 信号一出直接下单（仍守总开关 / 紧急停止 / 单飞 / 不重发）。 */
        FULL;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ADVICE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "limited" -> LIMITED;
                case "full" -> FULL;
                default -> ADVICE;
            };
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static volatile Mode mode = Mode.ADVICE;
    private static volatile int maxPerTrade = 10;
    private static volatile int maxPerDay = 100;
    private static volatile boolean kill = false;
    private static volatile boolean allowBuy = true;
    private static volatile boolean allowSell = true;
    private static volatile int cooldownSec = 60;
    private static volatile double cashFloor = 0.0;
    private static volatile String whitelistRaw = "";

    private AutoTradeSettings() {
    }

    public static void applyFromConfig(Mode modeValue, int maxTrade, int maxDay, boolean killValue,
                                       boolean allowBuyValue, boolean allowSellValue,
                                       int cooldownSecValue, double cashFloorValue,
                                       String whitelistValue) {
        mode = (modeValue == null) ? Mode.ADVICE : modeValue;
        maxPerTrade = maxTrade;
        maxPerDay = maxDay;
        kill = killValue;
        allowBuy = allowBuyValue;
        allowSell = allowSellValue;
        cooldownSec = Math.max(0, cooldownSecValue);
        cashFloor = Double.isNaN(cashFloorValue) ? 0.0 : cashFloorValue;
        whitelistRaw = whitelistValue == null ? "" : whitelistValue;
    }

    public static Mode mode() {
        return mode;
    }

    public static int maxPerTrade() {
        return maxPerTrade;
    }

    public static int maxPerDay() {
        return maxPerDay;
    }

    /** 紧急停止：true 时任何自动化与确认下单都不许发命令。 */
    public static boolean kill() {
        return kill;
    }

    public static boolean allowBuy() {
        return allowBuy;
    }

    public static boolean allowSell() {
        return allowSell;
    }

    public static int cooldownSec() {
        return cooldownSec;
    }

    public static long cooldownMs() {
        return cooldownSec * 1000L;
    }

    public static double cashFloor() {
        return cashFloor;
    }

    public static String whitelistRaw() {
        return whitelistRaw;
    }

    /** 空列表 = 全部可交易标的。 */
    public static List<String> whitelistTokens() {
        return parseWhitelist(whitelistRaw);
    }

    static List<String> parseWhitelist(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("[,，]")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    public static void setModeForTest(Mode value) {
        mode = (value == null) ? Mode.ADVICE : value;
    }

    public static void setMaxPerTradeForTest(int value) {
        maxPerTrade = value;
    }

    public static void setMaxPerDayForTest(int value) {
        maxPerDay = value;
    }

    public static void setKillForTest(boolean value) {
        kill = value;
    }

    public static void setAllowBuyForTest(boolean value) {
        allowBuy = value;
    }

    public static void setAllowSellForTest(boolean value) {
        allowSell = value;
    }

    public static void setCooldownSecForTest(int value) {
        cooldownSec = Math.max(0, value);
    }

    public static void setCashFloorForTest(double value) {
        cashFloor = value;
    }

    public static void setWhitelistForTest(String value) {
        whitelistRaw = value == null ? "" : value;
    }

    /** 测试复位到默认。 */
    public static void resetForTest() {
        mode = Mode.ADVICE;
        maxPerTrade = 10;
        maxPerDay = 100;
        kill = false;
        allowBuy = true;
        allowSell = true;
        cooldownSec = 60;
        cashFloor = 0.0;
        whitelistRaw = "";
    }
}
