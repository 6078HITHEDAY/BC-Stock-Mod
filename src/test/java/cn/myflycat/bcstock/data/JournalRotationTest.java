package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动文件轮转：文件不许无限增长，且轮转后 {@link SnapshotJournal#last()}
 * 仍必须取到最后一条<b>完整</b>快照（增量残缺行不能当恢复源）。
 */
class JournalRotationTest {

    private static final Instant T0 = Instant.parse("2026-10-21T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-21T08:15:00Z");
    private static final Instant T2 = Instant.parse("2026-10-21T08:30:00Z");

    private static CompanyView company(String name, double price) {
        return new CompanyView(name, 56, 55, price, String.valueOf(price), 0, "",
                1.5, Double.NaN, CompanyView.STATUS_TRADING, 3, 1_000,
                CompanyView.Source.API);
    }

    private static StockSnapshot snap(Instant at, double price) {
        return StockSnapshot.empty().withCompanies(List.of(company("月港控股", price)), true, at);
    }

    @Test
    @DisplayName("★ 超过阈值时先轮转再写完整行；last() 读新文件最后一条")
    void rotatesBeforeWritingFullLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("snapshots.jsonl");
        SnapshotJournal journal = new SnapshotJournal(file.toFile(), 1L);

        journal.append(snap(T0, 42.38));
        journal.append(snap(T1, 44.21));

        Path backup = dir.resolve("snapshots.jsonl.1");
        assertTrue(Files.isRegularFile(backup), "超过阈值必须留下旧文件");
        List<String> old = Files.readAllLines(backup, StandardCharsets.UTF_8);
        assertEquals(1, old.size());
        assertTrue(old.get(0).contains("42.38"), "旧文件保存轮转前的内容");

        List<String> active = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, active.size(), "轮转后活动文件从完整行重新开始");
        assertEquals(44.21, journal.last().orElseThrow().companies().get(0).price(), 1e-9);
    }

    @Test
    @DisplayName("★ 增量行不触发轮转（避免新文件第一条就是残缺行）")
    void deltaLineDoesNotRotate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("snapshots.jsonl");
        SnapshotJournal journal = new SnapshotJournal(file.toFile(), 1L);

        StockSnapshot full = StockSnapshot.empty().withCompanies(
                List.of(company("月港控股", 42.38), company("帕拉伦联合储蓄", 61.76)), true, T0);
        journal.append(full);
        StockSnapshot next = full.withCompanies(List.of(company("月港控股", 44.21)), true, T1);
        journal.append(next, "companies", full);

        assertTrue(Files.notExists(dir.resolve("snapshots.jsonl.1")), "增量行不许触发轮转");
        assertEquals(2, Files.readAllLines(file, StandardCharsets.UTF_8).size());
        StockSnapshot back = journal.last().orElseThrow();
        assertEquals(2, back.companies().size(), "last() 仍跳过增量行，返回完整快照");
        assertEquals(42.38, back.companyOf("月港控股").price(), 1e-9);
    }

    @Test
    @DisplayName("★ 再次轮转会覆盖旧备份，只保留一份历史")
    void secondRotationReplacesBackup(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("snapshots.jsonl");
        SnapshotJournal journal = new SnapshotJournal(file.toFile(), 1L);

        journal.append(snap(T0, 1.0));
        journal.append(snap(T1, 2.0));
        journal.append(snap(T2, 3.0));

        List<String> backup = Files.readAllLines(dir.resolve("snapshots.jsonl.1"),
                StandardCharsets.UTF_8);
        assertEquals(1, backup.size());
        assertTrue(backup.get(0).contains("\"price\":2.0"), "备份只保留轮转前那一刻");
        assertEquals(3.0, journal.last().orElseThrow().companies().get(0).price(), 1e-9);
    }
}
