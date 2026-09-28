package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.data.reply.CmdSamples;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardOpenPullTest {

    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        long get() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    private static final class RecordingSender implements CommandSender {
        final List<String> sent = new ArrayList<>();

        @Override
        public boolean send(String command) {
            sent.add(command);
            return true;
        }
    }

    @Test
    @DisplayName("★ 开盘 → 恰好 bal 再 portfolio；15 分钟内重复开盘不重发")
    void openSendsBalThenPortfolioOnce() {
        FakeClock clock = new FakeClock();
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);

        BoardOpenPull.onOpen(g);
        assertEquals(List.of("bal"), sender.sent, "单飞：开盘瞬间只能发出余额");

        for (String line : CmdSamples.lines("bal.txt")) {
            g.onChatLine(line);
        }
        g.tick();
        assertTrue(g.lastBalance().isPresent());

        clock.advance(CommandGateway.MIN_GAP_MS);
        BoardOpenPull.tickWhileOpen(g);
        assertEquals(List.of("bal", "invest portfolio"), sender.sent, "间隔满后才发持仓");

        for (String line : CmdSamples.lines("portfolio-held.txt")) {
            g.onChatLine(line);
        }
        g.tick();

        clock.advance(CommandGateway.MIN_GAP_MS);
        BoardOpenPull.onOpen(g);
        BoardOpenPull.tickWhileOpen(g);
        assertEquals(2, sender.sent.size(), "15 分钟内重复开盘不重发");

        SnapshotStore store = new SnapshotStore();
        BoardOpenPull.drainToStore(g, store, java.time.Instant.EPOCH);
        assertEquals(45108.13, store.get().wallet().balance(), 1e-9);
        assertEquals(1, store.get().heldOnly().size());
    }

    @Test
    @DisplayName("买单在飞时开盘不抢 bal/portfolio")
    void openPullYieldsToUserTrade() {
        FakeClock clock = new FakeClock();
        RecordingSender sender = new RecordingSender();
        CommandGateway g = new CommandGateway(clock::get, sender);
        assertTrue(g.trySendBuy(56, 1));
        BoardOpenPull.onOpen(g);
        BoardOpenPull.tickWhileOpen(g);
        assertEquals(List.of("invest buy 56 1"), sender.sent);
        assertTrue(g.userTradeBusy());
    }
}
