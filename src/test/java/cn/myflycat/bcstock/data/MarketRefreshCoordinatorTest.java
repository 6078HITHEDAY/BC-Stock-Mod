package cn.myflycat.bcstock.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRefreshCoordinatorTest {

    @Test
    @DisplayName("广播 → 发 invest companies；同广播不连发")
    void broadcastSendsCompaniesOnce() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        SnapshotStore store = new SnapshotStore();
        AtomicInteger apiHits = new AtomicInteger();
        CollectScheduler collect = new CollectScheduler(clock::get, () -> true, () -> false, g);
        MarketRefreshCoordinator c = new MarketRefreshCoordinator(
                clock::get, () -> true, g, store, () -> {
                    apiHits.incrementAndGet();
                    return false;
                }, collect);
        g.setOnMarketUpdated(c::onBroadcast);

        g.onChatLine("帕拉伦股市 > 所有商业股票已更新。");
        g.onChatLine("帕拉伦股市 > 所有商业股票已更新。");
        assertTrue(c.pendingBroadcastForTest());
        c.tick();
        assertEquals(List.of("invest companies"), sender.sent);
        assertTrue(c.companiesInFlightForTest());
        g.onChatLine("帕拉伦股市 > 所有商业股票已更新");
        c.tick();
        assertEquals(1, sender.sent.size(), "同广播不连发");
        assertEquals(0, apiHits.get(), "成功路径不打 API");
    }

    @Test
    @DisplayName("companies 失败 → 立刻 API 兜底")
    void companiesFailFallsBackToApi() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        SnapshotStore store = new SnapshotStore();
        AtomicInteger apiHits = new AtomicInteger();
        AtomicBoolean apiOk = new AtomicBoolean(true);
        CollectScheduler collect = new CollectScheduler(clock::get, () -> true, () -> false, g);
        MarketRefreshCoordinator c = new MarketRefreshCoordinator(
                clock::get, () -> true, g, store, () -> {
                    apiHits.incrementAndGet();
                    return apiOk.get();
                }, collect);

        c.onBroadcast();
        c.tick();
        assertEquals("invest companies", sender.sent.get(0));
        clock.addAndGet(CommandGateway.COMPANIES_TIMEOUT_MS);
        g.tick();
        assertTrue(g.lastTimedOut());
        c.tick();
        assertEquals(1, apiHits.get(), "失败立刻拉 API");
        assertEquals(1, c.generation());
    }

    @Test
    @DisplayName("进服成功后：19 分不兜底，超过 20 分兜底")
    void fallbackAfterTwentyMinutes() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        SnapshotStore store = new SnapshotStore();
        AtomicInteger apiHits = new AtomicInteger();
        CollectScheduler collect = new CollectScheduler(clock::get, () -> true, () -> false, g);
        MarketRefreshCoordinator c = new MarketRefreshCoordinator(
                clock::get, () -> true, g, store, () -> {
                    apiHits.incrementAndGet();
                    return true;
                }, collect);

        c.onJoinPoll();
        assertEquals(1, apiHits.get());
        assertEquals(1, c.generation());

        clock.addAndGet(19L * 60L * 1000L);
        c.tick();
        assertEquals(1, apiHits.get(), "19 分不兜底");

        clock.addAndGet(60_000L + 1);
        c.tick();
        assertEquals(2, apiHits.get(), "超过 20 分兜底");
        assertEquals(2, c.generation());
    }

    @Test
    @DisplayName("overlay 用旧价算本轮涨跌；未 overlay 的公司保留")
    void overlayComputesRoundChange() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        CompanyView oldYue = new CompanyView("月港控股", 56, 55, 42.38, "42.38", Double.NaN, "",
                1.0, 1.0, CompanyView.STATUS_TRADING, 3, 1, CompanyView.Source.API);
        CompanyView other = new CompanyView("帕拉伦联合储蓄", 10, 10, 61.76, "61.76", Double.NaN, "",
                0, 0, CompanyView.STATUS_TRADING, 1, 1, CompanyView.Source.API);
        SnapshotStore store = new SnapshotStore(
                StockSnapshot.empty().withCompanies(List.of(oldYue, other), true, Instant.EPOCH));
        CollectScheduler collect = new CollectScheduler(clock::get, () -> true, () -> false, g);
        MarketRefreshCoordinator c = new MarketRefreshCoordinator(
                clock::get, () -> true, g, store, () -> false, collect);

        c.onBroadcast();
        c.tick();
        feed(g, List.of(
                "-=-=-=-=-=-=-=-=-=-= [帕拉伦股市] =-=-=-=-=-=-=-=-=-=-",
                "月港控股",
                "",
                "状态: 交易中",
                "价格: 44.21",
                "风险等级: 3",
                "[Details]",
                "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-"));
        c.tick();

        CompanyView yue = store.get().companyOf("月港控股");
        assertEquals(44.21, yue.price(), 1e-9);
        assertEquals((44.21 - 42.38) / 42.38 * 100.0, yue.changePct(), 1e-9);
        assertEquals(2, store.get().companies().size(), "另外那家不能被首页增量删掉");
        assertEquals(61.76, store.get().companyOf("帕拉伦联合储蓄").price(), 1e-9);
        assertEquals(1, c.generation());
    }

    @Test
    @DisplayName("买单在飞时广播不发 companies")
    void companiesWaitForUserTrade() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        SnapshotStore store = new SnapshotStore();
        CollectScheduler collect = new CollectScheduler(clock::get, () -> true, () -> false, g);
        MarketRefreshCoordinator c = new MarketRefreshCoordinator(
                clock::get, () -> true, g, store, () -> false, collect);
        assertTrue(g.trySendBuy(56, 1));
        c.onBroadcast();
        c.tick();
        assertEquals(List.of("invest buy 56 1"), sender.sent);
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
