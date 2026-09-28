package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 采集持仓：行情刷新成功后再发一次 {@code invest portfolio}。
 * <b>不 import {@code net.minecraft}</b>；连接状态由调用方注入。
 *
 * <p>不再跟时钟相位走（避免和聊天刷新双时钟）。开盘 {@link BoardOpenPull} 仍单独拉。
 *
 * <p>collect 标记带生命周期：发出后超过 {@link #TAG_TTL_MS} 自动失效，
 * 避免回执超时 / 持仓未变时污染后续无关写入。
 */
public final class CollectScheduler {

    /**
     * 标记存活上限：超时 + 收齐余量 + 2s。超时那轮本来就不该有 collect 行。
     */
    public static final long TAG_TTL_MS =
            CommandGateway.TIMEOUT_MS + CommandGateway.SETTLE_MS + 2_000L;

    public static final CollectScheduler SHARED = new CollectScheduler();

    private LongSupplier clock = System::currentTimeMillis;
    private BooleanSupplier connected = () -> false;
    private BooleanSupplier enabled = BcStockSettings::collectEnabled;
    private CommandGateway gateway = CommandGateway.SHARED;

    private boolean pendingAfterRefresh;
    private boolean tagNextHoldings;
    private long tagAtMs = Long.MIN_VALUE;
    private long lastSkipLogAt = Long.MIN_VALUE / 2;

    public CollectScheduler() {
    }

    public CollectScheduler(LongSupplier clock, BooleanSupplier connected,
                            BooleanSupplier enabled, CommandGateway gateway) {
        setClock(clock);
        setConnected(connected);
        setEnabled(enabled);
        setGateway(gateway);
    }

    public void setClock(LongSupplier clock) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
    }

    public void setConnected(BooleanSupplier connected) {
        this.connected = (connected == null) ? () -> false : connected;
    }

    public void setEnabled(BooleanSupplier enabled) {
        this.enabled = (enabled == null) ? () -> false : enabled;
    }

    public void setGateway(CommandGateway gateway) {
        this.gateway = (gateway == null) ? CommandGateway.SHARED : gateway;
    }

    /**
     * 采集命令已发出、等持仓回执落盘时为 true。
     * 超过 {@link #TAG_TTL_MS} 自动失效并打一行说明（超时少一行是合理的）。
     */
    public boolean pendingCollectTag() {
        if (!tagNextHoldings) {
            return false;
        }
        long now = clock.getAsLong();
        if (tagAtMs != Long.MIN_VALUE && now - tagAtMs > TAG_TTL_MS) {
            tagNextHoldings = false;
            tagAtMs = Long.MIN_VALUE;
            BcStockLog.info("采集标记已过期（回执超时或未写入），本轮不会有 collect 行");
            return false;
        }
        return true;
    }

    /** 持仓已按 collect 落盘后清掉。 */
    public void clearCollectTag() {
        tagNextHoldings = false;
        tagAtMs = Long.MIN_VALUE;
    }

    /**
     * 行情增量/兜底成功后预约拉持仓。{@link #tick} 在单飞/间隔挡住时重试。
     */
    public void requestAfterRefresh() {
        pendingAfterRefresh = true;
        tryFire("refresh");
    }

    public void tick() {
        if (pendingAfterRefresh) {
            tryFire("retry");
        }
    }

    private void tryFire(String why) {
        if (!pendingAfterRefresh) {
            return;
        }
        if (!enabled.getAsBoolean()) {
            logSkip("collect.enabled=false");
            pendingAfterRefresh = false;
            return;
        }
        if (!connected.getAsBoolean()) {
            logSkip("玩家离线/未连接");
            return;
        }
        if (gateway.userTradeBusy()) {
            logSkip("买卖/对账优先");
            return;
        }
        boolean sent = gateway.trySendPortfolio();
        if (sent) {
            pendingAfterRefresh = false;
            tagNextHoldings = true;
            tagAtMs = clock.getAsLong();
            BcStockLog.info("采集：已发 invest portfolio（{}）", why);
        }
    }

    private void logSkip(String why) {
        long now = clock.getAsLong();
        if (now - lastSkipLogAt < CommandGateway.REJECT_LOG_MIN_GAP_MS) {
            return;
        }
        lastSkipLogAt = now;
        BcStockLog.info("采集跳过：{}", why);
    }
}
