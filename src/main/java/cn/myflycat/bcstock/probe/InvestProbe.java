package cn.myflycat.bcstock.probe;

import cn.myflycat.bcstock.BcStockLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/**
 * {@code /invest} 容器的只读探测器（M0 的主角）。
 *
 * <h2>为什么需要「等一下再读」</h2>
 * 打开容器的那一刻，客户端手里<b>只有标题，没有内容</b>。实测 1.21.11 的
 * {@code OpenScreenS2CPacket} 只带 {@code syncId} / {@code ScreenHandlerType} / {@code name}
 * 三个字段，<b>不含任何 ItemStack</b>（用 javap 核过字节码）。格子内容是在开屏包<b>之后</b>
 * 由一串 {@code ScreenHandlerSlotUpdateS2CPacket} 逐个送到的。
 *
 * <p>所以「在屏幕初始化时就读槽位」一定读到一堆空格子。这里的做法是：
 * <ol>
 *   <li>{@link #onSlotPacket} 每收到一个槽位包就把「稳定计时」清零；</li>
 *   <li>{@link #tick} 在主线程上等连续 {@value #SETTLE_TICKS} 个 tick 没有新包，
 *       才认为这一批同步完了，读一次快照。</li>
 * </ol>
 * 这个去抖动同时也解决了「GUI 开着时价格每 15 分钟刷新一次」——刷新会再触发一轮槽位包，
 * 于是自动再 dump 一次，不用额外机制。
 *
 * <h2>只读</h2>
 * 本类<b>不发送任何点击</b>，只读 {@code ScreenHandler.slots}。执行层（M4）才会碰
 * {@code handleInventoryMouseClick}，而且上线前要先确认服务器守则。
 */
public final class InvestProbe {

    /**
     * 收到最后一个槽位包后再等这么多 tick 才认为同步结束。
     * 同一容器的槽位包是同一批灌进来的，2 tick（约 100ms）足够。
     */
    public static final int SETTLE_TICKS = 2;

    /** 单次 dump 最多逐格打印多少个槽位，防止遇到超大容器把日志刷爆。 */
    public static final int MAX_LOGGED_SLOTS = 120;

    // --- 当前盯着的容器 ---
    private static ScreenHandler watched;
    private static Screen watchedScreen;
    private static String watchedTitle = "";
    private static String watchedTrigger = "";
    private static boolean dirty;
    private static int quietTicks;
    private static int dumpsThisSession;
    private static long lastSignature = Long.MIN_VALUE;

    private InvestProbe() {
    }

    // ------------------------------------------------------------------
    // 事件入口
    // ------------------------------------------------------------------

    /**
     * 屏幕初始化时调用（由 {@code ScreenEvents.AFTER_INIT} 驱动）。
     *
     * <p>窗口缩放会让 {@code Screen.init()} 重跑、这个回调再打一次；靠
     * {@code screen} 与 {@code handler} 的同一性判断把这种重复挡掉。
     *
     * @param source 触发来源，只进日志，方便排查
     */
    public static void onScreenOpened(Screen screen, String source) {
        if (!(screen instanceof HandledScreen<?> handled)) {
            // 不是容器界面 → 可能是聊天栏、别的 mod 的界面、或盖在容器上的一层。
            // 关键：界面被盖住 ≠ 容器没了。这时 player.currentScreenHandler 还是我们的容器，
            // 槽位数据照样能读。所以这里只记录、按需停，绝不无条件停——真服实测就是被这一下踢掉、0 dump 的。
            BcStockLog.info("检测到非容器界面：{}「{}」（来源={}）{}",
                    screen.getClass().getSimpleName(), screen.getTitle().getString(), source,
                    watched == null ? "" : "；容器监视不受影响");
            PlayerEntity player = MinecraftClient.getInstance().player;
            if (watched != null && (player == null || player.currentScreenHandler != watched)) {
                stopWatching("容器已不在客户端手上");
            }
            return;
        }

        ScreenHandler handler = handled.getScreenHandler();
        if (handler == watched && screen == watchedScreen) {
            return;
        }

        String title = screen.getTitle().getString();
        StockContainerKind kind = StockContainerKind.of(title);

        // 每个容器都打这一行：M0 验收时如果标题匹配失败，看日志就知道服务端实际发的是什么标题。
        BcStockLog.info("检测到容器界面：标题=「{}」 syncId={} 槽位数={} 识别={}（来源={}）",
                title, handler.syncId, handler.slots.size(), kind, source);

        if (kind == StockContainerKind.UNKNOWN) {
            // 不 dump：玩家自己的背包界面也是 HandledScreen，里面是个人装备，
            // 不该进日志（data/gui/ 的旧日志就是被背包撑长的）。想看得手动按键。
            stopWatching("非股市容器，默认不 dump（可在游戏内按探测键强制 dump）");
            return;
        }

        watched = handler;
        watchedScreen = screen;
        watchedTitle = title;
        watchedTrigger = source;
        dirty = true;
        quietTicks = 0;
        dumpsThisSession = 0;
        // 新会话第一次一定要打，哪怕内容和上次一模一样——否则「重开一次却什么都没看到」很难排查
        lastSignature = Long.MIN_VALUE;
    }

