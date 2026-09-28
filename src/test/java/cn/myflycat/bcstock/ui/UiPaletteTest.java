package cn.myflycat.bcstock.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UiPaletteTest {

    @Test
    @DisplayName("原版 16 色系：自绘倒向原版观感（2026-09-27）")
    void colorsMatchVanillaishSpec() {
        assertEquals(0xFFFFFFFF, UiPalette.TEXT);
        assertEquals(0xFFAAAAAA, UiPalette.MUTED);
        assertEquals(0xFF555555, UiPalette.DISABLED);
        assertEquals(0xFFFFFF55, UiPalette.WARN);
        assertEquals(0xFFFF5555, UiPalette.ERROR);
        assertEquals(0xFF55FF55, UiPalette.UP);
        assertEquals(0xFFFF5555, UiPalette.DOWN);
        assertEquals(0x20FFFFFF, UiPalette.HOVER);
        assertEquals(0xFF000000, UiPalette.BLINK_OFF);
        // GOLD 仅 K 线 / HUD 倒计时
        assertEquals(0xFFCD9B5A, UiPalette.GOLD);
        assertEquals(480, UiPalette.TABLE_W);
        assertEquals(18, UiPalette.CELL);
    }

    @Test
    @DisplayName("640 与 1920 宽下表格左右边距相等 ±1")
    void tableCentered() {
        assertEquals((640 - 480) / 2, UiPalette.originX(640));
        assertEquals((1920 - 480) / 2, UiPalette.originX(1920));
        int left = UiPalette.originX(800);
        int right = 800 - left - UiPalette.TABLE_W;
        assertEquals(left, right);
    }

    @Test
    @DisplayName("★ 调试栅格默认关")
    void debugGridOffByDefault() {
        assertFalse(UiPalette.debugGridEnabled(), "正式构建不许默认画栅格线");
    }
}
