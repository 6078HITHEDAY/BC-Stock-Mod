package cn.myflycat.bcstock.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ElementListWidget 默认禁选中；盘面必须覆写。真机点行/白框留给真人。
 */
class CompanyListWidgetSelectTest {

    @Test
    @DisplayName("覆写 isEntrySelectionAllowed=true；Entry.mouseClicked 点行体选中")
    void selectionOverrideInSource() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/cn/myflycat/bcstock/ui/CompanyListWidget.java"),
                StandardCharsets.UTF_8);
        assertTrue(src.contains("isEntrySelectionAllowed()"), "必须覆写 isEntrySelectionAllowed");
        assertTrue(src.contains("return true;"), "isEntrySelectionAllowed 必须为 true");
        // 方法体：protected boolean isEntrySelectionAllowed() { return true; }
        int methodAt = src.indexOf("protected boolean isEntrySelectionAllowed()");
        assertTrue(methodAt > 0, "找不到 isEntrySelectionAllowed 方法");
        String methodBody = src.substring(methodAt, methodAt + 120);
        assertTrue(methodBody.contains("return true"),
                "isEntrySelectionAllowed 必须 return true（原版 ElementList 默认 false）");

        assertTrue(src.contains("public boolean mouseClicked(Click click, boolean doubled)"),
                "CompanyEntry 必须自定义 mouseClicked");
        assertTrue(src.contains("child.mouseClicked(click, doubled)"),
                "先派发给买/卖按钮");
        // 按钮吃掉后对列表返回 false，避免抢选中
        int clickAt = src.indexOf("public boolean mouseClicked(Click click, boolean doubled)");
        String clickBody = src.substring(clickAt, clickAt + 350);
        assertTrue(clickBody.contains("return false"),
                "按钮处理后应对列表 return false（不改选中态）");
        assertTrue(clickBody.contains("return true"),
                "行体点击应对列表 return true（触发 setSelected）");
    }
}
