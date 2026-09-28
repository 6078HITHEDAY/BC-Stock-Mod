package cn.myflycat.bcstock.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某个时间点的全部数据。<b>不可变</b>，界面只吃这个对象。
 *
 * <p>三条数据源各自只能给一部分事实，谁也给不全：
 * <table border="1">
 *   <caption>谁能给什么</caption>
 *   <tr><th></th><th>行情/价格</th><th>{@code market_id}</th><th>持仓/成本</th><th>余额</th></tr>
 *   <tr><td>公开 API</td><td>✓</td><td><b>只有这里有</b></td><td>✗</td><td>✗（资金线已死）</td></tr>
 *   <tr><td>聊天命令</td><td>✓ 但一次只回一屏</td><td>只有 {@code info} 单查时回显</td><td>✓ 只有这里有</td><td>✓ 只有这里有</td></tr>
 *   <tr><td>GUI 容器</td><td>✓</td><td>✗</td><td>✓</td><td>✗</td></tr>
 * </table>
 *
 * <p>所以本类<b>不提供"把两份快照揉一起"的通用 {@code merge}</b>——揉不出来。
 * 一份数据里「没有持仓」和「没问持仓」是两件事：前者要清空、后者要保留。
 * 通用合并只能在两者里瞎猜（猜错的后果是持仓凭空消失）。改成分三个<b>带类型的更新器</b>
 * （{@link #withCompanies} / {@link #withHoldings} / {@link #withWallet}），
 * 调用方说清楚这次拿到了什么，就没有歧义了。
 *
 * <p>本类不引用任何 {@code net.minecraft} 类型，可离线测。
 *
 * @param at         数据的时间点（取各来源里最晚的那个）
 * @param companies  公司行。<b>可以为部分列表</b>（命令一次只回一屏 16 家），按名字叠加
 * @param holdings   持仓。<b>{@code null} = 未知（这次没问持仓），{@code List.of()} = 确认没有持仓</b>
 * @param wallet     余额。{@link WalletView#UNKNOWN} = 未知
 * @param apiHealthy 最近一次 API 探测的结果
 */
public record StockSnapshot(
        Instant at,
        List<CompanyView> companies,
        List<HoldingView> holdings,
        WalletView wallet,
        boolean apiHealthy) {

    public StockSnapshot {
        at = (at == null) ? Instant.EPOCH : at;
        companies = (companies == null) ? List.of() : List.copyOf(companies);
        holdings = (holdings == null) ? null : List.copyOf(holdings); // null 保留，它是"未知"
        wallet = (wallet == null) ? WalletView.UNKNOWN : wallet;
    }

    /** 还什么都没拿到。 */
    public static StockSnapshot empty() {
        return new StockSnapshot(Instant.EPOCH, List.of(), null, WalletView.UNKNOWN, false);
    }

    /** 一次 API 探测失败了。显式记一笔，免得界面一直以为 API 是好的。 */
    public static StockSnapshot apiDown(Instant at) {
        return new StockSnapshot(at, List.of(), null, WalletView.UNKNOWN, false);
    }

    public boolean holdingsKnown() {
        return holdings != null;
    }

    public List<HoldingView> holdingsOrEmpty() {
        return holdings == null ? List.of() : holdings;
    }

    // ------------------------------------------------------------------
    // 查
    // ------------------------------------------------------------------

    /** 按公司名查公司行，找不到返回 {@code null}。 */
    public CompanyView companyOf(String name) {
        if (name == null) {
            return null;
        }
        for (CompanyView c : companies) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 按公司名查持仓，找不到返回 {@code null}。
     * <b>只认名字</b>——GUI 和命令里都只有名字，没有可靠编号。
     */
    public HoldingView holdingOf(String companyName) {
        if (companyName == null) {
            return null;
        }
        for (HoldingView h : holdingsOrEmpty()) {
            if (h.name().equals(companyName)) {
                return h;
            }
        }
        return null;
    }

    /** 真正在持有的那些（持股数 > 0），踢掉墓碑行。 */
    public List<HoldingView> heldOnly() {
        List<HoldingView> out = new ArrayList<>();
        for (HoldingView h : holdingsOrEmpty()) {
            if (h.held()) {
                out.add(h);
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // 更新（每个都返回新对象，旧的照旧能用——界面拿在手里的那份不会被改）
    // ------------------------------------------------------------------

    /**
     * 并入一批公司行。**按名字叠加**：同名的走 {@link CompanyView#overlay}（新的有效才覆盖），
     * 名单里没有的老公司原样留着。
     *
     * <p>所以这个更新器既能吃 API 的全市场列表，也能吃命令那 16 家的一屏，
     * 不会因为"这次只有 16 家"就把另外 38 家删掉。
     */
    public StockSnapshot withCompanies(List<CompanyView> incoming, boolean newApiHealthy, Instant newAt) {
        Map<String, CompanyView> byName = new LinkedHashMap<>();
        for (CompanyView c : companies) {
            byName.put(c.name(), c);
        }
        if (incoming != null) {
            for (CompanyView n : incoming) {
                if (n == null) {
                    continue;
                }
                CompanyView old = byName.get(n.name());
                byName.put(n.name(), old == null ? n : old.overlay(n));
            }
        }
        return new StockSnapshot(later(at, newAt), List.copyOf(byName.values()), holdings, wallet, newApiHealthy);
    }

    /**
     * 替换持仓。<b>传 {@code List.of()} 是"确认没有持仓"（会清空）；
     * 这次没问持仓就别调这个方法。</b>
     */
    public StockSnapshot withHoldings(List<HoldingView> incoming, Instant newAt) {
        return new StockSnapshot(later(at, newAt), companies, incoming, wallet, apiHealthy);
    }

    /** 替换余额。未知的 {@link WalletView#UNKNOWN} 不会把已知余额冲掉。 */
    public StockSnapshot withWallet(WalletView incoming, Instant newAt) {
        return new StockSnapshot(later(at, newAt), companies, holdings,
                (incoming != null && incoming.known()) ? incoming : wallet, apiHealthy);
    }

    // ------------------------------------------------------------------
    // ★ 运行时读编号
    // ------------------------------------------------------------------

    /**
     * 用一张「公司名 → market_id」表，把编号补进公司和持仓里。
     *
     * <p><b>这是全 mod 唯一允许给编号赋值的地方。</b>那张表只能来自当次 API
     * （见 {@code docs/commands.md} §3：{@code market_id} 会随公司增删变化，
     * 写死一律算 bug）。表里没有的公司，编号一律置为
     * {@link CompanyView#ID_UNKNOWN}——包括<b>把上一次读到、这次没读到的旧号抹掉</b>，
     * 宁可显示"编号未知、不能下单"，也不能拿着过期号去下单。
     */
    public StockSnapshot withIdsFrom(Map<String, Integer> nameToMarketId) {
        Map<String, Integer> table = (nameToMarketId == null) ? Map.of() : nameToMarketId;

        List<CompanyView> newCompanies = new ArrayList<>(companies.size());
        for (CompanyView c : companies) {
            Integer id = table.get(c.name());
            int resolved = (id == null) ? CompanyView.ID_UNKNOWN : id;
            if (resolved == c.marketId()) {
                newCompanies.add(c);
            } else {
                newCompanies.add(c.withMarketId(resolved));
            }
        }

        List<HoldingView> newHoldings = (holdings == null) ? null : new ArrayList<>(holdings.size());
        if (holdings != null) {
            for (HoldingView h : holdings) {
                Integer id = table.get(h.name());
                int resolved = (id == null) ? CompanyView.ID_UNKNOWN : id;
                newHoldings.add((resolved == h.marketId()) ? h : h.withMarketId(resolved));
            }
        }

        return new StockSnapshot(at, newCompanies, newHoldings, wallet, apiHealthy);
    }

    // ------------------------------------------------------------------

    private static Instant later(Instant a, Instant b) {
        if (b == null) {
            return a;
        }
        return b.isAfter(a) ? b : a;
    }
}
