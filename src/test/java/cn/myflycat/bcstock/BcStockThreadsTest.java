package cn.myflycat.bcstock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 后台线程统一入口：命名、daemon、异常兜底。 */
class BcStockThreadsTest {

  @Test
  @DisplayName("★ 工厂产出的线程是 daemon、名字正确、挂了 uncaught handler")
  void factoryThreadsAreNamedDaemonAndGuarded() throws Exception {
    ThreadFactory factory = BcStockThreads.daemonFactory("bcstock-test");
    Thread t = factory.newThread(() -> {});
    assertEquals("bcstock-test", t.getName());
    assertTrue(t.isDaemon(), "后台线程必须是 daemon，不许拖住游戏进程退出");
    assertNotNull(t.getUncaughtExceptionHandler(), "必须挂 uncaught handler");
  }

  @Test
  @DisplayName("★ uncaught handler 只记日志，绝不再抛")
  void uncaughtHandlerNeverThrows() {
    Thread t = BcStockThreads.daemon("bcstock-test", () -> {});
    assertDoesNotThrow(
        () ->
            t.getUncaughtExceptionHandler()
                .uncaughtException(t, new IllegalArgumentException("boom")));
  }

  @Test
  @DisplayName("★ 真实线程抛异常时 handler 收到的是它自己")
  void handlerReceivesThrowingThread() throws Exception {
    AtomicReference<Thread> seen = new AtomicReference<>();
    Thread t =
        BcStockThreads.daemon(
            "bcstock-test-throw",
            () -> {
              throw new IllegalStateException("boom");
            });
    t.setUncaughtExceptionHandler((thread, error) -> seen.set(thread));
    t.start();
    t.join(2_000);
    assertSame(t, seen.get(), "异常必须归到出事的那个线程");
  }

  @Test
  @DisplayName("★ 四个建线程点全部走统一工厂，源码里不许再出现裸 new Thread")
  void allThreadSitesUseFactory() throws Exception {
    java.util.List<String> sites =
        java.util.List.of(
            "src/main/java/cn/myflycat/bcstock/data/MarketPollScheduler.java",
            "src/main/java/cn/myflycat/bcstock/data/FloorCache.java",
            "src/main/java/cn/myflycat/bcstock/data/KlineCache.java",
            "src/main/java/cn/myflycat/bcstock/BcStockClient.java");
    for (String site : sites) {
      String src = java.nio.file.Files.readString(java.nio.file.Path.of(site));
      assertTrue(src.contains("BcStockThreads."), site + " 必须用 BcStockThreads");
      assertFalse(src.contains("new Thread("), site + " 不许裸建线程");
    }
  }
}
