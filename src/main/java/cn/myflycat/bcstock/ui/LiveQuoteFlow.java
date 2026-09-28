package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.CompanyView;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 确认框打开后拉 {@code /invest company info} 实时价。纯状态机，可离线测。
 * 不阻塞：每个 tick 试发 / 收；超时退回牌价。
 */
public final class LiveQuoteFlow {

    public enum Phase {
        QUOTING,
        READY,
        FAILED
    }

    /** 单飞挡住时再等一会儿，超过这个窗口就放弃。 */
    public static final long GIVE_UP_MS =
            CommandGateway.TIMEOUT_MS + CommandGateway.SETTLE_MS + CommandGateway.MIN_GAP_MS;

    private final LongSupplier clock;
    private final long startedAt;
    private Phase phase = Phase.QUOTING;
    private boolean sent;
    private double livePrice = Double.NaN;

    public LiveQuoteFlow() {
        this(System::currentTimeMillis);
    }

    public LiveQuoteFlow(LongSupplier clock) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
        this.startedAt = this.clock.getAsLong();
    }

    public Phase phase() {
        return phase;
    }

    public Optional<Double> livePrice() {
        return TradeDraft.liveKnown(livePrice) ? Optional.of(livePrice) : Optional.empty();
    }

    public void tick(CommandGateway gateway, int marketId) {
        if (phase != Phase.QUOTING || gateway == null || marketId < 0) {
            return;
        }
        long now = clock.getAsLong();
        if (now - startedAt > GIVE_UP_MS) {
            phase = Phase.FAILED;
            return;
        }
        if (!sent) {
            sent = gateway.trySendCompanyInfoLive(marketId);
            return;
        }
        if (gateway.inFlight()) {
            return;
        }
        Optional<CompanyView> company = gateway.lastCompany();
        if (company.isPresent()
                && company.get().marketId() == marketId
                && company.get().priceKnown()) {
            livePrice = company.get().price();
            phase = Phase.READY;
            return;
        }
        phase = Phase.FAILED;
    }
}
