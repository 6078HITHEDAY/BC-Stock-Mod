package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotJournalTest {

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-26T08:15:00Z");

    private static CompanyView yuegang(double price) {
        return new CompanyView("月港控股", 56, 55, price, price + "", 0, "",
                1.5, Double.NaN, CompanyView.STATUS_TRADING, 3, 149_889,
                CompanyView.Source.API);
    }

    @Nested
    @DisplayName("★ JSONL 只追加")
    class AppendOnly {

        @Test
        @DisplayName("★ 两次 append 之后第一行还在，文件恰好两行")
        void secondAppendDoesNotRewriteFirstLine(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("snapshots.jsonl");
            SnapshotJournal journal = new SnapshotJournal(file.toFile());

            StockSnapshot first = StockSnapshot.empty().withCompanies(List.of(yuegang(42.38)), true, T0);
            StockSnapshot second = first.withCompanies(List.of(yuegang(44.21)), true, T1);
            journal.append(first);
            journal.append(second);

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertEquals(2, lines.size(), "只追加，不许重写整文件");
            assertTrue(lines.get(0).contains("42.38"), "第一行必须还是第一次快照");
            assertTrue(lines.get(1).contains("44.21"));
            assertEquals("月港控股", journal.last().orElseThrow().companies().get(0).name());
            assertEquals(44.21, journal.last().orElseThrow().companies().get(0).price(), 1e-9);
        }

        @Test
        @DisplayName("持仓未知写成 null，确认空仓写成 []")
        void holdingsUnknownVsEmpty(@TempDir Path dir) {
            SnapshotJournal journal = new SnapshotJournal(dir.resolve("s.jsonl").toFile());
            journal.append(StockSnapshot.empty().withCompanies(List.of(yuegang(1)), true, T0));
            assertFalse(journal.last().orElseThrow().holdingsKnown());

            journal.append(StockSnapshot.empty()
                    .withCompanies(List.of(yuegang(1)), true, T1)
                    .withHoldings(List.of(), T1));
            assertTrue(journal.last().orElseThrow().holdingsKnown());
            assertTrue(journal.last().orElseThrow().holdingsOrEmpty().isEmpty());
        }

        @Test
        @DisplayName("trigger=companies 只落价格/状态有变的行；last() 跳过增量")
        void companiesDeltaOnlyChangedRows(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("snapshots.jsonl");
            SnapshotJournal journal = new SnapshotJournal(file.toFile());
            CompanyView yue = yuegang(42.38);
            CompanyView bank = new CompanyView("帕拉伦联合储蓄", 10, 10, 61.76, "61.76", 0, "",
                    0, 0, CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API);
            StockSnapshot full = StockSnapshot.empty().withCompanies(List.of(yue, bank), true, T0);
            journal.append(full);

            StockSnapshot next = full.withCompanies(List.of(yuegang(44.21)), true, T1);
            journal.append(next, "companies", full);

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            assertTrue(lines.get(1).contains("\"trigger\":\"companies\""));
            assertTrue(lines.get(1).contains("44.21"));
            assertFalse(lines.get(1).contains("帕拉伦联合储蓄"), "没变的公司不写");
            assertEquals(2, journal.last().orElseThrow().companies().size(),
                    "last() 跳过增量，仍是进服那份全表");
            assertEquals(42.38, journal.last().orElseThrow().companyOf("月港控股").price(), 1e-9);
        }
    }

    @Nested
    @DisplayName("★ 三档降级落到界面模型")
    class Degrade {

        @Test
        @DisplayName("★ NO_API 的界面模型不含 K 线元素")
        void noApiHidesKline() {
            DegradePolicy p = DegradePolicy.resolve(false, true);
            assertEquals(DegradePolicy.NO_API, p);
            assertTrue(p.klineElements().isEmpty(), "模型里就不能有 kline，不是画了再藏");
        }

        @Test
        @DisplayName("★ READ_ONLY 下单入口 enabled==false")
        void readOnlyDisablesTrade() {
            DegradePolicy p = DegradePolicy.resolve(true, false);
            assertEquals(DegradePolicy.READ_ONLY, p);
            assertFalse(p.tradeEntry().enabled(), "命令挂了不许还开着下单口");
        }

        @Test
        @DisplayName("★ 降级判据只有一个入口：命令通路问网关，不许调用点写死 true")
        void ofUsesGatewayCommandHealth() {
            StockSnapshot apiOk = StockSnapshot.empty().withCompanies(List.of(), true, T0);
            CommandGateway healthy = new CommandGateway();
            assertTrue(healthy.commandHealthy());
            assertEquals(DegradePolicy.FULL, DegradePolicy.of(apiOk, healthy));

            CommandGateway down = new CommandGateway();
            down.setCommandHealthForTest(() -> false);
            assertEquals(DegradePolicy.READ_ONLY, DegradePolicy.of(apiOk, down),
                    "API 好但命令通路挂 → READ_ONLY，这才不是死代码");
            assertEquals(DegradePolicy.READ_ONLY, DegradePolicy.of(apiOk, null));
        }

        @Test
        @DisplayName("FULL 才有 K 线元素；命令挂优先于 API 挂")
        void fullHasKlineAndCommandWins() {
            DegradePolicy full = DegradePolicy.resolve(true, true);
            assertEquals(DegradePolicy.FULL, full);
            assertEquals(List.of("kline", "available-shares"), full.klineElements());
            assertTrue(full.tradeEntry().enabled());
            assertEquals(DegradePolicy.READ_ONLY, DegradePolicy.resolve(false, false));
        }
    }
}
