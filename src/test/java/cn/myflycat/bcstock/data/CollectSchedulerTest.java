package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.data.reply.CmdSamples;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectSchedulerTest {

    @AfterEach
    void reset() {
        BcStockSettings.setCollectEnabledForTest(false);
        CollectScheduler.SHARED.clearCollectTag();
    }

    @Test
    @DisplayName("★ 行情刷新后才发；只发一次 portfolio")
    void requestAfterRefreshSendsOnce() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AtomicBoolean connected = new AtomicBoolean(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        CollectScheduler sched = new CollectScheduler(clock::get, connected::get, () -> true, g);

        sched.tick();
        assertTrue(sender.sent.isEmpty(), "没有刷新预约不发");

        sched.requestAfterRefresh();
        assertEquals(List.of("invest portfolio"), sender.sent);
        feed(g, CmdSamples.lines("portfolio-held.txt"));
        clock.addAndGet(CommandGateway.MIN_GAP_MS);
        sched.requestAfterRefresh();
        assertEquals(1, sender.sent.size(), "采集冷却期内不重发");
    }

    @Test
    @DisplayName("★ 未连接不发；开关关不发")
    void offlineAndDisabled() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AtomicBoolean connected = new AtomicBoolean(false);
        AtomicBoolean enabled = new AtomicBoolean(true);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        CollectScheduler sched = new CollectScheduler(clock::get, connected::get, enabled::get, g);

        sched.requestAfterRefresh();
        assertTrue(sender.sent.isEmpty(), "未连接不发");

        connected.set(true);
        enabled.set(false);
        sched.requestAfterRefresh();
        assertTrue(sender.sent.isEmpty(), "collect.enabled=false 不发");
    }

    @Test
    @DisplayName("★ 网关冷却余量：899.9s 放行，明显不足仍拒；900.1s 放行")
    void cooldownSlackBoundary() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        assertTrue(g.trySendPortfolio());
        feed(g, CmdSamples.lines("portfolio-held.txt"));
        clock.addAndGet(CommandGateway.MIN_GAP_MS);

        clock.addAndGet(899_000L - CommandGateway.MIN_GAP_MS);
        // 距首次约 899s < 899.75s → 仍拒
        assertFalse(g.trySendPortfolio(), "899s 仍不足（含 slack）");

        clock.addAndGet(900L); // 累计约 899.9s
        assertTrue(g.trySendPortfolio(), "899.9s 不许因抖动被跳过");
        feed(g, CmdSamples.lines("portfolio-held.txt"));
        clock.addAndGet(CommandGateway.MIN_GAP_MS);
        clock.addAndGet(900_100L);
        assertTrue(g.trySendPortfolio(), "900.1s 必须放行");
    }

    @Test
    @DisplayName("★ 采集回执超时后标记过期；无关持仓写入不许标 collect")
    void collectTagExpiresNoPollute(@TempDir java.nio.file.Path dir) throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        SnapshotJournal journal = new SnapshotJournal(dir.resolve("snapshots.jsonl").toFile());
        SnapshotStore store = new SnapshotStore();
        store.setJournal(journal);

        CollectScheduler.SHARED.setClock(clock::get);
        CollectScheduler.SHARED.setConnected(() -> true);
        CollectScheduler.SHARED.setEnabled(() -> true);
        CollectScheduler.SHARED.setGateway(g);
        CollectScheduler.SHARED.clearCollectTag();

        CollectScheduler.SHARED.requestAfterRefresh();
        assertTrue(CollectScheduler.SHARED.pendingCollectTag(), "发出后标记应挂着");
        clock.addAndGet(CollectScheduler.TAG_TTL_MS + 1);
        assertFalse(CollectScheduler.SHARED.pendingCollectTag(), "超时后标记必须失效");

        RecordingSender sender2 = new RecordingSender();
        CommandGateway g2 = new CommandGateway(clock::get, sender2);
        assertTrue(g2.trySendPortfolio());
        feed(g2, CmdSamples.lines("portfolio-held.txt"));
        String trigger = CollectScheduler.SHARED.pendingCollectTag() ? "collect" : null;
        BoardOpenPull.drainToStore(g2, store, Instant.EPOCH, trigger);
        assertEquals(0, journal.countTrigger("collect"), "无关写入不许被标成 collect");
        CollectScheduler.SHARED.clearCollectTag();
        CollectScheduler.SHARED.setEnabled(() -> false);
    }

    @Test
    @DisplayName("采集落盘带 trigger=collect；API 行不计入 collect")
    void journalTriggerCollect(@TempDir java.nio.file.Path dir) throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        CollectScheduler sched = new CollectScheduler(clock::get, () -> true, () -> true, g);
        SnapshotJournal journal = new SnapshotJournal(dir.resolve("snapshots.jsonl").toFile());
        SnapshotStore store = new SnapshotStore();
        store.setJournal(journal);

        sched.requestAfterRefresh();
        assertTrue(sched.pendingCollectTag());
        feed(g, CmdSamples.lines("portfolio-held.txt"));
        BoardOpenPull.drainToStore(g, store, Instant.EPOCH, "collect");
        CollectScheduler.SHARED.clearCollectTag();
        sched.clearCollectTag();

        store.updateCompanies(List.of(new CompanyView("月港控股", 56, 55, 42, "42", Double.NaN, "",
                1, 1, CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API)), true, Instant.EPOCH);

        assertEquals(1, journal.countTrigger("collect"));
        List<String> lines = Files.readAllLines(dir.resolve("snapshots.jsonl"), StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 2, "API 更新仍 append，总行数可 > collect 行");
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"trigger\":\"collect\"")));
    }

    private static void feed(CommandGateway g, List<String> lines) {
        for (String line : lines) {
            g.onChatLine(line);
        }
        g.tick();
    }

    private static final class RecordingSender implements CommandSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String command) {
            sent.add(command);
            return true;
        }
    }
}
