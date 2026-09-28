package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.decision.AutoTradePlanner;
import cn.myflycat.bcstock.decision.DecisionPreset;
import cn.myflycat.bcstock.ui.TradeDraft;
import java.util.Map;
import java.util.Optional;

/**
 * 把 {@link AutoTradePlanner} 的意图交给网关。最多一笔。不引用 {@code net.minecraft}。
 */
public final class AutoTradeExecutor {

    private AutoTradeExecutor() {
    }

    public static boolean tryOnce(SnapshotStore store, FloorCache floors, CommandGateway gateway,
                                  long nowMs) {
        if (store == null || gateway == null) {
            return false;
        }
        if (!TradeSettings.enabled() || AutoTradeSettings.kill()) {
            return false;
        }
        if (AutoTradeSettings.mode() == AutoTradeSettings.Mode.ADVICE) {
            return false;
        }
        if (gateway.inFlight() || gateway.userTradeBusy()) {
            return false;
        }
        StockSnapshot snap = store.get();
        Map<String, FloorView> floorMap = Map.of();
        if (floors != null) {
            floorMap = floors.get().map(FloorSnapshot::byName).orElse(Map.of());
        }
        LedgerStore ledger = store.ledger();
        int filledToday = ledger == null ? 0 : ledger.filledQtyOnDay(nowMs);
        long lastAuto = ledger == null ? 0L : ledger.lastAutoSentAt();
        AutoTradePlanner.Limits limits = AutoTradePlanner.Limits.fromSettings(filledToday, lastAuto);
        DecisionPreset preset = BcStockSettings.decisionPreset();
        Optional<AutoTradePlanner.Intent> intent = AutoTradePlanner.plan(snap, floorMap, preset, limits, nowMs);
        if (intent.isEmpty()) {
            return false;
        }
        AutoTradePlanner.Intent i = intent.get();
        HoldingView holding = snap.holdingOf(i.company().name());
        Optional<TradeDraft> draft = TradeDraft.create(i.company(), i.side(), i.qty(), snap.wallet(), holding);
        if (draft.isEmpty()) {
            return false;
        }
        boolean accepted = gateway.trySendDraft(draft.get(), OrderRecord.Origin.AUTO);
        if (accepted) {
            if (ledger != null) {
                ledger.setLastAutoSentAt(nowMs);
            }
            BcStockLog.info("自动下单：{} {} {} 股（{}）",
                    i.side().title, i.company().name(), i.qty(), i.reason());
        }
        return accepted;
    }
}
