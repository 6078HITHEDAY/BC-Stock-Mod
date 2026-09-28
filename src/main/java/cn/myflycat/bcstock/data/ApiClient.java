package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.config.ConfigRuntime;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 公开 API 客户端。JDK 自带 {@link HttpClient}，不引 OkHttp。
 *
 * <p>默认 base 是 BCquant（{@code https://tool.myflycat.cn/quant}）。
 * 地址与超时来自 {@link ConfigRuntime}（配置文件 + {@code -D} 覆盖）。
 *
 * <p>{@link #fetch()} 只打 {@code /api/companies} 一次——能拉到公司就说明源活着，
 * 不再先探 {@code /api/health}（省一半延迟）。{@link Result#health} 仍由本次结果决定。
 *
 * <p>失败一律返回 {@link Health#FAILED} / {@link Optional#empty()}，不抛给调用方、不重试。
 */
public final class ApiClient {

    public static final String DEFAULT_BASE = "https://tool.myflycat.cn/quant";
    public static final String BASE_PROPERTY = "bcstock.api.base";
    public static final String TIMEOUT_PROPERTY = "bcstock.api.timeoutMs";
    /** 默认 15s（实测 companies 1.3~9s；旧 5s 会偶发超时）。 */
    public static final int DEFAULT_TIMEOUT_MS = 15_000;

    /**
     * @deprecated 用 {@link #DEFAULT_TIMEOUT_MS}；保留名以免旧文档搜不到。
     */
    @Deprecated
    public static final Duration TIMEOUT = Duration.ofMillis(DEFAULT_TIMEOUT_MS);

    /**
     * K 线窗口长度。全量 7k+ 点每帧 Bresenham 会卡核显；
     * 500 根 ≈ 5 天 15m，够看近况。最高/最低是<strong>窗口内</strong>的。
     */
    public static final int KLINE_LIMIT = 500;

    public enum Health { OK, FAILED }

    public record Result(Health health, List<CompanyView> companies, String latestPriceAt,
                         String lastUpdateMarker) {
        public Result {
            companies = (companies == null) ? List.of() : List.copyOf(companies);
        }

        public static Result failed() {
            return new Result(Health.FAILED, List.of(), null, null);
        }

        public boolean ok() {
            return health == Health.OK;
        }
    }

    @FunctionalInterface
    interface Getter {
        String get(String path) throws Exception;
    }

    private final String base;
    private final Getter getter;
    private final Duration timeout;

    public ApiClient() {
        this(ConfigRuntime.apiBase(), Duration.ofMillis(ConfigRuntime.timeoutMs()));
    }

    public ApiClient(String base) {
        this(base, Duration.ofMillis(ConfigRuntime.timeoutMs()));
    }

    public ApiClient(String base, Duration timeout) {
        this(base, timeout, httpGetter(base, timeout));
    }

    ApiClient(String base, Getter getter) {
        this(base, Duration.ofMillis(DEFAULT_TIMEOUT_MS), getter);
    }

    ApiClient(String base, Duration timeout, Getter getter) {
        this.base = (base == null || base.isBlank()) ? DEFAULT_BASE : trimSlash(base);
        this.timeout = (timeout == null || timeout.isNegative() || timeout.isZero())
                ? Duration.ofMillis(DEFAULT_TIMEOUT_MS) : timeout;
        this.getter = getter;
    }

    public String base() {
        return base;
    }

    public Duration timeout() {
        return timeout;
    }

    /**
     * 拉全市场公司。成功 → {@link Health#OK}；失败 → {@link Health#FAILED}。
     * 只发一次 HTTP（companies），不再先打 health。
     */
    public Result fetch() {
        try {
            String companiesBody = getter.get("/api/companies");
            List<CompanyView> companies = parseCompanies(companiesBody);
            return new Result(Health.OK, companies, null, null);
        } catch (Exception e) {
            BcStockLog.warn("API 失败（不重试）：{}", e.toString());
            return Result.failed();
        }
    }

    /**
     * 拉一家公司的 K 线。参数必须是 {@link CompanyView#apiId()}，不是 marketId。
     * 走 {@code /api/history/candles}，带 {@link #KLINE_LIMIT}。失败返回 empty，不抛。
     */
    public Optional<KlineSeries> fetchKline(int apiId, String interval) {
        if (apiId < 0) {
            return Optional.empty();
        }
        String iv = (interval == null || interval.isBlank()) ? "15m" : interval;
        try {
            String path = "/api/history/candles?company_id=" + apiId
                    + "&interval=" + iv + "&limit=" + KLINE_LIMIT;
            String body = getter.get(path);
            return Optional.ofNullable(parseKline(body, apiId));
        } catch (Exception e) {
            BcStockLog.warn("K线拉取失败 apiId={}：{}", apiId, e.toString());
            return Optional.empty();
        }
    }

    /** 拉地板快照。失败 / last_error 非空 → empty。绝不调 POST refresh。 */
    public Optional<FloorSnapshot> fetchFloors() {
        try {
            String body = getter.get("/api/floors");
            return Optional.ofNullable(FloorSnapshot.parse(body));
        } catch (Exception e) {
            BcStockLog.warn("地板拉取失败（不重试）：{}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 解析 K 线 JSON。优先 {@code candles}（BCquant），没有再认 {@code data}（旧 pleasance）。
     */
    public static KlineSeries parseKline(String body, int expectedApiId) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error")) {
            return null;
        }
        int companyId = intOr(root, "company_id", expectedApiId);
        if (expectedApiId >= 0 && companyId >= 0 && companyId != expectedApiId) {
            BcStockLog.warn("K线 company_id={} 与请求 apiId={} 不一致，丢弃", companyId, expectedApiId);
            return null;
        }
        String interval = text(root, "interval");
        if (interval == null) {
            interval = "15m";
        }
        JsonArray arr = candleArray(root);
        if (arr == null) {
            return null;
        }
        double[] closes = new double[arr.size()];
        long[] times = new long[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            JsonObject bar = arr.get(i).getAsJsonObject();
            closes[i] = doubleOr(bar, "close", Double.NaN);
            times[i] = bar.has("time") && !bar.get("time").isJsonNull()
                    ? bar.get("time").getAsLong() : 0L;
            if (Double.isNaN(closes[i])) {
                return null;
            }
        }
        int id = companyId >= 0 ? companyId : expectedApiId;
        return new KlineSeries(id, interval, closes, times);
    }

    /** candles 优先，data 兜底（迁移期旧样本 / 旧镜像）。 */
    private static JsonArray candleArray(JsonObject root) {
        if (root.has("candles") && root.get("candles").isJsonArray()) {
            return root.getAsJsonArray("candles");
        }
        if (root.has("data") && root.get("data").isJsonArray()) {
            return root.getAsJsonArray("data");
        }
        return null;
    }

    /** 保留给验收 / 探测脚本；{@link #fetch()} 本身不再调用 health。 */
    static boolean healthLooksOk(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) {
                return false;
            }
            JsonObject o = el.getAsJsonObject();
            return !o.has("error");
        } catch (RuntimeException e) {
            return false;
        }
    }

    static List<CompanyView> parseCompanies(String body) {
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        List<CompanyView> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            CompanyView row = parseCompany(el.getAsJsonObject());
            if (row != null) {
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    private static CompanyView parseCompany(JsonObject o) {
        String name = text(o, "name");
        if (name == null || name.isBlank()) {
            return null;
        }
        int apiId = intOr(o, "id", CompanyView.ID_UNKNOWN);
        int marketId = intOr(o, "market_id", CompanyView.ID_UNKNOWN);
        double price = doubleOr(o, "latest_price", 0.0);
        double changePct = o.has("change_pct") && !o.get("change_pct").isJsonNull()
                ? o.get("change_pct").getAsDouble()
                : Double.NaN;
        long shares = o.has("available_shares") && !o.get("available_shares").isJsonNull()
                ? o.get("available_shares").getAsLong()
                : CompanyView.SHARES_UNKNOWN;
        int risk = intOr(o, "risk_level", 0);
        String status = text(o, "status");
        if (status == null) {
            status = "";
        }
        String priceRaw = String.format(Locale.ROOT, "%.2f", price);
        return new CompanyView(
                name, marketId, apiId, price, priceRaw,
                Double.NaN, "",
                changePct, Double.NaN,
                status, risk, shares,
                CompanyView.Source.API);
    }

    private static Getter httpGetter(String base, Duration timeout) {
        String root = trimSlash(base);
        Duration t = (timeout == null) ? Duration.ofMillis(DEFAULT_TIMEOUT_MS) : timeout;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(t)
                .build();
        return path -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(root + path))
                    .timeout(t)
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + resp.statusCode() + " " + path);
            }
            return resp.body();
        };
    }

    private static String trimSlash(String base) {
        String s = base.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String text(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        return o.get(key).getAsString();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        return o.get(key).getAsInt();
    }

    private static double doubleOr(JsonObject o, String key, double fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        return o.get(key).getAsDouble();
    }
}
