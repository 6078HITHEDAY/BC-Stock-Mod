package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.data.BoardOpenPull;
import cn.myflycat.bcstock.data.CollectScheduler;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.SnapshotStore;
import java.time.Instant;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 盘面热键。不进 {@code probe/}——任务书禁改探测包，按键挂在展示层。
 *
 * <p>Yarn 无 {@code consumeClick} / {@code InputConstants} / {@code KeyMapping}，
 * 用已在 M0 跑通的 {@code KeyBinding} + {@code wasPressed()}。
 */
public final class BoardBootstrap {

    public static final String KEY_TRANSLATION = "key.bcstock.board";

    private static KeyBinding boardKey;

    private BoardBootstrap() {
    }

    public static void register() {
        boardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (boardKey != null) {
                while (boardKey.wasPressed()) {
                    if (client.currentScreen instanceof StockBoardScreen) {
                        client.setScreen(null);
                    } else {
                        client.setScreen(new StockBoardScreen());
                        BoardOpenPull.onOpen(CommandGateway.SHARED);
                    }
                }
            }
            // 盘面开着时续发被单飞挡住的那条；冷却期内会静默拒绝。
            if (client.currentScreen instanceof StockBoardScreen) {
                BoardOpenPull.tickWhileOpen(CommandGateway.SHARED);
            }
            String holdingsTrigger = CollectScheduler.SHARED.pendingCollectTag() ? "collect" : null;
            BoardOpenPull.drainToStore(CommandGateway.SHARED, SnapshotStore.SHARED, Instant.now(),
                    holdingsTrigger);
        });
        BcStockLog.info("盘面热键已注册：{}（默认 K）。调试栅格默认关，开用 -D{}", KEY_TRANSLATION,
                UiPalette.DEBUG_GRID_PROPERTY);
    }
}
