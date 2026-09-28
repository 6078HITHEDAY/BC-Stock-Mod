package cn.myflycat.bcstock.probe;

import cn.myflycat.bcstock.BcStockLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 把 {@link InvestProbe} 接到 Fabric 的事件总线上。
 *
 * <p>单独成类的理由：{@link InvestProbe} 是<b>纯逻辑</b>（不依赖任何事件 API，只吃
 * {@code Screen} / {@code ScreenHandler} / tick），这样 M3 之后就能拿它离线跑回归对照
 * （「黄金样本」测试策略）。事件注册这种 glue code 留在这里，
 * 以后换事件源（比如 M1 改成渲染钩子）不用动探测逻辑。
 *
 * <p>M0 只注册三样东西，全是只读的：
 * <ol>
 *   <li>{@code ScreenEvents.AFTER_INIT} —— 开屏时认容器（是不是「公司列表」等股市容器）</li>
 *   <li>{@code ClientTickEvents.END_CLIENT_TICK} —— 槽位收齐后的去抖动计时、关屏检测</li>
 *   <li>探测热键 —— 手动强制 dump 当前容器（默认 P，可在「按键绑定」里改）</li>
 * </ol>
 */
public final class InvestProbeBootstrap {

    /** 热键的翻译键，对应 {@code assets/bcstock/lang/*.json}。 */
    public static final String PROBE_KEY_TRANSLATION = "key.bcstock.probe";

    private static KeyBinding probeKey;

    private InvestProbeBootstrap() {
    }

    /** 在 {@code onInitializeClient} 里调一次。重复调用是安全的（事件总线会去重，热键会覆盖）。 */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                InvestProbe.onScreenOpened(screen, "AFTER_INIT"));

        ClientTickEvents.END_CLIENT_TICK.register(InvestProbeBootstrap::onEndTick);

        probeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                PROBE_KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                // 用原版现成的 MISC 分类，不自建分类——自建分类在 1.21.9+ 要走注册流程，
                // M0 没必要为一个调试热键付这个复杂度。
                KeyBinding.Category.MISC));

        BcStockLog.info("探测器已挂上事件总线：开屏识别 / 槽位去抖动 / 热键「{}」手动 dump", PROBE_KEY_TRANSLATION);
    }

    /**
     * 每个客户端 tick 末尾跑一次。
     *
     * <p>顺序要紧：<b>先处理热键再走常规 tick</b>。热键是玩家明确要求「现在就 dump」，
     * 应该立刻出结果，不能被 {@link InvestProbe#SETTLE_TICKS} 的去抖动再拖两 tick。
     *
     * <p>用 {@code while (wasPressed())} 而不是 {@code if}：一 tick 内连按多次时 vanilla 会
     * 把计数累加，循环能全部吃掉，避免残留的按下次数在下一 tick 触发一次莫名其妙的 dump。
     */
    private static void onEndTick(MinecraftClient client) {
        if (probeKey != null) {
            while (probeKey.wasPressed()) {
                InvestProbe.probeNow(client);
            }
        }
        InvestProbe.tick(client);
    }
}
