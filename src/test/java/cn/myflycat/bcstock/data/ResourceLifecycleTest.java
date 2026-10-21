package cn.myflycat.bcstock.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 后台线程池生命周期：断线可收口，重进服可懒重建，且不会把「在飞」状态留成死锁。 三个池（market-poll / floors / kline）覆盖。 */
class ResourceLifecycleTest {

  private static final long WAIT_MS = 3_000L;

  private static ApiClient fakeClient(AtomicLong clock, CountDownLatch latch) {
    return new ApiClient(
        "http://example.test",
        path -> {
          if (latch != null) {
            latch.countDown();
          }
          if (path.contains("/companies")) {
            return "[{\"id\":10,\"market_id\":7,\"name\":\"帕拉伦联合储蓄\","
                + "\"risk_level\":1,\"status\":\"交易中\",\"latest_price\":61.76}]";
          }
          if (path.contains("/floors")) {
            return "{\"interval\":\"15m\",\"floors\":[{\"company_id\":10,"
                + "\"company_name\":\"帕拉伦联合储蓄\",\"risk_level\":1,"
                + "\"current\":61.76,\"floor\":60.0,\"danger_zone\":60.4,"
                + "\"distance_pct\":2.9333,\"status\":\"floor_confirmed_low\"}],"
                + "\"last_error\":null,\"poll_interval\":300}";
          }
          if (path.contains("/candles")) {
            return "{\"company_id\":10,\"interval\":\"15m\",\"candles\":["
                + "{\"time\":1000,\"open\":60.0,\"high\":60.0,\"low\":60.0,\"close\":61.0}]}";
          }
          throw new IllegalStateException("unexpected " + path);
        });
  }

  @Test
  @DisplayName("★ market-poll：shutdown 后仍能再跑异步刷新")
  void marketPollSurvivesShutdown() throws Exception {
    AtomicLong clock = new AtomicLong(1_000_000L);
    ApiClient client = fakeClient(clock, null);
    MarketPollScheduler sched =
        new MarketPollScheduler(
            clock::get, () -> client, new SnapshotStore(), new FloorCache(clock::get, client));

    sched.shutdown(); // 还没建池，直接收口不许炸
    CountDownLatch done = new CountDownLatch(1);
    assertTrue(sched.pollOnceAsync(ok -> done.countDown()), "shutdown 后必须能懒重建并开新任务");
    assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS), "异步刷新必须完成");
    sched.shutdown();
  }

  @Test
  @DisplayName("★ floors：shutdown 清掉 LOADING，重进服 request 不被死锁")
  void floorCacheReleasesLoadingOnShutdown() throws Exception {
    AtomicLong clock = new AtomicLong(1_000_000L);
    CountDownLatch fetched = new CountDownLatch(1);
    ApiClient slowish = fakeClient(clock, fetched);
    FloorCache cache = new FloorCache(clock::get, slowish);

    cache.request();
    assertTrue(fetched.await(WAIT_MS, TimeUnit.MILLISECONDS));
    awaitDone(cache);
    cache.shutdown();

    // 模拟时间推进到 TTL 之后，重进服再请求：必须还能进 LOADING 并完成。
    clock.addAndGet(FloorCache.TTL_MS + 1);
    CountDownLatch again = new CountDownLatch(1);
    ApiClient client2 = fakeClient(clock, again);
    cache.setClient(client2);
    cache.request();
    assertTrue(again.await(WAIT_MS, TimeUnit.MILLISECONDS), "shutdown 后 request 必须还能发出去");
    awaitDone(cache);
    cache.shutdown();
  }

  @Test
  @DisplayName("★ kline：shutdown 清掉排队中的 LOADING，重进服还能再请求")
  void klineCacheClearsStuckLoading() throws Exception {
    AtomicLong clock = new AtomicLong(1_000_000L);
    ApiClient client = fakeClient(clock, null);
    KlineCache cache = new KlineCache(clock::get, client);

    cache.request(10);
    awaitReady(cache, 10);
    cache.shutdown();
    assertEquals(KlineCache.Status.READY, cache.status(10), "已就绪的 K 线不受断线影响");

    // 时间推进到 TTL 之后再次请求，shutdown 之后必须能重新走一轮。
    clock.addAndGet(KlineCache.TTL_MS + 1);
    cache.request(10);
    awaitReady(cache, 10);
    cache.shutdown();
    assertTrue(cache.get(10).isPresent());
  }

  private static void awaitReady(KlineCache cache, int apiId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (cache.status(apiId) == KlineCache.Status.READY) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("K 线未在时限内就绪，status=" + cache.status(apiId));
  }

  private static void awaitDone(FloorCache cache) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MS;
    while (System.currentTimeMillis() < deadline) {
      FloorCache.Status st = cache.status();
      if (st == FloorCache.Status.READY || st == FloorCache.Status.FAILED) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("地板未在时限内落地，status=" + cache.status());
  }
}
