package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.BcStockLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 快照落盘：一行一个 JSON，<b>只追加、不重写整文件</b>。 崩在半路最多丢最后一行，历史还在。
 *
 * <p>文件路径由调用方注入（游戏里是 {@code runDirectory/bcstock/snapshots.jsonl}）。 本类不引用 {@code
 * net.minecraft}，可离线测。
 */
public final class SnapshotJournal {

  public static final String RELATIVE_DIR = "bcstock";
  public static final String FILE_NAME = "snapshots.jsonl";

  /**
   * 活动文件轮转阈值。实测（2026-10-21，真实例 {@code ~/.minecraftx/instances/bc/bcstock/snapshots.jsonl}）： 153 行 /
   * 1,539,632 字节，平均 10,062 字节/行，最大单行 14,192 字节 （2026-09-28 首采 142 行 / 1,419,232 字节，量级一致）。 取 5 MB ≈
   * 500 行 ≈ 数天游玩量：既能兜住无限增长，又保留足够回看历史。
   */
  public static final long MAX_ACTIVE_BYTES = 5_000_000L;

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private final File file;
  private final long maxBytes;

  public SnapshotJournal(File file) {
    this(file, MAX_ACTIVE_BYTES);
  }

  /**
   * @param maxBytes 活动文件轮转阈值；测试用小值，生产用 {@link #MAX_ACTIVE_BYTES}。
   */
  public SnapshotJournal(File file, long maxBytes) {
    if (file == null) {
      throw new IllegalArgumentException("journal 文件不能为空");
    }
    this.file = file;
    this.maxBytes = maxBytes > 0 ? maxBytes : MAX_ACTIVE_BYTES;
  }

  public File file() {
    return file;
  }

  /** 追加一行（无 trigger）。失败只打日志，不抛给界面线程。 */
  public void append(StockSnapshot snapshot) {
    append(snapshot, null);
  }

  /**
   * 追加一行。{@code trigger} 非空时写入 JSON（如 {@code "collect"}）， 供验收按来源计数；API / 命令普通更新继续 append-only，不删历史。
   */
  public void append(StockSnapshot snapshot, String trigger) {
    append(snapshot, trigger, null);
  }

  /**
   * 追加一行。{@code trigger} 为 {@code chat}/{@code companies} 且有上一份时， 只写价格或状态有变的公司（外加 trigger、时间），启动
   * {@link #last} 会跳过这些残缺行。
   */
  public void append(StockSnapshot snapshot, String trigger, StockSnapshot previous) {
    if (snapshot == null) {
      return;
    }
    try {
      File parent = file.getParentFile();
      if (parent != null) {
        Files.createDirectories(parent.toPath());
      }
      JournalLine line;
      if (isDeltaTrigger(trigger) && previous != null) {
        List<CompanyView> changed = changedPriceOrStatus(previous, snapshot);
        if (changed.isEmpty()) {
          return;
        }
        line = toDeltaLine(snapshot, changed, trigger);
      } else {
        line = toLine(snapshot);
        if (trigger != null && !trigger.isBlank()) {
          line.trigger = trigger;
        }
      }
      String json = GSON.toJson(line);
      rotateIfNeeded(line);
      try (FileOutputStream out = new FileOutputStream(file, true);
          OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
        writer.write(json);
        writer.write('\n');
      }
    } catch (IOException | RuntimeException e) {
      // 落盘路径不允许把任何异常抛给调用线程（可能是渲染线程 / 行情刷新线程）。
      // Gson 对 NaN / Infinity 抛 IllegalArgumentException，归这一类。
      BcStockLog.warn("快照落盘失败：{}", e.toString());
    }
  }

  static boolean isDeltaTrigger(String trigger) {
    return "chat".equals(trigger) || "companies".equals(trigger);
  }