    /**
     * 收到一个槽位更新包时调用（由 {@code ClientPlayNetworkHandler} 的 Mixin 驱动）。
     *
     * <p>这里<b>只重置计时器，不读数据</b>：包可能还在批量队列里、也可能主线程还没轮到，
     * 真正的读取统一放到 {@link #tick} 上做，保证只在主线程读。
     */
    public static void onSlotPacket(int syncId, int slot) {
        if (watched == null || watched.syncId != syncId) {
            return;
        }
        dirty = true;
        quietTicks = 0;
    }

    /**
     * 收到「容器内容整包」时调用（由 {@link InventoryS2CPacket} 的 Mixin 驱动）。
     *
     * <p>为什么非挂这个包不可：{@link #onSlotPacket} 只在「单格变化」时来，而容器<b>初始内容</b>是
     * 开屏后整包送来的。只等逐格包的话，一旦内容包比开屏包晚到 ≥{@value #SETTLE_TICKS} tick，
     * 第一次快照就是一屏空槽——而且之后没有包再来触发，不会补 dump。
     *
     * <p>那行日志是给「包到底隔几 tick 到」留证据的，排查时别删。
     */
    public static void onInventoryContents(int syncId) {
        if (watched == null || watched.syncId != syncId) {
            return;
        }
        BcStockLog.info("容器「{}」内容整包到达（InventoryS2CPacket syncId={}），重置稳定计时",
                watchedTitle, syncId);
        dirty = true;
        quietTicks = 0;
    }

    /** 每个客户端 tick 末尾调用。 */
    public static void tick(MinecraftClient client) {
        if (watched == null) {
            return;
        }

        // 判据用「客户端手上挂的还是不是这个 ScreenHandler」，不看 currentScreen——
        // 界面被别的 Screen 盖住时数据照样能读（真服实测：用 currentScreen 判会被一层覆盖界面踢掉）。
        if (client.player == null || client.player.currentScreenHandler != watched) {
            stopWatching("容器已被替换或关闭");
            return;
        }

        if (!dirty) {
            return;
        }
        if (++quietTicks < SETTLE_TICKS) {
            return;
        }

        dirty = false;
        dump(client, watched, watchedTitle, watchedTrigger, dumpsThisSession == 0);
        dumpsThisSession++;
    }

