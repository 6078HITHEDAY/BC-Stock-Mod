package cn.myflycat.bcstock.decision;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import cn.myflycat.bcstock.ui.TradeDraft;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 行情刷新后最多交出一笔自动单意图。纯函数，不发命令。
 */
public final class AutoTradePlanner {

    public record Limits(
            AutoTradeSettings.Mode mode,
            boolean allowBuy,
            boolean allowSell,
            int maxPerTrade,
            int maxPerDay,
            int filledToday,
            long cooldownMs,
            long lastAutoAtMs,
            double cashFloor,
            List<String> whitelist) {

        public Limits {
            if (mode == null) {
                mode = AutoTradeSettings.Mode.ADVICE;
            }
            if (whitelist == null) {
                whitelist = List.of();
            }
        }

        public static Limits fromSettings(int filledToday, long lastAutoAtMs) {
            return new Limits(
                    AutoTradeSettings.mode(),
                    AutoTradeSettings.allowBuy(),
                    AutoTradeSettings.allowSell(),
                    AutoTradeSettings.maxPerTrade(),
                    AutoTradeSettings.maxPerDay(),
                    filledToday,
                    AutoTradeSettings.cooldownMs(),
                    lastAutoAtMs,
                    AutoTradeSettings.cashFloor(),
                    AutoTradeSettings.whitelistTokens());
        }
    }

    public record Intent(TradeDraft.Side side, CompanyView company, int qty, String reason) {
    }

    private AutoTradePlanner() {
    }

    public static Optional<Intent> plan(StockSnapshot snap, Map<String, FloorView> floors,
                                        DecisionPreset preset, Limits limits, long nowMs) {
        if (snap == null || limits == null) {
            return Optional.empty();
        }
        if (limits.mode() == AutoTradeSettings.Mode.ADVICE) {
            return Optional.empty();
        }
        if (limits.lastAutoAtMs() > 0 && limits.cooldownMs() > 0
                && nowMs - limits.lastAutoAtMs() < limits.cooldownMs()) {
            return Optional.empty();
        }
        WalletView wallet = snap.wallet();
        if (!wallet.known()) {
            return Optional.empty();
        }
        DecisionPreset p = (preset == null) ? DecisionPreset.DEFAULT : preset;
        Map<String, FloorView> floorMap = (floors == null) ? Map.of() : floors;

        if (limits.allowSell()) {
            Optional<Intent> sell = firstSell(snap, floorMap, p, limits, wallet);
            if (sell.isPresent()) {
                return sell;
            }
        }
        if (limits.allowBuy()) {
            return firstBuy(snap, floorMap, p, limits, wallet);
        }
        return Optional.empty();
    }

    private static Optional<Intent> firstSell(StockSnapshot snap, Map<String, FloorView> floors,
                                              DecisionPreset preset, Limits limits, WalletView wallet) {
        for (HoldingView h : snap.heldOnly()) {
            CompanyView company = snap.companyOf(h.name());
            if (company == null || !company.tradable()) {
                continue;
            }
            if (!whitelisted(company, limits.whitelist())) {
                continue;
            }
            FloorView floor = floors.get(h.name());
            DecisionEngine.Advice advice = DecisionEngine.advise(company, h, floor, preset);
            if (advice.side() != DecisionEngine.Side.SELL || advice.qty() < 1) {
                continue;
            }
            int qty = clampQty(advice.qty(), TradeDraft.Side.SELL, company, wallet, limits);
            if (qty < 1) {
                continue;
            }
            Optional<TradeDraft> draft = TradeDraft.create(company, TradeDraft.Side.SELL, qty, wallet, h);
            if (draft.isEmpty()) {
                continue;
            }
            return Optional.of(new Intent(TradeDraft.Side.SELL, company, qty, advice.reason()));
        }
        return Optional.empty();
    }

    private static Optional<Intent> firstBuy(StockSnapshot snap, Map<String, FloorView> floors,
                                             DecisionPreset preset, Limits limits, WalletView wallet) {
        for (CompanyView company : snap.companies()) {
            if (company == null || !company.tradable()) {
                continue;
            }
            if (!whitelisted(company, limits.whitelist())) {
                continue;
            }
            FloorView floor = floors.get(company.name());
            HoldingView held = snap.holdingOf(company.name());
            DecisionEngine.Advice advice = DecisionEngine.advise(company, held, floor, preset);
            if (advice.side() != DecisionEngine.Side.BUY || advice.qty() < 1) {
                continue;
            }
            int qty = clampQty(advice.qty(), TradeDraft.Side.BUY, company, wallet, limits);
            if (qty < 1) {
                continue;
            }
            Optional<TradeDraft> draft = TradeDraft.create(company, TradeDraft.Side.BUY, qty, wallet, held);
            if (draft.isEmpty()) {
                continue;
            }
            return Optional.of(new Intent(TradeDraft.Side.BUY, company, qty, advice.reason()));
        }
        return Optional.empty();
    }

    static int clampQty(int advised, TradeDraft.Side side, CompanyView company,
                        WalletView wallet, Limits limits) {
        int qty = advised;
        if (side == TradeDraft.Side.BUY) {
            qty = Math.min(qty, TradeSettings.MAX_CUSTOM_QTY);
            if (limits.mode() == AutoTradeSettings.Mode.LIMITED) {
                qty = Math.min(qty, Math.max(1, limits.maxPerTrade()));
                int remain = limits.maxPerDay() - limits.filledToday();
                qty = Math.min(qty, Math.max(0, remain));
            }
            double price = company.price();
            if (price > 0 && wallet.known()) {
                double room = wallet.balance() - limits.cashFloor();
                int affordable = (int) Math.floor(room / price);
                qty = Math.min(qty, Math.max(0, affordable));
            }
        } else if (limits.mode() == AutoTradeSettings.Mode.LIMITED) {
            qty = Math.min(qty, Math.max(1, limits.maxPerTrade()));
            int remain = limits.maxPerDay() - limits.filledToday();
            qty = Math.min(qty, Math.max(0, remain));
        }
        return qty;
    }

    static boolean whitelisted(CompanyView company, List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return true;
        }
        for (String t : tokens) {
            if (t.equalsIgnoreCase(company.name())) {
                return true;
            }
            if (company.marketIdKnown() && t.equals(Integer.toString(company.marketId()))) {
                return true;
            }
        }
        return false;
    }
}