  /**
   * 活动文件超过阈值时先轮转再写。只在<b>本次要写完整行</b>时轮转—— 这样新文件第一行一定是完整快照，{@link #last()} 不会恢复出残缺数据。
   * 增量行继续追加到旧文件，等下一次完整行再轮转（最多晚一个刷新周期）。
   *
   * <p>只保留一份旧文件（{@code snapshots.jsonl.1}），再轮转就覆盖它： 历史价值主要在最近的完整快照，旧文件只是排查备查。 注意 {@link
   * #countTrigger(String)} 只数活动文件——轮转后计数从新文件重新开始。
   */
  private void rotateIfNeeded(JournalLine line) {
    if (line.isDelta() || !file.isFile() || file.length() < maxBytes) {
      return;
    }
    File backup = new File(file.getPath() + ".1");
    try {
      Files.move(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      BcStockLog.info("快照轮转：{} → {}", file.getName(), backup.getName());
    } catch (IOException e) {
      // 轮转失败不许连累这一行：继续往旧文件追加，等下次完整行再试。
      BcStockLog.warn("快照轮转失败（继续追加）：{}", e.toString());
    }
  }

  static List<CompanyView> changedPriceOrStatus(StockSnapshot previous, StockSnapshot next) {
    List<CompanyView> out = new ArrayList<>();
    if (next == null) {
      return out;
    }
    for (CompanyView c : next.companies()) {
      if (c == null) {
        continue;
      }
      CompanyView old = previous == null ? null : previous.companyOf(c.name());
      if (old == null
          || Double.compare(old.price(), c.price()) != 0
          || !Objects.equals(old.status(), c.status())) {
        out.add(c);
      }
    }
    return out;
  }

  static JournalLine toDeltaLine(
      StockSnapshot snapshot, List<CompanyView> changed, String trigger) {
    JournalLine line = new JournalLine();
    line.at = snapshot.at().toString();
    line.apiHealthy = snapshot.apiHealthy();
    line.wallet = null;
    line.holdings = null;
    line.companies = new ArrayList<>();
    for (CompanyView c : changed) {
      line.companies.add(CompanyLine.from(c));
    }
    line.trigger = trigger;
    line.delta = Boolean.TRUE;
    return line;
  }

  /** 数文件里 {@code "trigger":"collect"} 的行（验收用，不加载全量对象）。 */
  public int countTrigger(String trigger) {
    if (trigger == null || !file.isFile()) {
      return 0;
    }
    String needle = "\"trigger\":\"" + trigger + "\"";
    int n = 0;
    try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains(needle)) {
          n++;
        }
      }
    } catch (IOException e) {
      BcStockLog.warn("快照计数失败：{}", e.toString());
    }
    return n;
  }

  /** 读最后一条<b>完整</b>非空行（跳过聊天增量残缺行）。没有文件 / 全是增量 → empty。 */
  public Optional<StockSnapshot> last() {
    if (!file.isFile()) {
      return Optional.empty();
    }
    try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
      String lastFull = null;
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JournalLine parsed;
        try {
          parsed = GSON.fromJson(line, JournalLine.class);
        } catch (Exception e) {
          continue;
        }
        if (parsed != null && !parsed.isDelta()) {
          lastFull = line;
        }
      }
      if (lastFull == null) {
        // 只有增量行 = 还没写出过完整快照，恢复会缺公司/持仓；宁可空，不给残缺数据。
        return Optional.empty();
      }
      return Optional.of(fromLine(GSON.fromJson(lastFull, JournalLine.class)));
    } catch (Exception e) {
      BcStockLog.warn("快照读取失败：{}", e.toString());
      return Optional.empty();
    }
  }

  static JournalLine toLine(StockSnapshot s) {
    JournalLine line = new JournalLine();
    line.at = s.at().toString();
    line.apiHealthy = s.apiHealthy();
    line.wallet = FiniteValues.finiteOrNull(s.wallet().balance());
    line.companies = new ArrayList<>();
    for (CompanyView c : s.companies()) {
      line.companies.add(CompanyLine.from(c));
    }
    if (s.holdingsKnown()) {
      line.holdings = new ArrayList<>();
      for (HoldingView h : s.holdingsOrEmpty()) {
        line.holdings.add(HoldingLine.from(h));
      }
    } else {
      line.holdings = null;
    }
    return line;
  }

  static StockSnapshot fromLine(JournalLine line) {
    Instant at;
    try {
      at = Instant.parse(line.at);
    } catch (Exception e) {
      at = Instant.EPOCH;
    }
    List<CompanyView> companies = new ArrayList<>();
    if (line.companies != null) {
      for (CompanyLine c : line.companies) {
        companies.add(c.toView());
      }
    }
    List<HoldingView> holdings = null;
    if (line.holdings != null) {
      holdings = new ArrayList<>();
      for (HoldingLine h : line.holdings) {
        holdings.add(h.toView());
      }
    }
    WalletView wallet =
        (line.wallet == null || !Double.isFinite(line.wallet))
            ? WalletView.UNKNOWN
            : new WalletView(line.wallet);
    return new StockSnapshot(at, companies, holdings, wallet, line.apiHealthy);
  }

  static final class JournalLine {
    String at;
    boolean apiHealthy;
    Double wallet;
    List<CompanyLine> companies;
    List<HoldingLine> holdings;

    /** 可选来源标记。采集周期写 {@code collect}；普通 API/命令更新为 null。 */
    String trigger;

    /** 聊天增量残缺行。启动恢复时跳过。 */
    Boolean delta;

    boolean isDelta() {
      return Boolean.TRUE.equals(delta) || SnapshotJournal.isDeltaTrigger(trigger);
    }
  }

  static final class CompanyLine {
    String name;
    int marketId;
    int apiId;
    double price;
    String priceRaw;
    Double marketCap;
    String marketCapRaw;
    Double changePct;
    Double totalChangePct;
    String status;
    int risk;
    long availableShares;
    String source;

    static CompanyLine from(CompanyView c) {
      CompanyLine line = new CompanyLine();
      line.name = c.name();
      line.marketId = c.marketId();
      line.apiId = c.apiId();
      line.price = FiniteValues.finiteOrZero(c.price());
      line.priceRaw = c.priceRaw();
      line.marketCap = FiniteValues.finiteOrNull(c.marketCap());
      line.marketCapRaw = c.marketCapRaw();
      line.changePct = FiniteValues.finiteOrNull(c.changePct());
      line.totalChangePct = FiniteValues.finiteOrNull(c.totalChangePct());
      line.status = c.status();
      line.risk = c.risk();
      line.availableShares = c.availableShares();
      line.source = c.source() == null ? null : c.source().name();
      return line;
    }

    CompanyView toView() {
      CompanyView.Source src;
      try {
        src = source == null ? CompanyView.Source.API : CompanyView.Source.valueOf(source);
      } catch (IllegalArgumentException e) {
        src = CompanyView.Source.API;
      }
      return new CompanyView(
          name,
          marketId,
          apiId,
          FiniteValues.finiteOrZero(price),
          priceRaw == null ? "" : priceRaw,
          FiniteValues.orNaN(marketCap),
          marketCapRaw == null ? "" : marketCapRaw,
          FiniteValues.orNaN(changePct),
          FiniteValues.orNaN(totalChangePct),
          status == null ? "" : status,
          risk,
          availableShares,
          src);
    }
  }

  static final class HoldingLine {
    String name;
    int marketId;
    String status;
    long shares;

    /** {@code null} = 未知；避免 Gson 写 NaN / Infinity 崩。 */
    Double currentValue;

    Double averageBuyPrice;

    static HoldingLine from(HoldingView h) {
      HoldingLine line = new HoldingLine();
      line.name = h.name();
      line.marketId = h.marketId();
      line.status = h.status();
      line.shares = h.shares();
      line.currentValue = FiniteValues.finiteOrNull(h.currentValue());
      line.averageBuyPrice =
          h.averageBuyPriceKnown() ? FiniteValues.finiteOrNull(h.averageBuyPrice()) : null;
      return line;
    }

    HoldingView toView() {
      return new HoldingView(
          name,
          marketId,
          status == null ? "" : status,
          shares,
          FiniteValues.orNaN(currentValue),
          FiniteValues.orNaN(averageBuyPrice));
    }
  }
}
