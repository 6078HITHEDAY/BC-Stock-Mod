package cn.myflycat.bcstock.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardNavStateTest {

    @Test
    @DisplayName("打开详情 → 返回：selectedIndex 与 scroll 不变")
    void detailReturnKeepsSelectedAndScroll() {
        BoardNavState nav = new BoardNavState();
        nav.save(5, 36.0);

        // 模拟 setScreen(DetailScreen) → setScreen(back) → init() 读 nav
        int restoredIdx = BoardNavState.clampIndex(nav.selectedIndex(), 16);
        double restoredScroll = nav.scrollY();
        assertEquals(5, restoredIdx, "返回后选中行必须保留");
        assertEquals(36.0, restoredScroll, 1e-9, "返回后滚动位置必须保留");

        BoardNavState after = nav.copy();
        assertEquals(5, after.selectedIndex());
        assertEquals(36.0, after.scrollY(), 1e-9);
    }

    @Test
    @DisplayName("越界下标夹成 -1")
    void clampOutOfRange() {
        assertEquals(-1, BoardNavState.clampIndex(5, 0));
        assertEquals(-1, BoardNavState.clampIndex(5, 3));
        assertEquals(-1, BoardNavState.clampIndex(-1, 10));
        assertEquals(2, BoardNavState.clampIndex(2, 10));
    }

    @Test
    @DisplayName("StockBoardScreen.init 不重置 sort/filter；返回复用实例")
    void initDoesNotWipeNavAndReusesBack() throws Exception {
        String board = Files.readString(
                Path.of("src/main/java/cn/myflycat/bcstock/ui/StockBoardScreen.java"),
                StandardCharsets.UTF_8);
        assertTrue(board.contains("restoreNavAfterRebuild"), "init 必须从 nav 恢复");
        assertTrue(board.contains("rememberNav()"), "开详情前必须记下 nav");
        assertFalse(board.contains("detailOpen"), "详情已迁独立 Screen");
        assertFalse(board.contains("new StockBoardScreen("), "返回不许 new 列表屏");

        String detail = Files.readString(
                Path.of("src/main/java/cn/myflycat/bcstock/ui/DetailScreen.java"),
                StandardCharsets.UTF_8);
        assertTrue(detail.contains("this.client.setScreen(back)"), "返回复用 back 实例");
        assertTrue(detail.contains("KlineCache.SHARED.request"), "init 输入路径请求 K 线");
        int renderAt = detail.indexOf("public void render(");
        assertTrue(renderAt > 0);
        assertFalse(detail.substring(renderAt).contains("KlineCache.SHARED.request"),
                "render 内不许 request");
    }
}
