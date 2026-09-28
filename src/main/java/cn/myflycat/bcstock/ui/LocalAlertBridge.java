package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.decision.AlertRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 把提醒推到<strong>本地</strong>聊天栏。只用 {@code InGameHud.getChatHud().addMessage}，
 * 禁止任何会往服务器发包的玩家消息通道。
 */
public final class LocalAlertBridge {

    private static final List<AlertRules.Alert> LAST = new ArrayList<>();

    private LocalAlertBridge() {
    }

    public static List<AlertRules.Alert> lastForHud() {
        return List.copyOf(LAST);
    }

    public static void clearForTest() {
        LAST.clear();
    }

    public static void present(List<AlertRules.Alert> alerts) {
        LAST.clear();
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        LAST.addAll(alerts);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) {
            return;
        }
        for (AlertRules.Alert a : alerts) {
            client.inGameHud.getChatHud().addMessage(Text.literal("[bcstock] " + a.message()));
            BcStockLog.info("提醒：{} / {}", a.kind(), a.message());
        }
    }

    /** 成交回执。不是 L1 涨跌提醒。 */
    public static void presentFill(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("[bcstock] " + message));
        }
        BcStockLog.info("提醒：成交 / {}", message);
    }
}