    /**
     * 强制 dump 当前打开的容器，不看是不是股市容器（手动按键走这条路）。
     *
     * @return 是否真的 dump 了
     */
    public static boolean probeNow(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            BcStockLog.info("当前没有打开任何容器界面，无处可 dump");
            return false;
        }
        dump(client, handled.getScreenHandler(), client.currentScreen.getTitle().getString(),
                "手动触发", true);
        if (handled.getScreenHandler() == watched) {
            dumpsThisSession++;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 快照
    // ------------------------------------------------------------------

    /**
     * 读一次只读快照。<b>不打印任何东西</b>，方便 M1 的展示层直接复用。
     *
     * <p>容器区 / 背包靠 {@code Slot.inventory} 的身份切分，不靠格数猜。
     */
    public static ProbedContainer snapshot(MinecraftClient client, ScreenHandler handler, String title) {
        PlayerEntity player = client.player;
        PlayerInventory playerInventory = player == null ? null : player.getInventory();

        List<ProbedSlot> containerSlots = new ArrayList<>();
        int playerSlots = 0;
        int playerNonEmpty = 0;
        int mismatch = 0;
        int index = 0;

        for (Slot slot : handler.slots) {
            boolean isPlayerSlot = playerInventory != null && slot.inventory == playerInventory;
            if (isPlayerSlot) {
                playerSlots++;
                if (!slot.getStack().isEmpty()) {
                    playerNonEmpty++;
                }
            } else {
                if (slot.id != index) {
                    mismatch++;
                }
                ItemStack stack = slot.getStack();
                containerSlots.add(ProbedSlot.of(
                        index, slot.id, stack, tooltipOf(stack, player)));
            }
            index++;
        }

        List<CompanyRow> companies = new ArrayList<>();
        for (ProbedSlot probed : containerSlots) {
            CompanyRow row = CompanyRow.from(probed);
            if (row != null) {
                companies.add(row);
            }
        }

        return new ProbedContainer(
                StockContainerKind.of(title),
                title,
                handler.syncId,
                handler.slots.size(),
                containerSlots.size(),
                playerSlots,
                playerNonEmpty,
                containerSlots,
                companies,
                mismatch,
                containerSlots.size() > MAX_LOGGED_SLOTS);
    }

    /**
     * 取 tooltip 文本行。
     *
     * <p>用 {@link TooltipType#BASIC}：和玩家肉眼看到的、以及 MCC {@code /inventory} dump 的
     * 是同一套行，方便拿旧样本对照。{@code ADVANCED}（F3+H）会多塞物品 ID 之类的行，反而对不上。
     *
     * <p>整个调用包了 try/catch：某些物品的 tooltip 需要 world / registry，
     * 单件物品出问题不该把整次 dump 带崩。
     */
    private static List<String> tooltipOf(ItemStack stack, PlayerEntity player) {
        if (stack.isEmpty()) {
            return List.of();
        }
        try {
            List<Text> lines = stack.getTooltip(Item.TooltipContext.DEFAULT, player, TooltipType.BASIC);
            List<String> out = new ArrayList<>(lines.size());
            for (Text line : lines) {
                out.add(line.getString());
            }
            return out;
        } catch (RuntimeException e) {
            BcStockLog.warn("生成 tooltip 失败，跳过该格：{} / {}", stack.getItem(), e.toString());
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // 打印
    // ------------------------------------------------------------------

    private static void dump(MinecraftClient client, ScreenHandler handler, String title,
            String trigger, boolean force) {
        ProbedContainer container = snapshot(client, handler, title);
        long signature = container.signature();

        if (!force && signature == lastSignature) {
            BcStockLog.info("容器「{}」收到槽位更新，但内容与上次一致，跳过重复 dump（触发={}）",
                    title, trigger);
            return;
        }
        lastSignature = signature;

        BcStockLog.info("===== /invest 容器探测开始 #{}（触发={}）=====", dumpsThisSession + 1, trigger);
        BcStockLog.info("{}", container.header());

        if (container.slotIdMismatch() > 0) {
            // M4 的命门：模拟点击要发的是 slots 下标，不是 Slot.id。两者不一致时按 id 点会点错格子。
            BcStockLog.warn("有 {} 个槽位的 slots 下标与 Slot.id 不一致——点击层必须用下标，别用 id",
                    container.slotIdMismatch());
        }

        int printed = 0;
        for (ProbedSlot slot : container.slots()) {
            if (printed >= MAX_LOGGED_SLOTS) {
                break;
            }
            BcStockLog.info("  [容器] {}", slot.toLogLine());
            if (!slot.empty()) {
                int loreIndex = 0;
                for (String line : slot.tooltip()) {
                    // tooltip 第 0 行就是物品名，已经在上一行打过了，别重复
                    if (loreIndex == 0 && line.equals(slot.itemName())) {
                        loreIndex++;
                        continue;
                    }
                    BcStockLog.info("         lore[{}] | {}", loreIndex++, line);
                }
            }
            printed++;
        }
        if (container.truncated() || printed < container.slots().size()) {
            BcStockLog.info("  ...（还有 {} 格未打印，上限 {}）",
                    container.slots().size() - printed, MAX_LOGGED_SLOTS);
        }

        BcStockLog.info("公司行识别（{} 家）：", container.companies().size());
        for (CompanyRow row : container.companies()) {
            BcStockLog.info("  {}", row.toLogLine());
        }

        BcStockLog.info("===== 探测结束：容器区 {} 格 / 非空 {} 格 / 公司 {} 家 =====",
                container.containerSlotCount(), container.nonEmptyContainerSlots(),
                container.companies().size());
    }

    private static void stopWatching(String reason) {
        if (watched == null) {
            return;
        }
        BcStockLog.info("停止监视容器「{}」：{}（本次共 dump {} 次）",
                watchedTitle, reason, dumpsThisSession);
        watched = null;
        watchedScreen = null;
        watchedTitle = "";
        watchedTrigger = "";
        dirty = false;
        quietTicks = 0;
        dumpsThisSession = 0;
        lastSignature = Long.MIN_VALUE;
    }
}
