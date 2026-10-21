package cn.myflycat.bcstock.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * HttpClient 复用：每轮行情刷新都 {@code new ApiClient()}，不许每次都新建一个
 * HttpClient（每个都带 selector / 连接池线程，长会话只增不减）。
 * 注入式构造（测试 seam）保持独立，不碰共享实例。
 */
class ApiClientReuseTest {

    @Test
    @DisplayName("★ 重复构造默认客户端，复用同一个 HttpClient")
    void defaultClientsShareOneHttpClient() {
        ApiClient a = new ApiClient("http://example.test", Duration.ofSeconds(1));
        ApiClient b = new ApiClient("http://example.test", Duration.ofSeconds(3));
        assertNotNull(a.httpClientForTest());
        assertSame(a.httpClientForTest(), b.httpClientForTest(),
                "默认构造必须复用共享 HttpClient，超时不同也不许各建一个");
        assertSame(ApiClient.sharedHttpClient(), a.httpClientForTest());
    }

    @Test
    @DisplayName("★ 注入式构造不建 HttpClient（测试 seam 保留）")
    void injectedGetterHasNoHttpClient() {
        ApiClient injected = new ApiClient("http://example.test", path -> "[]");
        assertNull(injected.httpClientForTest(), "注入 getter 的构造不许顺手建 HttpClient");
    }
}
