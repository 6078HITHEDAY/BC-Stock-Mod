package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.OrderRecord;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.LedgerProbe;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import java.util.Optional;

/**
 * 确认框状态机。Esc 取消必须一个字节都不发；回车才走 {@link CommandGateway}。
 * 可离线测（假网关 + 假时钟），不引用 {@code net.minecraft}。
 *
 * <p>{@link AutoTradeSettings#kill()} 为 true 时 confirm 直接拒发。
 * 点确认后进入 {@link Phase#SUBMITTING}，等买卖回执才 {@link Phase#FILLED}。
 */
public final class TradeFlow {

    public enum Phase {
        IDLE,
        OPEN,
        SUBMITTING,
        FILLED,
        FAILED
    }

    private TradeDraft pending;
    private Phase phase = Phase.IDLE;
    private String failReason = "";

    public Optional<TradeDraft> pending() {
        return Optional.ofNullable(pending);
    }

    public Phase phase() {
        return phase;
    }

    public String failReason() {
        return failReason;
    }

    public String statusLine() {
        if (phase == Phase.SUBMITTING) {
            return "正在下单…";
        }
        if (phase == Phase.FAILED && failReason != null && !failReason.isBlank()) {
            return failReason;
        }
        return null;
    }

    public boolean shouldClose() {
        return phase == Phase.FILLED;
    }

    public boolean tryOpen(CompanyView company, TradeDraft.Side side, int qty,
                           WalletView wallet, HoldingView holding) {
        pending = null;
        phase = Phase.IDLE;
        failReason = "";
        if (!TradeSettings.enabled()) {
            return false;
        }
        Optional<TradeDraft> draft = TradeDraft.create(company, side, qty, wallet, holding);
        if (draft.isEmpty()) {
            return false;
        }
        pending = draft.get();
        phase = Phase.OPEN;
        return true;
    }

    /** 已校验过的草稿直接进入确认态（独立确认屏用）。 */
    public boolean tryOpenDraft(TradeDraft draft) {
        pending = null;
        phase = Phase.IDLE;
        failReason = "";
        if (!TradeSettings.enabled() || draft == null) {
            return false;
        }
        pending = draft;
        phase = Phase.OPEN;
        return true;
    }

    public void cancel() {
        cancel(null);
    }

    public void cancel(CommandGateway gateway) {
        if (gateway != null) {
            gateway.cancelQueuedTrade();
        }
        pending = null;
        phase = Phase.IDLE;
        failReason = "";
    }

    /** 回车确认。没有草稿 / 开关关着 / kill → 不发。接受排队也算成功。 */
    public boolean confirm(CommandGateway gateway) {
        TradeDraft draft = pending;
        if (draft == null || !TradeSettings.enabled() || gateway == null) {
            return false;
        }
        if (AutoTradeSettings.kill()) {
            phase = Phase.FAILED;
            failReason = "自动交易已掐死";
            return false;
        }
        if (phase == Phase.SUBMITTING) {
            return true;
        }
        LedgerProbe.beginConfirm();
        LedgerProbe.info("确认点击 {}", draft.command());
        boolean accepted = gateway.trySendDraft(draft, OrderRecord.Origin.MANUAL);
        if (accepted) {
            phase = Phase.SUBMITTING;
            failReason = "";
            return true;
        }
        phase = Phase.FAILED;
        failReason = "未发出";
        LedgerProbe.info("确认未发出 {}", draft.command());
        return false;
    }

    /** 每个 tick：看买卖回执。 */
    public void tick(CommandGateway gateway) {
        if (phase != Phase.SUBMITTING || gateway == null) {
            return;
        }
        Optional<CommandGateway.TradeOutcome> t = gateway.lastTrade();
        if (t.isEmpty()) {
            return;
        }
        CommandGateway.TradeOutcome o = t.get();
        if (o.confirmed()) {
            phase = Phase.FILLED;
            LedgerProbe.info("成交确认");
            return;
        }
        phase = Phase.FAILED;
        failReason = (o.message() == null || o.message().isBlank()) ? "下单失败" : o.message();
        LedgerProbe.info("成交失败 {}", failReason);
    }
}
