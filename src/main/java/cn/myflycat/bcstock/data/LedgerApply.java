package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.TradeDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 成交后的乐观入账。对账回执随后覆盖。不 import {@code net.minecraft}。
 */
public final class LedgerApply {

    private LedgerApply() {
    }

    public static String fillMessage(TradeDraft draft) {
        if (draft == null) {
            return "";
        }
        return draft.side().title + " " + draft.name() + " " + draft.qty()
                + " 股 @ " + TradeDraft.money(draft.fillPrice());
    }

    public static StockSnapshot optimistic(StockSnapshot snap, TradeDraft draft, Instant at) {
        if (snap == null || draft == null) {
            return snap;
        }
        Instant when = (at == null) ? Instant.EPOCH : at;
        List<HoldingView> holdings = new ArrayList<>(
                snap.holdingsKnown() ? snap.holdingsOrEmpty() : List.of());
        int idx = indexOf(holdings, draft.name());
        long delta = (draft.side() == TradeDraft.Side.BUY) ? draft.qty() : -draft.qty();
        double fill = draft.fillPrice();
        double valueDelta = fill * draft.qty() * (delta >= 0 ? 1 : -1);
        if (idx < 0) {
            if (draft.side() == TradeDraft.Side.BUY) {
                holdings.add(new HoldingView(
                        draft.name(), draft.marketId(), "", draft.qty(),
                        round2(fill * draft.qty()), fill));
            }
        } else {
            HoldingView old = holdings.get(idx);
            long shares = Math.max(0L, old.shares() + delta);
            double value = Math.max(0.0, round2(old.currentValue() + valueDelta));
            double avg = old.averageBuyPrice();
            if (draft.side() == TradeDraft.Side.BUY && shares > 0) {
                if (old.averageBuyPriceKnown() && old.shares() > 0) {
                    avg = (old.cost() + fill * draft.qty()) / shares;
                } else {
                    avg = fill;
                }
            }
            holdings.set(idx, new HoldingView(
                    old.name(), old.marketId(), old.status(), shares, value, avg));
        }
        WalletView wallet = snap.wallet();
        if (wallet.known()) {
            wallet = new WalletView(draft.walletAfter());
        }
        return snap.withHoldings(holdings, when).withWallet(wallet, when);
    }

    public static void applyToStore(SnapshotStore store, TradeDraft draft, Instant at) {
        if (store == null || draft == null) {
            return;
        }
        Instant when = (at == null) ? Instant.now() : at;
        StockSnapshot next = optimistic(store.get(), draft, when);
        store.updateHoldings(next.holdingsOrEmpty(), when, "optimistic");
        store.updateWallet(next.wallet(), when);
        HoldingView held = next.holdingOf(draft.name());
        long shares = held == null ? 0L : held.shares();
        LedgerProbe.info("乐观入账 {} 持股 {} 余额 {}",
                draft.name(),
                shares,
                next.wallet().known()
                        ? TradeDraft.money(next.wallet().balance())
                        : "未知");
    }

    private static int indexOf(List<HoldingView> holdings, String name) {
        for (int i = 0; i < holdings.size(); i++) {
            if (holdings.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
