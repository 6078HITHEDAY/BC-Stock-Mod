package cn.myflycat.bcstock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 涨跌归并的边界：广播增量用上一口价算本轮涨跌。 */
class CompanyViewTest {

  private static CompanyView at(double price, double changePct) {
    return new CompanyView(
        "月港控股",
        56,
        55,
        price,
        String.valueOf(price),
        Double.NaN,
        "",
        changePct,
        Double.NaN,
        CompanyView.STATUS_TRADING,
        3,
        1_000,
        CompanyView.Source.API);
  }

  @Test
  @DisplayName("★ 正常路径：(新-旧)/旧×100")
  void roundChangeFromPreviousPrice() {
    CompanyView now = at(44.0, Double.NaN);
    CompanyView back = now.withRoundChangeFrom(at(40.0, 0));
    assertEquals(10.0, back.changePct(), 1e-9);
  }

  @Test
  @DisplayName("★ 旧价未知/为 0/负、新价非有限 → 保持原涨跌，不许算出 Infinity")
  void roundChangeKeepsOriginalOnBadInputs() {
    CompanyView now = at(44.0, Double.NaN);
    assertSame(now, now.withRoundChangeFrom(null));
    assertSame(now, now.withRoundChangeFrom(at(0.0, 0)));
    assertSame(now, now.withRoundChangeFrom(at(-1.0, 0)));

    CompanyView nan = at(Double.NaN, 1.5);
    assertSame(nan, nan.withRoundChangeFrom(at(40.0, 0)));

    CompanyView inf = at(Double.POSITIVE_INFINITY, 1.5);
    assertSame(inf, inf.withRoundChangeFrom(at(40.0, 0)), "非有限新价不许参与涨跌计算");
  }

  @Test
  @DisplayName("★ overlay：新涨跌未知（NaN）时保留旧值")
  void overlayKeepsOldChangeWhenNewUnknown() {
    CompanyView old = at(40.0, 2.5);
    CompanyView merged = old.overlay(at(44.0, Double.NaN));
    assertEquals(44.0, merged.price(), 1e-9, "价格用新的");
    assertEquals(2.5, merged.changePct(), 1e-9, "涨跌保持旧的");
    assertTrue(merged.marketIdKnown());
  }
}
