package cn.myflycat.bcstock.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 探针日志节流：只限频、不删日志；已知股市容器不节流。 */
class ProbeLogThrottleTest {

  @Test
  @DisplayName("★ 同 key 窗口内只放行一次，窗口过后再放行")
  void sameKeyThrottledForOneWindow() {
    AtomicLong clock = new AtomicLong(1_000L);
    LogThrottle throttle = new LogThrottle(15_000L, clock::get);

    assertTrue(throttle.allow("chat"));
    assertFalse(throttle.allow("chat"), "窗口内重复必须压掉");
    clock.addAndGet(14_999L);
    assertFalse(throttle.allow("chat"));
    clock.addAndGet(1L);
    assertTrue(throttle.allow("chat"), "窗口过后必须重新放行");
  }

  @Test
  @DisplayName("★ 不同 key 互不牵连")
  void differentKeysIndependent() {
    AtomicLong clock = new AtomicLong(0L);
    LogThrottle throttle = new LogThrottle(15_000L, clock::get);
    assertTrue(throttle.allow("chat"));
    assertTrue(throttle.allow("inventory"));
    assertFalse(throttle.allow("chat"));
  }

  @Test
  @DisplayName("★ 连续 100 次同因日志，只放行 1 行")
  void hundredScreensYieldOneLine() {
    AtomicLong clock = new AtomicLong(0L);
    LogThrottle throttle = new LogThrottle(15_000L, clock::get);
    int allowed = 0;
    for (int i = 0; i < 100; i++) {
      if (throttle.allow("non-container:ChatScreen")) {
        allowed++;
      }
      clock.addAndGet(100L);
    }
    assertEquals(1, allowed, "100 次同因日志只能剩 1 行（10 秒内）");
  }

  @Test
  @DisplayName("★ 探针确实接了节流：InvestProbe 用 LogThrottle 包住非股市界面日志")
  void investProbeUsesThrottle() throws Exception {
    String src =
        Files.readString(Path.of("src/main/java/cn/myflycat/bcstock/probe/InvestProbe.java"));
    assertTrue(src.contains("LogThrottle"), "InvestProbe 必须接 LogThrottle");
    assertTrue(src.contains("SCREEN_LOG.allow"), "非股市界面 / UNKNOWN 容器日志必须走节流");
    assertTrue(src.contains("unknown-container:"), "UNKNOWN 容器按标题节流");
  }
}
