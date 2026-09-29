package cn.myflycat.bcstock;

import cn.myflycat.bcstock.data.CommandGateway;
import java.io.File;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 只在 {@code config/bcstock-debug.flag} 存在时注册的调试键。
 * 正式使用不许留这个 flag——键默认不存在，Controls 里也看不到。
 *
 * <p>必须走 {@link CommandGateway#trySendBalance()} /
 * {@link CommandGateway#trySendPortfolio()}，不许绕过节流。
 * 冷却在内存里，同一条采集命令 15 分钟内不重发；想重测就重开游戏。
 */
public final class DebugCommandTrigger {

    public static final String FLAG_RELATIVE = "config/bcstock-debug.flag";
    public static final String KEY_TRANSLATION = "key.bcstock.debug.send";

    private static KeyBinding debugKey;
    private static boolean nextIsBalance = true;

    private DebugCommandTrigger() {
    }

    public static void registerIfFlagPresent() {
        File flag = new File(MinecraftClient.getInstance().runDirectory, FLAG_RELATIVE);
        if (!flag.isFile()) {
            return;
        }
        debugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (debugKey == null) {
                return;
            }
            // Yarn 名 wasPressed = 官方文档 consumeClick
            while (debugKey.wasPressed()) {
                if (!ServerGate.active()) {
                    continue;
                }
                fireOnce();
            }
        });
        BcStockLog.info("debug 触发器已注册（{} 存在）。正式使用请删这个 flag", flag.getPath());
    }

    private static void fireOnce() {
        boolean sendBal = nextIsBalance;
        nextIsBalance = !nextIsBalance;
        String shown = sendBal ? "/bal" : "/invest portfolio";
        boolean sent = sendBal
                ? CommandGateway.SHARED.trySendBalance()
                : CommandGateway.SHARED.trySendPortfolio();
        BcStockLog.info("debug 触发：发 {} → {}", shown, sent);
    }
}
