package cn.myflycat.bcstock.data;

import cn.myflycat.bcstock.ui.BoardFormat;
import cn.myflycat.bcstock.ui.DetailLines;
import cn.myflycat.bcstock.ui.FloorGauge;
import cn.myflycat.bcstock.ui.UiPalette;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloorCacheTest {

    @AfterEach
    void clearShared() {
        FloorCache.SHARED.clearForTest();
    }

    @Test
    @DisplayName("★ 有地板 / 没地板 / last_error：标签与颜色正确")
    void floorsKnownMissingAndError() throws Exception {
        String body = new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/floors-sample.json"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        FloorSnapshot snap = FloorSnapshot.parse(body);
        assertFalse(snap.hasError());

        FloorView para = snap.ofName("帕拉伦联合储蓄");
        assertTrue(para.known());
        assertEquals(2.9333, para.distancePct(), 1e-6);
        assertEquals("距地板: " + BoardFormat.pct(2.9333), DetailLines.floorLine(para));
        // 2.93% ≤3 且不危险 → DOWN
        assertEquals(UiPalette.DOWN,
                FloorGauge.barColorForDistance(para.distancePct(), para.inDangerZone(), 0L));

        assertNull(snap.ofName("不存在的破产公司"));
        assertEquals("距地板: " + BoardFormat.UNKNOWN, DetailLines.floorLine(null));
        assertEquals(UiPalette.MUTED, FloorGauge.barColorForDistance(Double.NaN, false, 0L));

        FloorView near = snap.ofName("月港控股");
        assertTrue(FloorGauge.dangerDistance(near.distancePct(), near.inDangerZone()));
        assertTrue(DetailLines.floorLine(near).startsWith("距地板:"));

        String errBody = new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/floors-sample-error.json"))
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        FloorSnapshot err = FloorSnapshot.parse(errBody);
        assertTrue(err.hasError());
        assertTrue(err.byName().isEmpty());
        assertEquals("距地板: " + BoardFormat.UNKNOWN, DetailLines.floorLine(err.ofName("帕拉伦联合储蓄")));
    }

    @Test
    @DisplayName("FloorCache TTL 内不重复请求")
    void ttlNoRepeat() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AtomicInteger hits = new AtomicInteger();
        ApiClient client = new ApiClient("http://example.test", p -> {
            hits.incrementAndGet();
            return new String(
                    Objects.requireNonNull(getClass().getResourceAsStream("/floors-sample.json"))
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
        });
        FloorCache cache = new FloorCache(clock::get, client);
        cache.fetchSync(client);
        assertEquals(1, hits.get());
        assertEquals(FloorCache.Status.READY, cache.status());
        cache.request();
        assertEquals(1, hits.get(), "TTL 内不得再发");
        clock.addAndGet(FloorCache.TTL_MS);
        assertEquals(FloorCache.Status.EMPTY, cache.status());
    }

    @Test
    @DisplayName("MODE 已解锁为 FLOOR")
    void modeUnlocked() {
        assertEquals(FloorGauge.Mode.FLOOR, FloorGauge.MODE);
        assertEquals("距地板", FloorGauge.label());
    }
}
