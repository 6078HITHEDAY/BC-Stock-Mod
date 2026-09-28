package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.data.reply.BalanceReply;
import cn.myflycat.bcstock.data.reply.CompaniesReply;
import cn.myflycat.bcstock.data.reply.CompanyInfoReply;
import cn.myflycat.bcstock.data.reply.PortfolioReply;
import cn.myflycat.bcstock.data.reply.ReplyText;
import cn.myflycat.bcstock.data.reply.TradeReply;
import cn.myflycat.bcstock.ui.TradeDraft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 命令网关。三条铁律：
 * <ol>
 *   <li><b>单飞</b>：同一时刻只允许一条命令在飞（并发发命令会让回执串台，无法归因）；</li>
 *   <li><b>节流</b>：两条命令之间至少 {@link #MIN_GAP_MS}，且同一采集命令
 *       {@link #COLLECT_COOLDOWN_MS} 内不重复发；</li>
 *   <li><b>静默失败</b>：超时返回 empty，不抛异常、不重试（重试 = 刷屏）。</li>
 * </ol>
 *
 * <p>本类<b>不引用</b> {@code net.minecraft}。发送走 {@link CommandSender}，
 * 时钟走 {@link LongSupplier}，所以节流能离线测。游戏里由 {@code BcStockClient}
 * 把 {@code sendChatCommand}（Yarn {@code method_45730}，<b>不带前导 /</b>）
 * 和客户端 tick 接上来。
 *
 * <p>聊天注入点：{@code ClientPlayNetworkHandler.onGameMessage}
 * （Yarn 名；中介名 {@code method_43596}；包 {@code GameMessageS2CPacket} /
 * {@code class_7439}，字段 {@code content}）。Mixin 只把
 * {@code packet.content().getString()} 交给 {@link #onChatLine}。
 *
 * <p>回执是分多行来的，块结束前不下结论。主线程不能阻塞等回执——
 * {@link #trySend} 只负责发出去，{@link #tick} / {@link #onChatLine} 收齐再解析。
 */
public final class CommandGateway {

    public static final long MIN_GAP_MS = 1_000L;
    public static final long TIMEOUT_MS = 5_000L;
    /** {@code /invest companies} 一屏 160+ 行，5s 会误超时。 */
    public static final long COMPANIES_TIMEOUT_MS = 15_000L;
    public static final long SETTLE_MS = 800L;
    public static final long COLLECT_COOLDOWN_MS = 15L * 60L * 1000L;
    /**
     * 采集冷却余量。相位 +8s 相邻两次间隔名义上正好 15 分钟；
     * tick 抖动若早几十毫秒，严格 {@code < COOLDOWN} 会静默跳过一整轮。
     * 判据改为 {@code < COOLDOWN - SLACK}，899.9s 仍放行、明显不足仍拒。
     */
    public static final long COLLECT_COOLDOWN_SLACK_MS = 250L;
    /** 下单成功发出后，延时多久发持仓对账。 */
    public static final long RECONCILE_DELAY_MS = 3_000L;
    /** 对账失败重试窗口（从首次预约起算，含 delay）。超时放弃。 */
    public static final long RECONCILE_RETRY_WINDOW_MS = 30_000L;

    /** 游戏里用的那一个。测试请 {@code new}，别碰它。 */
    public static final CommandGateway SHARED = new CommandGateway();

    /** 采集命令走 15 分钟冷却；{@link #BUY}/{@link #SELL} 不走这条冷却。 */
    public enum Kind {
        BAL,
        PORTFOLIO,
        COMPANY_INFO,
        COMPANIES,
        BUY,
        SELL
    }

    private final LongSupplier clock;
    private CommandSender sender;
    private BooleanSupplier commandHealth = () -> true;

    private Kind inFlightKind;
    private String inFlightCommand;
    private int inFlightMarketId = CompanyView.ID_UNKNOWN;
    private final List<String> collected = new ArrayList<>();
    private long sentAt;
    private long lastLineAt;
    private boolean sawBlockStart;
    private boolean sawBlockEnd;

    private long lastAnySentAt = Long.MIN_VALUE / 2;
    /** 冷却 key：BAL/PORTFOLIO 用 Kind 名；COMPANY_INFO 用 {@code COMPANY_INFO#marketId}。 */
    private final Map<String, Long> lastCollectSentAt = new HashMap<>();

    private Optional<WalletView> lastBalance = Optional.empty();
    private Optional<List<HoldingView>> lastHoldings = Optional.empty();
    private Optional<CompanyView> lastCompany = Optional.empty();
    private Optional<List<CompanyView>> lastCompanies = Optional.empty();
    private boolean lastTimedOut;
    /** 买卖回执：空 = 没发过；有值但 {@code confirmed=false} = 未确认（成功格式从未实测）。 */
    private Optional<TradeOutcome> lastTrade = Optional.empty();
    private long reconcileAt = Long.MAX_VALUE;
    /** 对账截止：超过则放弃持仓/余额对账，防死循环。 */
    private long reconcileDeadline = Long.MAX_VALUE;
    /** portfolio 对账已成功发出、等待回执结束后再发 bal。 */
    private boolean reconcileAwaitingPortfolioDone;
    /** portfolio 对账完成后，再发一次 bal（同样免采集冷却）。 */
    private boolean reconcileNeedBal;
    /** 乐观入账后、对账尚未覆盖。失败时不要悄悄回到成交前。 */
    private boolean ledgerOptimistic;
    private Kind lastSentKind;
    private int lastSentMarketId = CompanyView.ID_UNKNOWN;
    private Kind queuedKind;
    private int queuedMarketId = CompanyView.ID_UNKNOWN;
    private int queuedQty;
    private long queuedOrderId = -1L;
    private TradeDraft queuedDraft;
    private OrderRecord.Origin queuedOrigin = OrderRecord.Origin.MANUAL;
    private long inFlightOrderId = -1L;
    private TradeDraft inFlightDraft;
    private OrderRecord.Origin inFlightOrigin = OrderRecord.Origin.MANUAL;
    private SnapshotStore snapshotStore;
    private Consumer<TradeDraft> onFilled;

    /** 聊天「所有商业股票已更新」——只许设 flag，不许在回调里调 {@code trySend}（会死锁）。 */
    private volatile Runnable onMarketUpdated;

    /**
     * 拒绝路径日志最小间隔。节流行为本身一毫米不改，只限制同因刷屏。
     * 15s ≈ 一个相位窗口内连续 tick 拒绝只打一行量级。
     */
    public static final long REJECT_LOG_MIN_GAP_MS = 15_000L;
    private final Map<String, Long> lastRejectLogAt = new HashMap<>();
    private int rejectLogCount;

    public CommandGateway() {
        this(System::currentTimeMillis, command -> false);
    }

    public CommandGateway(LongSupplier clock, CommandSender sender) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
        this.sender = (sender == null) ? command -> false : sender;
    }

    public void setSender(CommandSender sender) {
        this.sender = (sender == null) ? command -> false : sender;
    }

    /**
     * 命令通路健不健康。今天恒 true（还没把超时/断线算进来）。
     * 调用点必须问这个方法，不许写字面量 {@code true}。
     */
    public boolean commandHealthy() {
        return commandHealth.getAsBoolean();
    }

    /** 仅测试用。正式代码不要改。 */
    public void setCommandHealthForTest(BooleanSupplier commandHealth) {
        this.commandHealth = (commandHealth == null) ? () -> true : commandHealth;
    }

    public boolean inFlight() {
        return inFlightKind != null;
    }

    public Optional<WalletView> lastBalance() {
        return lastBalance;
    }

    public Optional<List<HoldingView>> lastHoldings() {
        return lastHoldings;
    }

    public Optional<CompanyView> lastCompany() {
        return lastCompany;
    }

    public Optional<List<CompanyView>> lastCompanies() {
        return lastCompanies;
    }

    /**
     * 取出并清空最近一次公司列表回执。刷新协调器消费用，避免每 tick 重复叠加。
     */
    public synchronized Optional<List<CompanyView>> takeLastCompanies() {
        Optional<List<CompanyView>> got = lastCompanies;
        lastCompanies = Optional.empty();
        return got;
    }

    /** 行情刷新广播回调。回调里只许记 flag，不许调本网关。 */
    public void setOnMarketUpdated(Runnable onMarketUpdated) {
        this.onMarketUpdated = onMarketUpdated;
    }

    public void setSnapshotStore(SnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    public void setOnFilled(Consumer<TradeDraft> onFilled) {
        this.onFilled = onFilled;
    }

    public boolean lastTimedOut() {
        return lastTimedOut;
    }

    public Optional<TradeOutcome> lastTrade() {
        return lastTrade;
    }

    public record TradeOutcome(boolean error, boolean confirmed, String message) {
        public static TradeOutcome unconfirmed() {
            return new TradeOutcome(false, false, "未确认（成功回执格式未匹配）");
        }

        public static TradeOutcome confirmed(String message) {
            return new TradeOutcome(false, true, message == null ? "" : message);
        }

        public static TradeOutcome error(String message) {
            return new TradeOutcome(true, false, message);
        }
    }

    public synchronized boolean trySendBuy(int marketId, int qty) {
        return trySendTrade(Kind.BUY, marketId, qty, null, OrderRecord.Origin.MANUAL, -1L);
    }

    public synchronized boolean trySendSell(int marketId, int qty) {
        return trySendTrade(Kind.SELL, marketId, qty, null, OrderRecord.Origin.MANUAL, -1L);
    }

    public synchronized boolean trySendDraft(TradeDraft draft, OrderRecord.Origin origin) {
        if (draft == null) {
            return false;
        }
        Kind kind = (draft.side() == TradeDraft.Side.BUY) ? Kind.BUY : Kind.SELL;
        return trySendTrade(kind, draft.marketId(), draft.qty(), draft, origin, -1L);
    }

    private synchronized boolean trySendTrade(Kind kind, int marketId, int qty,
                                              TradeDraft draft, OrderRecord.Origin origin,
                                              long existingOrderId) {
        if (AutoTradeSettings.kill()) {
            BcStockLog.info("命令跳过：auto.kill=true，拒绝下单 marketId={} qty={}", marketId, qty);
            return false;
        }
        if (marketId < 0 || qty < 1) {
            BcStockLog.info("命令跳过：下单参数非法 marketId={} qty={}", marketId, qty);
            return false;
        }
        if (kind == Kind.BUY && qty > TradeSettings.MAX_CUSTOM_QTY) {
            BcStockLog.info("命令跳过：买入超过上限 {} marketId={} qty={}",
                    TradeSettings.MAX_CUSTOM_QTY, marketId, qty);
            return false;
        }
        lastTrade = Optional.empty();
        String verb = (kind == Kind.BUY) ? "buy" : "sell";
        LedgerStore ledger = ledger();
        long orderId = existingOrderId;
        if (ledger != null && orderId < 0) {
            TradeDraft.Side side = (kind == Kind.BUY) ? TradeDraft.Side.BUY : TradeDraft.Side.SELL;
            String name = draft == null ? "" : draft.name();
            orderId = ledger.insertPending(side, marketId, name, qty,
                    origin == null ? OrderRecord.Origin.MANUAL : origin, clock.getAsLong());
        }
        if (blockedForNow(kind, marketId)) {
            queueTrade(kind, marketId, qty, orderId, draft, origin);
            return true;
        }
        boolean sent = trySend(kind, "invest " + verb + " " + marketId + " " + qty, marketId);
        if (sent) {
            inFlightOrderId = orderId;
            inFlightDraft = draft;
            inFlightOrigin = (origin == null) ? OrderRecord.Origin.MANUAL : origin;
            if (ledger != null && orderId >= 0) {
                ledger.markSent(orderId, clock.getAsLong());
            }
        } else if (ledger != null && orderId >= 0) {
            ledger.markCancelled(orderId, "未发出");
        }
        return sent;
    }

    private boolean skipMinGapForTrade(Kind kind, int marketId) {
        return (kind == Kind.BUY || kind == Kind.SELL)
                && lastSentKind == Kind.COMPANY_INFO
                && lastSentMarketId == marketId
                && marketId >= 0;
    }

    private boolean blockedForNow(Kind kind, int marketId) {
        if (inFlightKind != null) {
            return true;
        }
        long now = clock.getAsLong();
        return now - lastAnySentAt < MIN_GAP_MS && !skipMinGapForTrade(kind, marketId);
    }

    private void queueTrade(Kind kind, int marketId, int qty, long orderId,
                            TradeDraft draft, OrderRecord.Origin origin) {
        if (queuedOrderId >= 0 && queuedOrderId != orderId) {
            LedgerStore ledger = ledger();
            if (ledger != null) {
                ledger.markCancelled(queuedOrderId, "被新单替换");
            }
        }
        queuedKind = kind;
        queuedMarketId = marketId;
        queuedQty = qty;
        queuedOrderId = orderId;
        queuedDraft = draft;
        queuedOrigin = (origin == null) ? OrderRecord.Origin.MANUAL : origin;
        long remain = Math.max(0L, MIN_GAP_MS - (clock.getAsLong() - lastAnySentAt));
        LedgerProbe.info("排队 {} marketId={} qty={} inflight={} gapRemain={}ms",
                kind, marketId, qty, inFlightKind, remain);
    }

    private void maybeSendQueued() {
        if (queuedKind == null || inFlightKind != null) {
            return;
        }
        if (clock.getAsLong() - lastAnySentAt < MIN_GAP_MS
                && !skipMinGapForTrade(queuedKind, queuedMarketId)) {
            return;
        }
        Kind kind = queuedKind;
        int marketId = queuedMarketId;
        int qty = queuedQty;
        long orderId = queuedOrderId;
        TradeDraft draft = queuedDraft;
        OrderRecord.Origin origin = queuedOrigin;
        queuedKind = null;
        queuedMarketId = CompanyView.ID_UNKNOWN;
        queuedQty = 0;
        queuedOrderId = -1L;
        queuedDraft = null;
        queuedOrigin = OrderRecord.Origin.MANUAL;
        LedgerProbe.info("实际发出 {} marketId={} qty={}", kind, marketId, qty);
        trySendTrade(kind, marketId, qty, draft, origin, orderId);
    }

    /**
     * 用户买单/卖单在飞、排队，或下单后对账未完。盘面开盘拉数和行情 companies 必须让路。
     */
    public synchronized boolean userTradeBusy() {
        return queuedKind == Kind.BUY || queuedKind == Kind.SELL
                || inFlightKind == Kind.BUY || inFlightKind == Kind.SELL
                || reconcileAt != Long.MAX_VALUE
                || reconcileNeedBal
                || reconcileAwaitingPortfolioDone;
    }

    public synchronized boolean cancelQueuedTrade() {
        if (queuedKind == null) {
            return false;
        }
        LedgerProbe.info("取消排队 {}", queuedKind);
        if (queuedOrderId >= 0) {
            LedgerStore ledger = ledger();
            if (ledger != null) {
                ledger.markCancelled(queuedOrderId, "用户取消");
            }
        }
        queuedKind = null;
        queuedMarketId = CompanyView.ID_UNKNOWN;
        queuedQty = 0;
        queuedOrderId = -1L;
        queuedDraft = null;
        queuedOrigin = OrderRecord.Origin.MANUAL;
        return true;
    }

    public synchronized void markOptimisticLedger() {
        ledgerOptimistic = true;
    }

    public synchronized boolean inFlightUserTrade() {
        return inFlightKind == Kind.BUY || inFlightKind == Kind.SELL;
    }

    public boolean trySendBalance() {
        return trySend(Kind.BAL, "bal", CompanyView.ID_UNKNOWN, false);
    }

    public boolean trySendPortfolio() {
        return trySend(Kind.PORTFOLIO, "invest portfolio", CompanyView.ID_UNKNOWN, false);
    }

    /**
     * 下单后对账专用：发 {@code invest portfolio}，<b>绕过</b> 15 分钟采集冷却。
     * 仍守单飞与 {@link #MIN_GAP_MS}。定时采集 / 开盘拉持仓请用 {@link #trySendPortfolio()}。
     */
    public boolean trySendPortfolioReconcile() {
        return trySend(Kind.PORTFOLIO, "invest portfolio", CompanyView.ID_UNKNOWN, true);
    }

    /** 下单后对账专用：发 {@code bal}，绕过采集冷却。 */
    public boolean trySendBalanceReconcile() {
        return trySend(Kind.BAL, "bal", CompanyView.ID_UNKNOWN, true);
    }

    public boolean trySendCompanyInfo(int marketId) {
        if (marketId < 0) {
            return false;
        }
        return trySend(Kind.COMPANY_INFO, "invest company info " + marketId, marketId, false);
    }

    /**
     * 发 {@code invest companies}（首页 16 家）。绕过采集冷却，仍守单飞与 {@link #MIN_GAP_MS}。
     * 超时 {@link #COMPANIES_TIMEOUT_MS}。发出时清掉上一份列表，避免串台。
     */
    public synchronized boolean trySendCompanies() {
        lastCompanies = Optional.empty();
        return trySend(Kind.COMPANIES, "invest companies", CompanyView.ID_UNKNOWN, true);
    }

    /**
     * 下单确认前查实时价：发 {@code invest company info}，绕过采集冷却。
     * 仍守单飞与 {@link #MIN_GAP_MS}。成功发出时清掉上一份 {@link #lastCompany}，避免串台。
     */
    public synchronized boolean trySendCompanyInfoLive(int marketId) {
        if (marketId < 0) {
            return false;
        }
        lastCompany = Optional.empty();
        return trySend(Kind.COMPANY_INFO, "invest company info " + marketId, marketId, true);
    }

    /**
     * 尝试发出一条命令。被单飞 / 间隔 / 冷却挡住，或 sender 发不出去，
     * 都返回 {@code false}，不抛异常。
     *
     * @param bypassCollectCooldown 为 true 时跳过采集 15 分钟冷却（仅下单对账）
     */
    public synchronized boolean trySend(Kind kind, String command, int marketId) {
        return trySend(kind, command, marketId, false);
    }

    public synchronized boolean trySend(Kind kind, String command, int marketId,
                                        boolean bypassCollectCooldown) {
        if (kind == null || command == null || command.isBlank()) {
            return false;
        }
        if (command.startsWith("/")) {
            BcStockLog.warn("命令带了前导 /，已拒绝（sendChatCommand 不带 /）：{}", command);
            return false;
        }
        long now = clock.getAsLong();
        if (inFlightKind != null) {
            logReject("inflight", "命令跳过：单飞中（在飞 {}），不发 {}", inFlightKind, command);
            return false;
        }
        if (now - lastAnySentAt < MIN_GAP_MS && !skipMinGapForTrade(kind, marketId)) {
            logReject("mingap", "命令跳过：距上一条不足 {}ms，不发 {}", MIN_GAP_MS, command);
            return false;
        }
        String collectKey = isCollect(kind) ? collectKey(kind, marketId) : null;
        if (collectKey != null && !bypassCollectCooldown) {
            Long lastSame = lastCollectSentAt.get(collectKey);
            long minGap = COLLECT_COOLDOWN_MS - COLLECT_COOLDOWN_SLACK_MS;
            if (lastSame != null && now - lastSame < minGap) {
                logReject("cooldown:" + collectKey,
                        "命令跳过：同一采集命令 {} 在 {}ms 内已发过，不重试",
                        collectKey, COLLECT_COOLDOWN_MS);
                return false;
            }
        }
        if (!sender.send(command)) {
            logReject("sender", "命令跳过：sender 发不出去（多半还没进服），不进入单飞：{}", command);
            return false;
        }
        inFlightKind = kind;
        inFlightCommand = command;
        inFlightMarketId = marketId;
        collected.clear();
        sentAt = now;
        lastLineAt = now;
        sawBlockStart = false;
        sawBlockEnd = false;
        lastTimedOut = false;
        lastAnySentAt = now;
        lastSentKind = kind;
        lastSentMarketId = marketId;
        if (collectKey != null) {
            lastCollectSentAt.put(collectKey, now);
        }
        if (kind == Kind.BUY || kind == Kind.SELL) {
            lastTrade = Optional.empty();
            scheduleReconcile(now);
        }
        return true;
    }

    private void scheduleReconcile(long now) {
        reconcileAt = now + RECONCILE_DELAY_MS;
        reconcileDeadline = now + RECONCILE_DELAY_MS + RECONCILE_RETRY_WINDOW_MS;
        reconcileNeedBal = false;
        reconcileAwaitingPortfolioDone = false;
    }

    /** 拒绝路径限流日志。节流行为本身不动。 */
    private void logReject(String reasonKey, String msg, Object... args) {
        long now = clock.getAsLong();
        Long last = lastRejectLogAt.get(reasonKey);
        if (last != null && now - last < REJECT_LOG_MIN_GAP_MS) {
            return;
        }
        lastRejectLogAt.put(reasonKey, now);
        rejectLogCount++;
        BcStockLog.info(msg, args);
    }

    /** 测试用：拒绝日志条数（限流后的实打条数）。 */
    public int rejectLogCountForTest() {
        return rejectLogCount;
    }

    public void resetRejectLogCountForTest() {
        rejectLogCount = 0;
        lastRejectLogAt.clear();
    }

    static boolean isCollect(Kind kind) {
        return kind == Kind.BAL || kind == Kind.PORTFOLIO || kind == Kind.COMPANY_INFO;
    }

    /** BAL / PORTFOLIO 按种类冷却；COMPANY_INFO 按「种类 + 公司」冷却。 */
    static String collectKey(Kind kind, int marketId) {
        if (kind == Kind.COMPANY_INFO) {
            return kind.name() + "#" + marketId;
        }
        return kind.name();
    }

    /**
     * Mixin 收到一条系统聊天时调用。
     * 行情刷新广播最先认、不进 collected；其余行不在飞就丢掉。
     */
    public void onChatLine(String raw) {
        String line = ReplyText.normalize(raw);
        if (ReplyText.isMarketUpdated(line)) {
            Runnable cb = onMarketUpdated;
            if (cb != null) {
                cb.run();
            }
            return;
        }
        onChatLineInFlight(line);
    }

    private synchronized void onChatLineInFlight(String line) {
        if (inFlightKind == null) {
            return;
        }
        if (line.isEmpty()) {
            lastLineAt = clock.getAsLong();
            if (acceptLine(line)) {
                collected.add("");
            }
            return;
        }
        if (!acceptLine(line)) {
            return;
        }
        collected.add(line);
        lastLineAt = clock.getAsLong();
        if (ReplyText.isBlockStart(line)) {
            sawBlockStart = true;
        }
        if (ReplyText.isBlockEnd(line)) {
            sawBlockEnd = true;
        }
        tryComplete(false);
    }

    /** 客户端每个 tick 调一次，用来判 settle / 超时。 */
    public synchronized void tick() {
        tryComplete(true);
        maybeSendQueued();
        maybeReconcile();
    }

    private void maybeReconcile() {
        if (inFlightKind != null) {
            return;
        }
        if (queuedKind == Kind.BUY || queuedKind == Kind.SELL) {
            return;
        }
        long now = clock.getAsLong();

        // 阶段 2：portfolio 已成功发出后，再发 bal
        if (reconcileNeedBal && reconcileAt == Long.MAX_VALUE) {
            if (now > reconcileDeadline) {
                reconcileNeedBal = false;
                if (ledgerOptimistic) {
                    LedgerProbe.info("对账失败，仍显示乐观数");
                }
                BcStockLog.info("下单后对账：余额超时放弃");
                return;
            }
            if (now - lastAnySentAt < MIN_GAP_MS) {
                return;
            }
            BcStockLog.info("下单后对账：发 bal");
            LedgerProbe.info("对账发出 bal");
            if (trySendBalanceReconcile()) {
                reconcileNeedBal = false;
            }
            return;
        }

        if (reconcileAt == Long.MAX_VALUE) {
            return;
        }
        if (now < reconcileAt) {
            return;
        }
        if (now > reconcileDeadline) {
            reconcileAt = Long.MAX_VALUE;
            reconcileNeedBal = false;
            if (ledgerOptimistic) {
                LedgerProbe.info("对账失败，仍显示乐观数");
            }
            BcStockLog.info("下单后对账：持仓超时放弃");
            return;
        }
        if (now - lastAnySentAt < MIN_GAP_MS) {
            // 不取消预约，下个 tick 再试
            return;
        }
        BcStockLog.info("下单后对账：发 invest portfolio（免采集冷却）");
        LedgerProbe.info("对账发出 invest portfolio");
        if (trySendPortfolioReconcile()) {
            reconcileAt = Long.MAX_VALUE;
            reconcileAwaitingPortfolioDone = true;
        } else {
            // sender 失败等：稍后重试
            reconcileAt = now + MIN_GAP_MS;
        }
    }

    /** 测试：对账是否仍挂起（持仓预约 / 等 portfolio 回执 / 待发余额）。 */
    public boolean reconcilePendingForTest() {
        return reconcileAt != Long.MAX_VALUE || reconcileNeedBal || reconcileAwaitingPortfolioDone;
    }

    private void tryComplete(boolean fromTick) {
        if (inFlightKind == null) {
            return;
        }
        long now = clock.getAsLong();

        if (inFlightKind == Kind.BAL) {
            Optional<WalletView> wallet = BalanceReply.parse(collected);
            if (wallet.isPresent()) {
                finishOk(wallet, Optional.empty(), Optional.empty());
                return;
            }
        }

        if (inFlightKind == Kind.BUY || inFlightKind == Kind.SELL) {
            completeTrade(fromTick, now);
            return;
        }

        if (hasError()) {
            // 空仓短回执带「帕拉伦股市 >」前缀，但应写成确认空仓，不能 finishEmpty 丢掉 lastHoldings
            if (inFlightKind == Kind.PORTFOLIO && collectedHasNoHoldings()) {
                finishOk(Optional.empty(), Optional.of(List.of()), Optional.empty());
                return;
            }
            finishEmpty("回执是错误前缀");
            return;
        }

        boolean settled = sawBlockEnd
                || (fromTick && sawBlockStart && now - lastLineAt >= SETTLE_MS);
        if (settled) {
            finishByParse();
            return;
        }

        long timeout = timeoutMs();
        if (fromTick && now - sentAt >= timeout) {
            lastTimedOut = true;
            finishEmpty("超时 " + timeout + "ms");
        }
    }

    private long timeoutMs() {
        return inFlightKind == Kind.COMPANIES ? COMPANIES_TIMEOUT_MS : TIMEOUT_MS;
    }

    /**
     * 块类命令只收块开始之后的行（进退服广播不要进 collected）。
     * 错误前缀没有块，始终收。
     */
    private boolean acceptLine(String line) {
        if (inFlightKind != Kind.PORTFOLIO
                && inFlightKind != Kind.COMPANY_INFO
                && inFlightKind != Kind.COMPANIES) {
            return true;
        }
        if (ReplyText.isError(line) || ReplyText.isBlockStart(line) || sawBlockStart) {
            return true;
        }
        return false;
    }

    private boolean hasError() {
        for (String line : collected) {
            if (ReplyText.isError(line) && !ReplyText.isMarketUpdated(line)) {
                return true;
            }
        }
        return false;
    }

    private boolean collectedHasNoHoldings() {
        for (String line : collected) {
            if (ReplyText.isNoHoldingsMessage(line)) {
                return true;
            }
        }
        return false;
    }

    private void completeTrade(boolean fromTick, long now) {
        // 成功文案也带「帕拉伦股市 >」，必须先走 TradeReply，不能直接 hasError()
        Optional<TradeReply.Result> parsed = TradeReply.parse(collected);
        if (parsed.isPresent()) {
            TradeReply.Result r = parsed.get();
            if (r.ok()) {
                lastTrade = Optional.of(TradeOutcome.confirmed(r.message()));
                BcStockLog.info("命令回执：买卖成功");
                LedgerProbe.info("回执 成功 {}", r.message());
                onChatOk(now);
                clearFlight();
                return;
            }
            lastTrade = Optional.of(TradeOutcome.error(r.message()));
            LedgerProbe.info("回执 失败 {}", r.message());
            onChatFail(now, r.message());
            finishEmpty("交易回执是错误前缀");
            return;
        }
        if (fromTick && !collected.isEmpty() && now - lastLineAt >= SETTLE_MS) {
            lastTrade = Optional.of(TradeOutcome.unconfirmed());
            BcStockLog.info("命令回执：买卖未确认（回执格式未匹配），不重试");
            LedgerProbe.info("回执 未确认");
            onUnconfirmed(now);
            clearFlight();
            return;
        }
        if (fromTick && now - sentAt >= TIMEOUT_MS) {
            lastTimedOut = true;
            lastTrade = Optional.of(TradeOutcome.unconfirmed());
            LedgerProbe.info("回执 超时");
            onUnconfirmed(now);
            finishEmpty("超时 " + TIMEOUT_MS + "ms");
        }
    }

    private void onChatOk(long now) {
        LedgerStore ledger = ledger();
        if (ledger != null && inFlightOrderId >= 0) {
            Double fill = (inFlightDraft == null) ? null : inFlightDraft.fillPrice();
            ledger.markChatOk(inFlightOrderId, now, fill);
        }
        if (inFlightDraft != null && snapshotStore != null) {
            LedgerApply.applyToStore(snapshotStore, inFlightDraft, Instant.ofEpochMilli(now));
            ledgerOptimistic = true;
        }
        Consumer<TradeDraft> cb = onFilled;
        if (inFlightDraft != null && cb != null) {
            cb.accept(inFlightDraft);
        }
    }

    private void onChatFail(long now, String reason) {
        LedgerStore ledger = ledger();
        if (ledger != null && inFlightOrderId >= 0) {
            ledger.markChatFail(inFlightOrderId, now, reason);
        }
    }

    private void onUnconfirmed(long now) {
        LedgerStore ledger = ledger();
        if (ledger != null && inFlightOrderId >= 0) {
            ledger.markUnconfirmed(inFlightOrderId, now);
        }
    }

    private void finishByParse() {
        switch (inFlightKind) {
            case BAL -> {
                Optional<WalletView> wallet = BalanceReply.parse(collected);
                if (wallet.isPresent()) {
                    finishOk(wallet, Optional.empty(), Optional.empty());
                } else {
                    finishEmpty("余额行对不上");
                }
            }
            case PORTFOLIO -> {
                Optional<List<HoldingView>> holdings = PortfolioReply.parse(collected);
                if (holdings.isPresent()) {
                    finishOk(Optional.empty(), holdings, Optional.empty());
                } else {
                    finishEmpty("持仓块对不上");
                }
            }
            case COMPANY_INFO -> {
                Optional<CompanyView> company = CompanyInfoReply.parse(collected, inFlightMarketId);
                if (company.isPresent()) {
                    finishOk(Optional.empty(), Optional.empty(), company);
                } else {
                    finishEmpty("单公司块对不上或 Id 错位");
                }
            }
            case COMPANIES -> {
                Optional<List<CompanyView>> companies = CompaniesReply.parse(collected);
                if (companies.isPresent() && !companies.get().isEmpty()) {
                    lastCompanies = companies;
                    BcStockLog.info("命令回执：公司列表 {} 家", companies.get().size());
                    clearFlight();
                } else {
                    finishEmpty("公司列表块对不上");
                }
            }
            case BUY, SELL -> completeTrade(true, clock.getAsLong());
        }
    }

    private void finishOk(Optional<WalletView> wallet,
                          Optional<List<HoldingView>> holdings,
                          Optional<CompanyView> company) {
        if (wallet.isPresent()) {
            lastBalance = wallet;
            BcStockLog.info("命令回执：余额 {}", String.format(java.util.Locale.ROOT, "%.2f", wallet.get().balance()));
            LedgerProbe.info("对账余额 {}", String.format(java.util.Locale.ROOT, "%.2f", wallet.get().balance()));
        }
        if (holdings.isPresent()) {
            lastHoldings = holdings;
            long held = holdings.get().stream().filter(HoldingView::held).count();
            BcStockLog.info("命令回执：持仓 {} 家（在持 {} 家）",
                    holdings.get().size(), held);
            LedgerProbe.info("对账持仓 {} 家 在持 {}", holdings.get().size(), held);
            if (reconcileAwaitingPortfolioDone) {
                reconcileAwaitingPortfolioDone = false;
                reconcileNeedBal = true;
                ledgerOptimistic = false;
                LedgerStore ledger = ledger();
                if (ledger != null) {
                    ledger.reconcileChatOk(clock.getAsLong());
                }
            }
        }
        if (company.isPresent()) {
            lastCompany = company;
            CompanyView c = company.get();
            BcStockLog.info("命令回执：公司 {} / Id={} / 价 {}", c.name(), c.marketId(), c.price());
        }
        clearFlight();
    }

    private void finishEmpty(String why) {
        Kind kind = inFlightKind;
        String cmd = inFlightCommand;
        if (kind == Kind.BAL) {
            lastBalance = Optional.empty();
        } else if (kind == Kind.PORTFOLIO) {
            lastHoldings = Optional.empty();
            if (reconcileAwaitingPortfolioDone) {
                reconcileAwaitingPortfolioDone = false;
                // 持仓块失败仍尝试刷余额
                reconcileNeedBal = true;
            }
        } else if (kind == Kind.COMPANY_INFO) {
            lastCompany = Optional.empty();
        } else if (kind == Kind.COMPANIES) {
            lastCompanies = Optional.empty();
        }
        BcStockLog.info("命令失败：{}（{}，不重试）", cmd, why);
        clearFlight();
    }

    private void clearFlight() {
        inFlightKind = null;
        inFlightCommand = null;
        inFlightMarketId = CompanyView.ID_UNKNOWN;
        inFlightOrderId = -1L;
        inFlightDraft = null;
        inFlightOrigin = OrderRecord.Origin.MANUAL;
        collected.clear();
        sawBlockStart = false;
        sawBlockEnd = false;
    }

    private LedgerStore ledger() {
        if (snapshotStore != null && snapshotStore.ledger() != null) {
            return snapshotStore.ledger();
        }
        return LedgerRuntime.store();
    }
}
