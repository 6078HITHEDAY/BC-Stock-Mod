package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.ui.TradeDraft;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 按玩家 UUID 隔离的 H2 文件库。路径
 * {@code runDirectory/bcstock/<uuid>/store}（H2 会写成 {@code store.mv.db}）。
 *
 * <p>不引用 {@code net.minecraft}。失败只打日志，不抛给界面线程。
 */
public final class LedgerStore implements AutoCloseable {

    public static final int SCHEMA_VERSION = 1;
    public static final String RELATIVE_DIR = SnapshotJournal.RELATIVE_DIR;
    public static final String FILE_NAME = "store";

    private final File file;
    private Connection conn;

    private LedgerStore(File file, Connection conn) {
        this.file = file;
        this.conn = conn;
    }

    public static File filePath(File runDirectory, String playerUuid) {
        File root = (runDirectory == null) ? new File(".") : runDirectory;
        String id = (playerUuid == null || playerUuid.isBlank()) ? "unknown" : playerUuid.trim();
        return new File(new File(new File(root, RELATIVE_DIR), id), FILE_NAME);
    }

    public static LedgerStore openFile(File storeFile) {
        if (storeFile == null) {
            throw new IllegalArgumentException("store 路径不能为空");
        }
        File parent = storeFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            BcStockLog.warn("无法创建账本目录 {}", parent.getAbsolutePath());
        }
        String path = storeFile.getAbsoluteFile().toPath().toAbsolutePath().toString()
                .replace('\\', '/');
        String url = "jdbc:h2:file:" + path + ";MODE=REGULAR;AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=TRUE";
        return open(url, storeFile);
    }

    public static LedgerStore openMemory(String name) {
        String db = (name == null || name.isBlank()) ? "bcstock_mem" : name;
        String url = "jdbc:h2:mem:" + db + ";MODE=REGULAR;DB_CLOSE_DELAY=-1";
        return open(url, new File(db));
    }

    private static LedgerStore open(String url, File file) {
        try {
            Connection conn = DriverManager.getConnection(url, "sa", "");
            conn.setAutoCommit(true);
            LedgerStore store = new LedgerStore(file, conn);
            store.migrate();
            return store;
        } catch (SQLException e) {
            BcStockLog.warn("打开账本失败：{}", e.toString());
            throw new IllegalStateException("打开账本失败", e);
        }
    }

    public File file() {
        return file;
    }

    public synchronized int schemaVersion() {
        return intMeta("schema_version", 0);
    }

    public synchronized boolean isFresh() {
        return count("companies") == 0
                && count("holdings") == 0
                && count("orders") == 0
                && !walletKnown();
    }

    public synchronized void setPlayerUuid(String uuid) {
        putMeta("player_uuid", uuid == null ? "" : uuid);
    }

    public synchronized String playerUuid() {
        return meta("player_uuid").orElse("");
    }

    public synchronized Optional<Long> firstPluginSeenAt() {
        return longMeta("first_plugin_seen_at");
    }

    public synchronized void notePluginSeen(long nowMs) {
        if (firstPluginSeenAt().isEmpty()) {
            putMeta("first_plugin_seen_at", Long.toString(nowMs));
        }
    }

    public synchronized void setLastAutoSentAt(long nowMs) {
        putMeta("last_auto_sent_at", Long.toString(nowMs));
    }

    public synchronized long lastAutoSentAt() {
        return longMeta("last_auto_sent_at").orElse(0L);
    }

    /**
     * 把整份快照写入库（公司全量替换；持仓已知才替换；余额已知才覆盖）。
     */
    public synchronized void writeSnapshot(StockSnapshot snap, long nowMs) {
        if (snap == null || conn == null) {
            return;
        }
        replaceCompanies(snap.companies(), nowMs);
        if (snap.holdingsKnown()) {
            replaceHoldings(snap.holdingsOrEmpty(), nowMs);
        }
        if (snap.wallet().known()) {
            writeWallet(snap.wallet(), nowMs);
        }
        writeSnapshotMeta(snap, nowMs);
        if (!snap.companies().isEmpty() || snap.holdingsKnown()) {
            notePluginSeen(nowMs);
        }
    }

    /**
     * 读出快照。公司行的 {@code market_id} 抹成未知——未经验证不能下单。
     */
    public synchronized StockSnapshot loadSnapshotWipingMarketId() {
        List<CompanyView> companies = loadCompanies(true);
        boolean holdingsKnown = booleanMeta("holdings_known", false);
        List<HoldingView> holdings = holdingsKnown ? loadHoldings(true) : null;
        WalletView wallet = loadWallet();
        Instant at = Instant.ofEpochMilli(Math.max(0L, longMeta("snapshot_at").orElse(0L)));
        boolean apiHealthy = booleanMeta("api_healthy", false);
        return new StockSnapshot(at, companies, holdings, wallet, apiHealthy);
    }

    public synchronized void writeSnapshotMeta(StockSnapshot snap, long nowMs) {
        if (snap == null) {
            return;
        }
        putMeta("snapshot_at", Long.toString(snap.at() == null ? nowMs : snap.at().toEpochMilli()));
        putMeta("api_healthy", Boolean.toString(snap.apiHealthy()));
        putMeta("holdings_known", Boolean.toString(snap.holdingsKnown()));
    }

    public synchronized long insertPending(TradeDraft.Side side, int marketId, String name,
                                           int qty, OrderRecord.Origin origin, long nowMs) {
        if (conn == null) {
            return -1L;
        }
        String sql = """
                INSERT INTO orders (side, market_id, company_name, qty, origin, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, side == null ? TradeDraft.Side.BUY.name() : side.name());
            ps.setInt(2, marketId);
            ps.setString(3, name == null ? "" : name);
            ps.setInt(4, qty);
            ps.setString(5, (origin == null ? OrderRecord.Origin.MANUAL : origin).name());
            ps.setString(6, OrderRecord.Status.PENDING.name());
            ps.setLong(7, nowMs);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            BcStockLog.warn("写入待确认单失败：{}", e.toString());
        }
        return -1L;
    }

    public synchronized void markSent(long orderId, long nowMs) {
        updateStatus(orderId, OrderRecord.Status.SENT, nowMs, null, null, null);
        setLongCol(orderId, "sent_at", nowMs);
    }

    public synchronized void markChatOk(long orderId, long nowMs, Double fillPrice) {
        updateStatus(orderId, OrderRecord.Status.CHAT_OK, nowMs, nowMs, fillPrice, "");
    }

    public synchronized void markChatFail(long orderId, long nowMs, String reason) {
        updateStatus(orderId, OrderRecord.Status.CHAT_FAIL, nowMs, nowMs, null, reason);
    }

    public synchronized void markUnconfirmed(long orderId, long nowMs) {
        updateStatus(orderId, OrderRecord.Status.UNCONFIRMED, nowMs, nowMs, null, "未确认");
    }

    public synchronized void markCancelled(long orderId, String reason) {
        updateStatus(orderId, OrderRecord.Status.CANCELLED, 0L, null, null,
                reason == null ? "取消" : reason);
    }

    public synchronized void markReconciled(long orderId, long nowMs) {
        updateStatus(orderId, OrderRecord.Status.RECONCILED, nowMs, null, null, "");
    }

    /** 重进：所有 SENT 标成 UNCONFIRMED，不重发。 */
    public synchronized int markAllSentUnconfirmed(long nowMs) {
        if (conn == null) {
            return 0;
        }
        String sql = "UPDATE orders SET status=?, filled_at=?, fail_reason=? WHERE status=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, OrderRecord.Status.UNCONFIRMED.name());
            ps.setLong(2, nowMs);
            ps.setString(3, "重进未确认");
            ps.setString(4, OrderRecord.Status.SENT.name());
            return ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("SENT→UNCONFIRMED 失败：{}", e.toString());
            return 0;
        }
    }

    /** 初始化拿到持仓/余额后，把 UNCONFIRMED 收口为 RECONCILED。 */
    public synchronized int reconcileUnconfirmed(long nowMs) {
        if (conn == null) {
            return 0;
        }
        String sql = "UPDATE orders SET status=? WHERE status=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, OrderRecord.Status.RECONCILED.name());
            ps.setString(2, OrderRecord.Status.UNCONFIRMED.name());
            return ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("UNCONFIRMED 收口失败：{}", e.toString());
            return 0;
        }
    }

    /** 对账成功：CHAT_OK → RECONCILED。 */
    public synchronized int reconcileChatOk(long nowMs) {
        if (conn == null) {
            return 0;
        }
        String sql = "UPDATE orders SET status=? WHERE status=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, OrderRecord.Status.RECONCILED.name());
            ps.setString(2, OrderRecord.Status.CHAT_OK.name());
            return ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("CHAT_OK 收口失败：{}", e.toString());
            return 0;
        }
    }

    public synchronized Optional<OrderRecord> find(long orderId) {
        if (conn == null || orderId < 0) {
            return Optional.empty();
        }
        String sql = """
                SELECT id, side, market_id, company_name, qty, origin, status,
                       created_at, sent_at, filled_at, fill_price, fail_reason
                FROM orders WHERE id=?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(fromRow(rs));
                }
            }
        } catch (SQLException e) {
            BcStockLog.warn("读订单失败：{}", e.toString());
        }
        return Optional.empty();
    }

    public synchronized List<OrderRecord> listAll() {
        List<OrderRecord> out = new ArrayList<>();
        if (conn == null) {
            return out;
        }
        String sql = """
                SELECT id, side, market_id, company_name, qty, origin, status,
                       created_at, sent_at, filled_at, fill_price, fail_reason
                FROM orders ORDER BY id
                """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            BcStockLog.warn("列订单失败：{}", e.toString());
        }
        return out;
    }

    /** 当日已成交股数（CHAT_OK + RECONCILED），按本机时区自然日。 */
    public synchronized int filledQtyOnDay(long nowMs) {
        if (conn == null) {
            return 0;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        long start = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        String sql = """
                SELECT COALESCE(SUM(qty), 0) FROM orders
                WHERE status IN (?, ?)
                  AND filled_at IS NOT NULL
                  AND filled_at >= ? AND filled_at < ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, OrderRecord.Status.CHAT_OK.name());
            ps.setString(2, OrderRecord.Status.RECONCILED.name());
            ps.setLong(3, start);
            ps.setLong(4, end);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            BcStockLog.warn("统计当日成交失败：{}", e.toString());
        }
        return 0;
    }

    @Override
    public synchronized void close() {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            BcStockLog.warn("关闭账本失败：{}", e.toString());
        } finally {
            conn = null;
        }
    }

    // ------------------------------------------------------------------

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS meta (
                      k VARCHAR(64) NOT NULL PRIMARY KEY,
                      v VARCHAR(1024)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS companies (
                      name VARCHAR(128) NOT NULL PRIMARY KEY,
                      market_id INT NOT NULL,
                      api_id INT NOT NULL,
                      price DOUBLE,
                      price_raw VARCHAR(64),
                      market_cap DOUBLE,
                      market_cap_raw VARCHAR(64),
                      change_pct DOUBLE,
                      total_change_pct DOUBLE,
                      status VARCHAR(64),
                      risk INT,
                      available_shares BIGINT,
                      source VARCHAR(32),
                      updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS holdings (
                      name VARCHAR(128) NOT NULL PRIMARY KEY,
                      market_id INT NOT NULL,
                      status VARCHAR(64),
                      shares BIGINT NOT NULL,
                      current_value DOUBLE,
                      average_buy_price DOUBLE,
                      updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS wallet (
                      id INT NOT NULL PRIMARY KEY,
                      known BOOLEAN NOT NULL,
                      balance DOUBLE,
                      updated_at BIGINT NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                      id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      side VARCHAR(8) NOT NULL,
                      market_id INT NOT NULL,
                      company_name VARCHAR(128),
                      qty INT NOT NULL,
                      origin VARCHAR(16) NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      created_at BIGINT NOT NULL,
                      sent_at BIGINT,
                      filled_at BIGINT,
                      fill_price DOUBLE,
                      fail_reason VARCHAR(512)
                    )
                    """);
        }
        int ver = intMeta("schema_version", 0);
        if (ver < SCHEMA_VERSION) {
            putMeta("schema_version", Integer.toString(SCHEMA_VERSION));
        }
    }

    private void replaceCompanies(List<CompanyView> companies, long nowMs) {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM companies");
        } catch (SQLException e) {
            BcStockLog.warn("清空公司表失败：{}", e.toString());
            return;
        }
        if (companies == null || companies.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO companies (name, market_id, api_id, price, price_raw, market_cap,
                  market_cap_raw, change_pct, total_change_pct, status, risk, available_shares,
                  source, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (CompanyView c : companies) {
                if (c == null) {
                    continue;
                }
                ps.setString(1, c.name());
                ps.setInt(2, c.marketId());
                ps.setInt(3, c.apiId());
                setDouble(ps, 4, c.price());
                ps.setString(5, c.priceRaw());
                setDouble(ps, 6, c.marketCap());
                ps.setString(7, c.marketCapRaw());
                setDouble(ps, 8, c.changePct());
                setDouble(ps, 9, c.totalChangePct());
                ps.setString(10, c.status());
                ps.setInt(11, c.risk());
                ps.setLong(12, c.availableShares());
                ps.setString(13, c.source() == null ? CompanyView.Source.API.name() : c.source().name());
                ps.setLong(14, nowMs);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            BcStockLog.warn("写入公司失败：{}", e.toString());
        }
        putMeta("snapshot_at", Long.toString(nowMs));
    }

    private void replaceHoldings(List<HoldingView> holdings, long nowMs) {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM holdings");
        } catch (SQLException e) {
            BcStockLog.warn("清空持仓表失败：{}", e.toString());
            return;
        }
        putMeta("holdings_known", "true");
        if (holdings == null) {
            return;
        }
        String sql = """
                INSERT INTO holdings (name, market_id, status, shares, current_value,
                  average_buy_price, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (HoldingView h : holdings) {
                if (h == null) {
                    continue;
                }
                ps.setString(1, h.name());
                ps.setInt(2, h.marketId());
                ps.setString(3, h.status());
                ps.setLong(4, h.shares());
                setDouble(ps, 5, h.currentValue());
                setDouble(ps, 6, h.averageBuyPrice());
                ps.setLong(7, nowMs);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            BcStockLog.warn("写入持仓失败：{}", e.toString());
        }
    }

    private void writeWallet(WalletView wallet, long nowMs) {
        String sql = """
                MERGE INTO wallet (id, known, balance, updated_at) KEY (id)
                VALUES (1, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, wallet != null && wallet.known());
            setDouble(ps, 2, wallet == null ? Double.NaN : wallet.balance());
            ps.setLong(3, nowMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("写入余额失败：{}", e.toString());
        }
    }

    private List<CompanyView> loadCompanies(boolean wipeMarketId) {
        List<CompanyView> out = new ArrayList<>();
        String sql = """
                SELECT name, market_id, api_id, price, price_raw, market_cap, market_cap_raw,
                       change_pct, total_change_pct, status, risk, available_shares, source
                FROM companies
                """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                int marketId = wipeMarketId ? CompanyView.ID_UNKNOWN : rs.getInt("market_id");
                CompanyView.Source src;
                try {
                    src = CompanyView.Source.valueOf(rs.getString("source"));
                } catch (Exception e) {
                    src = CompanyView.Source.API;
                }
                out.add(new CompanyView(
                        name,
                        marketId,
                        rs.getInt("api_id"),
                        getDouble(rs, "price"),
                        nvl(rs.getString("price_raw")),
                        getDouble(rs, "market_cap"),
                        nvl(rs.getString("market_cap_raw")),
                        getDouble(rs, "change_pct"),
                        getDouble(rs, "total_change_pct"),
                        nvl(rs.getString("status")),
                        rs.getInt("risk"),
                        rs.getLong("available_shares"),
                        src));
            }
        } catch (SQLException e) {
            BcStockLog.warn("读公司失败：{}", e.toString());
        }
        return out;
    }

    private List<HoldingView> loadHoldings(boolean wipeMarketId) {
        List<HoldingView> out = new ArrayList<>();
        String sql = "SELECT name, market_id, status, shares, current_value, average_buy_price FROM holdings";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                int marketId = wipeMarketId ? CompanyView.ID_UNKNOWN : rs.getInt("market_id");
                out.add(new HoldingView(
                        name,
                        marketId,
                        nvl(rs.getString("status")),
                        rs.getLong("shares"),
                        getDouble(rs, "current_value"),
                        getDouble(rs, "average_buy_price")));
            }
        } catch (SQLException e) {
            BcStockLog.warn("读持仓失败：{}", e.toString());
        }
        return out;
    }

    private WalletView loadWallet() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT known, balance FROM wallet WHERE id=1")) {
            if (rs.next()) {
                if (!rs.getBoolean("known")) {
                    return WalletView.UNKNOWN;
                }
                double bal = getDouble(rs, "balance");
                return Double.isNaN(bal) ? WalletView.UNKNOWN : new WalletView(bal);
            }
        } catch (SQLException e) {
            BcStockLog.warn("读余额失败：{}", e.toString());
        }
        return WalletView.UNKNOWN;
    }

    private boolean walletKnown() {
        return loadWallet().known();
    }

    private void updateStatus(long orderId, OrderRecord.Status status, long nowMs,
                              Long filledAt, Double fillPrice, String reason) {
        if (conn == null || orderId < 0 || status == null) {
            return;
        }
        String sql = "UPDATE orders SET status=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("更新订单状态失败：{}", e.toString());
            return;
        }
        if (filledAt != null) {
            setLongCol(orderId, "filled_at", filledAt);
        }
        if (fillPrice != null && Double.isFinite(fillPrice)) {
            setDoubleCol(orderId, "fill_price", fillPrice);
        }
        if (reason != null) {
            setStringCol(orderId, "fail_reason", reason);
        }
    }

    private void setLongCol(long orderId, String col, long value) {
        String sql = "UPDATE orders SET " + col + "=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("更新订单 {} 失败：{}", col, e.toString());
        }
    }

    private void setDoubleCol(long orderId, String col, double value) {
        String sql = "UPDATE orders SET " + col + "=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, value);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("更新订单 {} 失败：{}", col, e.toString());
        }
    }

    private void setStringCol(long orderId, String col, String value) {
        String sql = "UPDATE orders SET " + col + "=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("更新订单 {} 失败：{}", col, e.toString());
        }
    }

    private OrderRecord fromRow(ResultSet rs) throws SQLException {
        TradeDraft.Side side;
        try {
            side = TradeDraft.Side.valueOf(rs.getString("side"));
        } catch (Exception e) {
            side = TradeDraft.Side.BUY;
        }
        OrderRecord.Origin origin;
        try {
            origin = OrderRecord.Origin.valueOf(rs.getString("origin"));
        } catch (Exception e) {
            origin = OrderRecord.Origin.MANUAL;
        }
        OrderRecord.Status status;
        try {
            status = OrderRecord.Status.valueOf(rs.getString("status"));
        } catch (Exception e) {
            status = OrderRecord.Status.PENDING;
        }
        long sentAt = rs.getLong("sent_at");
        boolean sentNull = rs.wasNull();
        long filledAt = rs.getLong("filled_at");
        boolean filledNull = rs.wasNull();
        double fill = rs.getDouble("fill_price");
        boolean fillNull = rs.wasNull();
        return new OrderRecord(
                rs.getLong("id"),
                side,
                rs.getInt("market_id"),
                nvl(rs.getString("company_name")),
                rs.getInt("qty"),
                origin,
                status,
                rs.getLong("created_at"),
                sentNull ? null : sentAt,
                filledNull ? null : filledAt,
                fillNull ? null : fill,
                nvl(rs.getString("fail_reason")));
    }

    private int count(String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            BcStockLog.warn("计数 {} 失败：{}", table, e.toString());
        }
        return 0;
    }

    private Optional<String> meta(String key) {
        if (conn == null || key == null) {
            return Optional.empty();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    return v == null ? Optional.empty() : Optional.of(v);
                }
            }
        } catch (SQLException e) {
            BcStockLog.warn("读 meta {} 失败：{}", key, e.toString());
        }
        return Optional.empty();
    }

    private void putMeta(String key, String value) {
        if (conn == null || key == null) {
            return;
        }
        String sql = "MERGE INTO meta (k, v) KEY (k) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            BcStockLog.warn("写 meta {} 失败：{}", key, e.toString());
        }
    }

    private int intMeta(String key, int def) {
        try {
            return Integer.parseInt(meta(key).orElse(Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Optional<Long> longMeta(String key) {
        return meta(key).flatMap(v -> {
            try {
                return Optional.of(Long.parseLong(v.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private boolean booleanMeta(String key, boolean def) {
        return meta(key).map(v -> Boolean.parseBoolean(v.trim())).orElse(def);
    }

    private static void setDouble(PreparedStatement ps, int idx, double value) throws SQLException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            ps.setNull(idx, Types.DOUBLE);
        } else {
            ps.setDouble(idx, value);
        }
    }

    private static double getDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? Double.NaN : v;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
