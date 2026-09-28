package cn.myflycat.bcstock.data;

import java.util.List;

/**
 * 三档降级。这是「降级」落到 UI 的<b>唯一入口</b>——界面只问本枚举，
 * 不要在渲染里再写一遍「API 挂了怎么办」。
 *
 * <p>判定由调用方给两个布尔：API 通不通、命令通路通不通。
 * 「还没问过命令」不是「命令挂了」——那种情况命令通路仍算可用。
 */
public enum DegradePolicy {
    /** API 好 + 命令好：价格 / 持仓 / 资金都有，K 线元素可以画。 */
    FULL,
    /** API 挂：只显示命令和容器能给的，K 线相关元素隐藏。 */
    NO_API,
    /** 命令挂：只能显示容器数据，下单入口禁用并灰显。 */
    READ_ONLY;

    /**
     * 命令挂优先于 API 挂：下单走命令，命令不通时即使行情在也不能买。
     */
    public static DegradePolicy resolve(boolean apiHealthy, boolean commandHealthy) {
        if (!commandHealthy) {
            return READ_ONLY;
        }
        if (!apiHealthy) {
            return NO_API;
        }
        return FULL;
    }

    /**
     * 生产入口：API 看快照，命令通路看网关派生值。界面 / 客户端只准走这里。
     */
    public static DegradePolicy of(StockSnapshot snap, CommandGateway gateway) {
        boolean api = snap != null && snap.apiHealthy();
        boolean cmd = gateway != null && gateway.commandHealthy();
        return resolve(api, cmd);
    }

    /**
     * 界面模型里的 K 线元素名。{@link #NO_API} / {@link #READ_ONLY} 必须是空列表，
     * 不能只是「画了但不显示」——模型里就不该有，渲染才不会误画。
     */
    public List<String> klineElements() {
        if (this != FULL) {
            return List.of();
        }
        return List.of("kline", "available-shares");
    }

    /** 下单入口。被降级封死就 {@code enabled == false}（A5 的功能开关是另一件事）。 */
    public TradeEntry tradeEntry() {
        return new TradeEntry(this != READ_ONLY);
    }

    /** 下单入口的最小界面模型。 */
    public record TradeEntry(boolean enabled) {
    }
}
