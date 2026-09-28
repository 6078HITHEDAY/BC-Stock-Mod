package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 全 mod 唯一的快照持有者。界面线程每帧 {@link #get()}，采集线程往这里
 * {@link #updateCompanies} / {@link #updateHoldings} / {@link #updateWallet} 灌数据。
 *
 * <p>为什么要有这一层：三条数据源到得时间不一样（API 每 15 分钟、命令按需、GUI 打开才有），
 * 如果各写各的字段，界面就得同时看三个半满的对象、还要自己拼。归一到这里之后，
 * 界面任何时候拿到的都是一份<b>完整且自洽</b>的快照。
 *
 * <p>线程安全：{@link #snapshot} 是 {@code volatile}，读不加锁（界面每帧读，不能卡）；
 * 写用 {@code synchronized}（写很少）。
 *
 * <p>本类不引用任何 {@code net.minecraft} 类型。游戏外不要用 {@link #SHARED}，
 * 单测自己 {@code new} 一个。
 */
public final class SnapshotStore {

    /** 游戏里用的那一个。放在客户端入口初始化，别在游戏外碰它。 */
    public static final SnapshotStore SHARED = new SnapshotStore();

    private volatile StockSnapshot snapshot;
    private SnapshotJournal journal;
    private LedgerStore ledger;

    public SnapshotStore() {
        this(StockSnapshot.empty());
    }

    public SnapshotStore(StockSnapshot initial) {
        this.snapshot = (initial == null) ? StockSnapshot.empty() : initial;
    }

    /** 当前快照。永远非 {@code null}；还没收到数据时是 {@link StockSnapshot#empty()}。 */
    public StockSnapshot get() {
        return snapshot;
    }

    /** 挂上落盘。未挂时更新只留在内存（离线测试就是这样）。 */
    public void setJournal(SnapshotJournal journal) {
        this.journal = journal;
    }

    /** 挂上 H2 账本。未挂时更新只留在内存。 */
    public void setLedger(LedgerStore ledger) {
        this.ledger = ledger;
    }

    public LedgerStore ledger() {
        return ledger;
    }

    private void persist(StockSnapshot next) {
        persist(next, null, null);
    }

    private void persist(StockSnapshot next, String trigger) {
        persist(next, trigger, null);
    }

    private void persist(StockSnapshot next, String trigger, StockSnapshot previous) {
        SnapshotJournal j = journal;
        if (j != null) {
            j.append(next, trigger, previous);
        }
        LedgerStore led = ledger;
        if (led != null) {
            led.writeSnapshot(next, System.currentTimeMillis());
        }
    }

    /**
     * 整份替换。只给"从磁盘恢复上次快照"这类场合用；
     * 平时请走三个 {@code update*}，否则会把别的源刚拿到的字段冲掉。
     */
    public synchronized StockSnapshot set(StockSnapshot whole) {
        return set(whole, true);
    }

    /**
     * 整份替换。{@code persist=false} 用于启动时从行情缓存灌内存——
     * 那不是新观测，不应再往 {@code snapshots.jsonl} 追加一行。
     */
    public synchronized StockSnapshot set(StockSnapshot whole, boolean doPersist) {
        StockSnapshot next = (whole == null) ? StockSnapshot.empty() : whole;
        snapshot = next;
        if (doPersist) {
            persist(next);
        }
        BcStockLog.info("快照替换：公司 {} 家 / 持仓 {} / 余额 {} / 源={} / API健康={}{}",
                next.companies().size(), describeHoldings(next), describeWallet(next),
                describeSources(next.companies()), next.apiHealthy(),
                doPersist ? "" : "（仅内存，不落 JSONL）");
        return next;
    }

    /**
     * 仅把 API 健康位置为 false，<b>保留已有公司行</b>。
     * 单次刷新失败时用——有本地缓存/上次数据时不要清空盘面。
     */
    public synchronized void markApiUnhealthy() {
        StockSnapshot cur = snapshot;
        if (!cur.apiHealthy()) {
            return;
        }
        snapshot = new StockSnapshot(cur.at(), cur.companies(), cur.holdings(), cur.wallet(), false);
        BcStockLog.info("快照：API 标记不健康（保留 {} 家公司，不落盘）", cur.companies().size());
    }

    /** 并入一批公司行（API 全市场 或 命令那一屏 16 家，都行）。 */
    public synchronized StockSnapshot updateCompanies(List<CompanyView> companies,
                                                      boolean apiHealthy, Instant at) {
        return updateCompanies(companies, apiHealthy, at, null);
    }

    /**
     * 并入一批公司行。{@code trigger} 为 {@code companies}/{@code chat} 时 JSONL 只落变化行。
     */
    public synchronized StockSnapshot updateCompanies(List<CompanyView> companies,
                                                      boolean apiHealthy, Instant at,
                                                      String trigger) {
        StockSnapshot previous = snapshot;
        StockSnapshot next = snapshot.withCompanies(companies, apiHealthy, at);
        snapshot = next;
        persist(next, trigger, previous);
        BcStockLog.info("快照更新：公司 {} 家 / 持仓 {} / 余额 {} / 源={} / API健康={}{}",
                next.companies().size(), describeHoldings(next), describeWallet(next),
                describeSources(companies), next.apiHealthy(),
                (trigger == null || trigger.isBlank()) ? "" : (" / trigger=" + trigger));
        return next;
    }

    /** 替换持仓。<b>{@code List.of()} 表示"确认没有持仓"，会清空。</b> */
    public synchronized StockSnapshot updateHoldings(List<HoldingView> holdings, Instant at) {
        return updateHoldings(holdings, at, null);
    }

    /**
     * 替换持仓。{@code trigger} 非空时落盘带上来源标记（B1：{@code "collect"}），
     * 不替代其它 update 的 append-only 历史。
     */
    public synchronized StockSnapshot updateHoldings(List<HoldingView> holdings, Instant at,
                                                     String trigger) {
        StockSnapshot next = snapshot.withHoldings(holdings, at);
        snapshot = next;
        persist(next, trigger);
        BcStockLog.info("快照更新：持仓 {} / 余额 {} / 源=COMMAND{}",
                describeHoldings(next), describeWallet(next),
                (trigger == null || trigger.isBlank()) ? "" : (" / trigger=" + trigger));
        return next;
    }

    /** 替换余额。传 {@link WalletView#UNKNOWN} 不会把已知余额冲掉。 */
    public synchronized StockSnapshot updateWallet(WalletView wallet, Instant at) {
        StockSnapshot next = snapshot.withWallet(wallet, at);
        snapshot = next;
        persist(next);
        BcStockLog.info("快照更新：余额 {} / 源=COMMAND", describeWallet(next));
        return next;
    }

    /** 用当次 API 的「公司名 → market_id」表刷新编号。见 {@link StockSnapshot#withIdsFrom}。 */
    public synchronized StockSnapshot updateIds(java.util.Map<String, Integer> nameToMarketId, Instant at) {
        StockSnapshot before = snapshot;
        StockSnapshot next = before.withIdsFrom(nameToMarketId);
        snapshot = next;
        persist(next);
        int unknown = 0;
        for (CompanyView c : next.companies()) {
            if (!c.marketIdKnown()) {
                unknown++;
            }
        }
        BcStockLog.info("快照更新：编号来自当次 API，表 {} 项；公司 {} 家里仍有 {} 家编号未知（这 {} 家不能下单）",
                (nameToMarketId == null ? 0 : nameToMarketId.size()),
                next.companies().size(), unknown, unknown);
        return next;
    }

    // ------------------------------------------------------------------

    private static String describeHoldings(StockSnapshot s) {
        if (!s.holdingsKnown()) {
            return "未知";
        }
        return s.holdingsOrEmpty().size() + " 家（在持 " + s.heldOnly().size() + " 家）";
    }

    private static String describeWallet(StockSnapshot s) {
        return s.wallet().known() ? String.format(Locale.ROOT, "%.2f", s.wallet().balance()) : "未知";
    }

    private static String describeSources(List<CompanyView> companies) {
        if (companies == null || companies.isEmpty()) {
            return "-";
        }
        TreeSet<String> names = new TreeSet<>();
        for (CompanyView c : companies) {
            if (c != null && c.source() != null) {
                names.add(Objects.requireNonNull(c.source()).name());
            }
        }
        return names.isEmpty() ? "-" : String.join("+", names);
    }
}
