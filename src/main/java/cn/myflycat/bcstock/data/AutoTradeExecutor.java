package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.decision.AutoTradePlanner;
import cn.myflycat.bcstock.decision.DecisionPreset;
import cn.myflycat.bcstock.ui.TradeDraft;
import java.util.Map;
import java.util.Optional;

/**
 * 把 {@link AutoTradePlanner} 的意图交给网关。最多一笔。不引用 {@code net.minecraft}。
 *
 * <p>两阶段（2026-10-21 拍板：自动单先查实时价，不再按盘面牌价乐观校验）：
 *
 * <ol>
 *   <li>{@link #tryOnce} 规划出意图后先发 {@code invest company info <market_id>}，暂存意图；
 *   <li>下一个 {@link #tick} 若拿到<b>同一家公司</b>的实时价，用实时价重夹数量并发单； 查价失败 / 超时 / 回执串台 → 放弃本轮（宁可不做，不按乐观价下单）。
 * </ol>
 *
 * 手动链路本来就是先查价再确认，这条对齐后自动链路不再比手动乐观。
 */
public final class AutoTradeExecutor {

  /** 查价阶段上限：单飞等得起的最长时间（与手动确认框同口径）。 */
  public static final long QUOTE_GIVE_UP_MS =
      CommandGateway.TIMEOUT_MS + CommandGateway.SETTLE_MS + CommandGateway.MIN_GAP_MS;

  private static volatile AutoTradePlanner.Intent pending;
  private static volatile long pendingAtMs;

  private AutoTradeExecutor() {}

  /** 清掉在途意图（断线复位 / 测试用）：挂着的查价不许跨会话。 */
  public static void resetPending() {
    pending = null;
    pendingAtMs = 0;
  }

  /** 行情刷新成功后的自动单入口（主线程 tick 里调用）。 有查价在途时本调用让位，不重复规划。 */
  public static boolean tryOnce(
      SnapshotStore store, FloorCache floors, CommandGateway gateway, long nowMs) {
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
    if (pending != null) {
      // 已有意图在等实时价；tick 会收尾。
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
    Optional<AutoTradePlanner.Intent> intent =
        AutoTradePlanner.plan(snap, floorMap, preset, limits, nowMs);
    if (intent.isEmpty()) {
      return false;
    }
    AutoTradePlanner.Intent i = intent.get();
    int marketId = i.company().marketId();
    if (!i.company().marketIdKnown()) {
      BcStockLog.info("自动下单放弃：{} 编号未知（不拿上次读到的号下单）", i.company().name());
      return false;
    }
    pending = i;
    pendingAtMs = nowMs;
    boolean sent = gateway.trySendCompanyInfoLive(marketId);
    if (!sent) {
      pending = null;
      BcStockLog.info("自动下单：查价发不出（单飞/间隔/门禁），本轮放弃 {}", i.company().name());
      return false;
    }
    BcStockLog.info("自动下单：先查实时价 {} (#{})", i.company().name(), marketId);
    return false;
  }

  /**
   * 主线程每个 tick 调一次：查价在途时收实时价并按实时价完成下单。 {@code nowMs} 由调用方传（与网关同一时钟）。 查价失败 / 超时 / 回执串台 →
   * 放弃本轮，等下一轮行情刷新重新规划。
   */
  public static void tick(SnapshotStore store, CommandGateway gateway, long nowMs) {
    AutoTradePlanner.Intent i = pending;
    if (i == null || store == null || gateway == null) {
      return;
    }
    long now = nowMs;
    if (now - pendingAtMs > QUOTE_GIVE_UP_MS) {
      BcStockLog.info("自动下单：查价超时，本轮放弃 {}", i.company().name());
      pending = null;
      return;
    }
    if (gateway.inFlight()) {
      return;
    }
    Optional<CompanyView> quoted = gateway.lastCompany();
    if (quoted.isEmpty() || quoted.get().marketId() != i.company().marketId()) {
      // 回执还没到 / 串台：等下个 tick，直到超时。
      return;
    }
    pending = null;
    CompanyView live = quoted.get();
    if (!live.priceKnown() || live.price() <= 0) {
      BcStockLog.info("自动下单：实时价读不到，本轮放弃 {}", i.company().name());
      return;
    }
    finishWithLivePrice(store, gateway, i, live.price(), now);
  }

  /** 仅测试：当前是否有意图在等实时价。 */
  public static boolean pendingForTest() {
    return pending != null;
  }

  private static void finishWithLivePrice(
      SnapshotStore store,
      CommandGateway gateway,
      AutoTradePlanner.Intent i,
      double livePrice,
      long nowMs) {
    if (!TradeSettings.enabled() || AutoTradeSettings.kill()) {
      return;
    }
    if (AutoTradeSettings.mode() == AutoTradeSettings.Mode.ADVICE) {
      return;
    }
    if (gateway.inFlight() || gateway.userTradeBusy()) {
      return;
    }
    StockSnapshot snap = store.get();
    Map<String, FloorView> floorMap = Map.of();
    LedgerStore ledger = store.ledger();
    int filledToday = ledger == null ? 0 : ledger.filledQtyOnDay(nowMs);
    long lastAuto = ledger == null ? 0L : ledger.lastAutoSentAt();
    AutoTradePlanner.Limits limits = AutoTradePlanner.Limits.fromSettings(filledToday, lastAuto);
    HoldingView holding = snap.holdingOf(i.company().name());
    // 实时价可能比牌价高：先按实时价重夹数量（余额/现金底线都按实时价算），
    // 再构造 draft——顺序反了会让 create 按原数量查余额、直接拒绝。
    int clamped =
        AutoTradePlanner.clampQtyForLive(
            i.side(), i.qty(), i.company(), snap.wallet(), limits, livePrice);
    if (clamped < 1) {
      BcStockLog.info("自动下单：实时价下限额把数量夹成 0，本轮放弃 {}", i.company().name());
      return;
    }
    Optional<TradeDraft> draft =
        TradeDraft.create(i.company(), i.side(), clamped, snap.wallet(), holding, livePrice);
    if (draft.isEmpty()) {
      BcStockLog.info(
          "自动下单：实时价 {} 下门槛不过（余额/可用股数），本轮放弃 {}", TradeDraft.money(livePrice), i.company().name());
      return;
    }
    boolean accepted = gateway.trySendDraft(draft.get(), OrderRecord.Origin.AUTO);
    if (accepted && ledger != null) {
      ledger.setLastAutoSentAt(nowMs);
    }
    if (accepted) {
      BcStockLog.info(
          "自动下单：{} {} {} 股 @实时价 {}（{}）",
          i.side().title,
          i.company().name(),
          draft.get().qty(),
          TradeDraft.money(livePrice),
          i.reason());
    }
  }
}
