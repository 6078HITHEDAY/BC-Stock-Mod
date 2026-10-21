package cn.myflycat.bcstock.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 非有限值（{@code NaN} / {@code ±Infinity}）不许从任何落盘路径抛出去。
 *
 * <p>事故背景：2026-09-27 22:02 线上 {@code Uncaught exception in thread "bcstock-api"}， 栈顶是 Gson 的 {@code
 * IllegalArgumentException: NaN is not a valid double value}。 当时只修了 {@link MarketCache}，{@link
 * SnapshotJournal} 的同一道防线漂移了—— 这份测试把两个落盘类的整类缺陷一次锁死。
 */
class NonFiniteJsonTest {

  private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");
  private static final double[] BAD = {
    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
  };

  private static CompanyView company(double price, double change, double marketCap) {
    return new CompanyView(
        "月港控股",
        56,
        55,
        price,
        "42.38",
        marketCap,
        "",
        change,
        Double.NaN,
        CompanyView.STATUS_TRADING,
        3,
        1_000,
        CompanyView.Source.API);
  }

  private static StockSnapshot snapshot(CompanyView c, HoldingView h, WalletView w) {
    return new StockSnapshot(T0, List.of(c), List.of(h), w, true);
  }

  @Test
  @DisplayName("★ SnapshotJournal：价格/涨跌/市值/持仓/余额为非有限值时不抛，产物不含 NaN/Infinity")
  void journalNeverWritesNonFinite(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("snapshots.jsonl");
    SnapshotJournal journal = new SnapshotJournal(f.toFile());
    for (double bad : BAD) {
      String label = "值=" + bad;
      assertDoesNotThrow(
          () ->
              journal.append(
                  snapshot(
                      company(bad, bad, bad),
                      new HoldingView("HESCO商贸", CompanyView.ID_UNKNOWN, "", 1, bad, bad),
                      new WalletView(bad))),
          "SnapshotJournal 不许抛：" + label);
    }
    String text = Files.readString(f, StandardCharsets.UTF_8);
    assertFalse(text.contains("NaN"), "落盘文本不许出现 NaN");
    assertFalse(text.contains("Infinity"), "落盘文本不许出现 Infinity");
    // 读回也不许炸；未知必须是未知，不能被当成 0 元或 0 股。
    StockSnapshot back = journal.last().orElseThrow();
    assertFalse(back.wallet().known(), "非有限余额读回必须是未知");
    assertTrue(back.holdingsKnown());
    assertFalse(Double.isFinite(back.holdings().get(0).currentValue()), "非有限当前价值读回必须是未知（NaN），不是 0");
    assertEqualsFiniteOrDefault(back.companies().get(0).price());
  }

  @Test
  @DisplayName("★ MarketCache：同一组非有限值不抛，产物不含 NaN/Infinity，恢复后编号仍被抹掉")
  void marketCacheNeverWritesNonFinite(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("market-cache.json");
    for (double bad : BAD) {
      assertDoesNotThrow(
          () -> MarketCache.write(f.toFile(), List.of(company(bad, bad, bad)), 1_000L),
          "MarketCache 不许抛：值=" + bad);
    }
    String text = Files.readString(f, StandardCharsets.UTF_8);
    assertFalse(text.contains("NaN"));
    assertFalse(text.contains("Infinity"));
    var payload = MarketCache.read(f.toFile()).orElseThrow();
    List<CompanyView> views = MarketCache.toViewsWipingMarketId(payload);
    assertFalse(views.isEmpty());
    assertFalse(views.get(0).marketIdKnown(), "缓存里的编号一律不信");
  }

  private static void assertEqualsFiniteOrDefault(double v) {
    assertTrue(Double.isFinite(v), "读回的价格必须是有限值（未知按 0 处理），实际=" + v);
    assertTrue(v >= 0.0);
  }
}
