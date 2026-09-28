package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.reply.CmdSamples;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveQuoteFlowTest {

    @Test
    @DisplayName("company info 回执到了 → READY + 实时价")
    void readyOnCompanyInfo() {
        AtomicLong now = new AtomicLong(1_000_000L);
        List<String> sent = new ArrayList<>();
        CommandGateway g = new CommandGateway(now::get, cmd -> {
            sent.add(cmd);
            return true;
        });
        LiveQuoteFlow q = new LiveQuoteFlow(now::get);
        q.tick(g, 56);
        assertEquals(LiveQuoteFlow.Phase.QUOTING, q.phase());
        assertTrue(sent.contains("invest company info 56"));
        for (String line : CmdSamples.lines("company-info-56.txt")) {
            g.onChatLine(line);
        }
        q.tick(g, 56);
        assertEquals(LiveQuoteFlow.Phase.READY, q.phase());
        assertTrue(q.livePrice().isPresent());
        assertEquals(42.38, q.livePrice().orElseThrow(), 1e-9);
    }

    @Test
    @DisplayName("超时无回执 → FAILED")
    void failOnTimeout() {
        AtomicLong now = new AtomicLong(1_000_000L);
        CommandGateway g = new CommandGateway(now::get, cmd -> true);
        LiveQuoteFlow q = new LiveQuoteFlow(now::get);
        q.tick(g, 56);
        now.addAndGet(LiveQuoteFlow.GIVE_UP_MS + 1);
        q.tick(g, 56);
        assertEquals(LiveQuoteFlow.Phase.FAILED, q.phase());
        assertTrue(q.livePrice().isEmpty());
    }
}
