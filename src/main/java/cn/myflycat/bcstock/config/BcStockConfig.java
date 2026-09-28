package cn.myflycat.bcstock.config;

/**
 * {@code config/bcstock.json} 的形状。分组与面向用户的设置项一一对应。
 * 不 import {@code net.minecraft}，可离线测。
 *
 * <p>字段全是 public 可变，方便 Gson 与 Cloth Config 共用同一份对象。
 */
public final class BcStockConfig {

    public Board board = new Board();
    public Collect collect = new Collect();
    public Alert alert = new Alert();
    public Trade trade = new Trade();
    public Auto auto = new Auto();
    public Decision decision = new Decision();
    public Api api = new Api();

    /** 硬编码安全默认（文件损坏时整份回落这里）。 */
    public static BcStockConfig safeDefaults() {
        return new BcStockConfig();
    }

    /** 深拷贝一份（GUI 草稿用）。 */
    public BcStockConfig copy() {
        BcStockConfig c = new BcStockConfig();
        c.board.showBankrupt = board.showBankrupt;
        c.collect.enabled = collect.enabled;
        c.alert.enabled = alert.enabled;
        c.alert.changeEnabled = alert.changeEnabled;
        c.alert.floorEnabled = alert.floorEnabled;
        c.alert.pnlEnabled = alert.pnlEnabled;
        c.trade.enabled = trade.enabled;
        c.auto.mode = auto.mode;
        c.auto.maxPerTrade = auto.maxPerTrade;
        c.auto.maxPerDay = auto.maxPerDay;
        c.auto.kill = auto.kill;
        c.auto.allowBuy = auto.allowBuy;
        c.auto.allowSell = auto.allowSell;
        c.auto.cooldownSec = auto.cooldownSec;
        c.auto.cashFloor = auto.cashFloor;
        c.auto.whitelist = auto.whitelist;
        c.decision.preset = decision.preset;
        c.api.base = api.base;
        c.api.timeoutMs = api.timeoutMs;
        return c;
    }

    public static final class Board {
        /** 破产公司是否显示。默认 true。 */
        public boolean showBankrupt = true;
    }

    public static final class Collect {
        /** 定时采集。默认关。 */
        public boolean enabled = false;
    }

    public static final class Alert {
        public boolean enabled = false;
        public boolean changeEnabled = false;
        public boolean floorEnabled = false;
        public boolean pnlEnabled = false;
    }

    public static final class Trade {
        /** 下单总开关。默认关——坏文件绝不能回落成开。 */
        public boolean enabled = false;
    }

    public static final class Auto {
        /** advice / limited / full。默认 advice。 */
        public String mode = "advice";
        public int maxPerTrade = 10;
        public int maxPerDay = 100;
        public boolean kill = false;
        public boolean allowBuy = true;
        public boolean allowSell = true;
        public int cooldownSec = 60;
        public double cashFloor = 0.0;
        /** 逗号分隔公司名或 market_id；空 = 全部。 */
        public String whitelist = "";
    }

    public static final class Decision {
        /** default / aggressive / conservative。 */
        public String preset = "default";
    }

    public static final class Api {
        public String base = "https://tool.myflycat.cn/quant";
        /** 默认 15s：实测 companies 冷启动可达 9s，5s 必偶发超时。 */
        public int timeoutMs = 15_000;
    }
}
