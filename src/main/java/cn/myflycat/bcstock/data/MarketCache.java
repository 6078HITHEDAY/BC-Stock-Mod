package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 最新行情缓存（可覆盖重写）。与 {@link SnapshotJournal}（append-only 历史）并存，
 * 路径同目录：{@code runDirectory/bcstock/market-cache.json}。
 *
 * <p><b>钱的安全线</b>：缓存里的 {@code market_id} 一律不信。恢复进内存时必须抹成
 * {@link CompanyView#ID_UNKNOWN}，只有当次 API 刷新确认后才允许下单。
 * 断网启动 → 能看行情，但不能下单——这是正确行为。
 *
 * <p>不 import {@code net.minecraft}。
 */
public final class MarketCache {

    public static final String RELATIVE_DIR = SnapshotJournal.RELATIVE_DIR;
    public static final String FILE_NAME = "market-cache.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private MarketCache() {
    }

    public static final class Payload {
        public long savedAtMs;
        public String source = "api";
        public List<CompanyLine> companies = List.of();

        public Payload() {
        }

        public Payload(long savedAtMs, String source, List<CompanyLine> companies) {
            this.savedAtMs = savedAtMs;
            this.source = (source == null || source.isBlank()) ? "api" : source;
            this.companies = (companies == null) ? List.of() : List.copyOf(companies);
        }

        public long savedAtMs() {
            return savedAtMs;
        }

        public String source() {
            return source;
        }

        public List<CompanyLine> companies() {
            return companies == null ? List.of() : companies;
        }
    }

    /** Gson 形状；字段对外只给同包测试读 marketId。 */
    public static final class CompanyLine {
        public String name;
        public int marketId = CompanyView.ID_UNKNOWN;
        public int apiId = CompanyView.ID_UNKNOWN;
        public double price;
        public String priceRaw;
        /** null = 未知；避免 Gson 写 NaN 崩。 */
        public Double changePct;
        public String status = "";
        public int risk;
        public long availableShares = CompanyView.SHARES_UNKNOWN;

        static CompanyLine from(CompanyView c) {
            CompanyLine line = new CompanyLine();
            line.name = c.name();
            line.marketId = c.marketId();
            line.apiId = c.apiId();
            line.price = Double.isFinite(c.price()) ? c.price() : 0.0;
            line.priceRaw = c.priceRaw();
            line.changePct = Double.isFinite(c.changePct()) ? c.changePct() : null;
            line.status = c.status();
            line.risk = c.risk();
            line.availableShares = c.availableShares();
            return line;
        }

        /**
         * 恢复为视图时<strong>故意丢掉 market_id</strong>。apiId / 价格等保留用于显示与 K 线。
         */
        CompanyView toViewWipingMarketId() {
            String raw = (priceRaw == null || priceRaw.isBlank())
                    ? String.format(Locale.ROOT, "%.2f", price) : priceRaw;
            double change = (changePct == null || !Double.isFinite(changePct))
                    ? Double.NaN : changePct;
            return new CompanyView(
                    name,
                    CompanyView.ID_UNKNOWN,
                    apiId,
                    price,
                    raw,
                    Double.NaN,
                    "",
                    change,
                    Double.NaN,
                    status == null ? "" : status,
                    risk,
                    availableShares,
                    CompanyView.Source.API);
        }
    }

    public static Optional<Payload> read(File file) {
        if (file == null || !file.isFile()) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Payload p = GSON.fromJson(raw, Payload.class);
            if (p == null || p.companies().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(p);
        } catch (Exception e) {
            BcStockLog.warn("行情缓存读取失败：{}", e.toString());
            return Optional.empty();
        }
    }

    public static void write(File file, List<CompanyView> companies, long savedAtMs) {
        if (file == null || companies == null || companies.isEmpty()) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            List<CompanyLine> lines = new ArrayList<>(companies.size());
            for (CompanyView c : companies) {
                if (c != null) {
                    lines.add(CompanyLine.from(c));
                }
            }
            Payload payload = new Payload(savedAtMs, "api", lines);
            Files.writeString(file.toPath(), GSON.toJson(payload) + "\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            BcStockLog.warn("行情缓存写入失败：{}", e.toString());
        }
    }

    /**
     * 缓存 → 内存视图。每条的 {@code market_id} 都是未知——未经验证不能下单。
     */
    public static List<CompanyView> toViewsWipingMarketId(Payload payload) {
        if (payload == null) {
            return List.of();
        }
        List<CompanyView> out = new ArrayList<>(payload.companies().size());
        for (CompanyLine line : payload.companies()) {
            if (line == null || line.name == null || line.name.isBlank()) {
                continue;
            }
            out.add(line.toViewWipingMarketId());
        }
        return List.copyOf(out);
    }
}
