package cn.myflycat.bcstock;

import cn.myflycat.bcstock.config.ConfigRuntime;
import cn.myflycat.bcstock.data.CollectScheduler;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.DegradePolicy;
import cn.myflycat.bcstock.data.LedgerApply;
import cn.myflycat.bcstock.data.LedgerRuntime;
import cn.myflycat.bcstock.data.MarketCache;
import cn.myflycat.bcstock.data.MarketDataAge;
import cn.myflycat.bcstock.data.MarketPollScheduler;
import cn.myflycat.bcstock.data.MarketRefreshCoordinator;
import cn.myflycat.bcstock.data.SnapshotJournal;
import cn.myflycat.bcstock.data.SnapshotStore;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.probe.InvestProbeBootstrap;
import cn.myflycat.bcstock.ui.AlertBootstrap;
import cn.myflycat.bcstock.ui.BoardBootstrap;
import cn.myflycat.bcstock.ui.LocalAlertBridge;
import cn.myflycat.bcstock.ui.StockHud;
import java.io.File;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;

/**
 * mod 的客户端入口。
 *
 * <p>纯客户端 mod（{@code fabric.mod.json} 里 {@code environment = "client"}），
 * 服务端一行都不用改，也不该改。
 *
 * <p>三层的包结构已经按 PLAN.md §2 预留好，M0 只有 {@code probe} 包是实的：
 * <ul>
 *   <li>{@code ui} —— 展示层（HUD / GUI 覆盖层 / 独立面板）</li>
 *   <li>{@code decision} —— 决策层（BCquant 地板模型移植，纯数学，可离线测）</li>
 *   <li>{@code data} —— 数据层（API 客户端 + 本地 JSONL 积累）</li>
 *   <li>{@code probe} —— 容器探测（把 {@code /invest} GUI 里的字段读出来）</li>
 * </ul>
 */
public final class BcStockClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BcStockLog.info("BC Stock 客户端初始化开始");
        MinecraftClient client = MinecraftClient.getInstance();
        ConfigRuntime.bootstrap(client.runDirectory);

        // M0 的全部行为都在这里挂上去：开屏识别 + 槽位去抖动 + 热键强制 dump。
        // 槽位包那一路靠 bcstock.mixins.json 里的 ClientPlayNetworkHandlerMixin，
        // 不需要在这里注册。
        InvestProbeBootstrap.register();
        BoardBootstrap.register();
        StockHud.register();
        AlertBootstrap.register();
        wireCommandGateway();
        wireCollectScheduler();
        wireMarketPoll();
        wireSnapshotJournal();
        wireLedger();
        DebugCommandTrigger.registerIfFlagPresent();

        logMixinStatus();
    }

    /** 人在线且落在目标服。单机 / 其他服一律 false。 */
    private static boolean playerOnTargetServer() {
        if (!ServerGate.active()) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.player != null && client.getNetworkHandler() != null;
    }

    private static void wireCollectScheduler() {
        CollectScheduler.SHARED.setGateway(CommandGateway.SHARED);
        CollectScheduler.SHARED.setConnected(BcStockClient::playerOnTargetServer);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CollectScheduler.SHARED.tick());
        BcStockLog.info("采集调度已挂上（默认关；改 config/bcstock.json 或 -D 覆盖）");
    }

    /**
     * 把游戏里的 {@code sendChatCommand} 和 tick 接到网关。
     * <b>这里不发任何命令</b>——只把管子接上，等人或后续 Task 来调 {@code trySend*}。
     * 非目标服 sender 直接拒，调度器漏判也不会往别的服发包。
     */
    private static void wireCommandGateway() {
        CommandGateway.SHARED.setSender(command -> {
            if (!ServerGate.active()) {
                return false;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.player.networkHandler == null) {
                return false;
            }
            client.player.networkHandler.sendChatCommand(command);
            return true;
        });
        CommandGateway.SHARED.setSnapshotStore(SnapshotStore.SHARED);
        CommandGateway.SHARED.setOnFilled(draft ->
                LocalAlertBridge.presentFill(LedgerApply.fillMessage(draft)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> CommandGateway.SHARED.tick());
        BcStockLog.info("命令网关已挂上：聊天注入 + tick 收齐；启动时不发任何命令");
    }

    private static void wireMarketPoll() {
        MarketRefreshCoordinator.SHARED.setClock(System::currentTimeMillis);
        MarketRefreshCoordinator.SHARED.setConnected(BcStockClient::playerOnTargetServer);
        MarketRefreshCoordinator.SHARED.setGateway(CommandGateway.SHARED);
        MarketRefreshCoordinator.SHARED.setStore(SnapshotStore.SHARED);
        MarketRefreshCoordinator.SHARED.setPoller(MarketPollScheduler.SHARED);
        MarketRefreshCoordinator.SHARED.setCollect(CollectScheduler.SHARED);
        CommandGateway.SHARED.setOnMarketUpdated(MarketRefreshCoordinator.SHARED::onBroadcast);
        ClientTickEvents.END_CLIENT_TICK.register(client -> MarketRefreshCoordinator.SHARED.tick());
        BcStockLog.info("行情刷新：进目标服 API 全量，聊天增量，20 分钟兜底");
    }

    /**
     * 启动只恢复本地文件（行情缓存 + JSONL），不拉 API、不把刷新状态机跑起来。
     * API 全量改到匹配成功的 {@link #wireLedger} JOIN。
     *
     * <p>缓存与 JSONL 用途不同：{@code market-cache.json} 可覆盖的最新行情；
     * {@code snapshots.jsonl} append-only 历史。二者并存。
     */
    private static void wireSnapshotJournal() {
        MinecraftClient client = MinecraftClient.getInstance();
        File bcDir = new File(client.runDirectory, SnapshotJournal.RELATIVE_DIR);
        File journalFile = new File(bcDir, SnapshotJournal.FILE_NAME);
        File cacheFile = new File(bcDir, MarketCache.FILE_NAME);

        SnapshotJournal journal = new SnapshotJournal(journalFile);
        SnapshotStore.SHARED.setJournal(journal);
        MarketPollScheduler.SHARED.setCacheFile(cacheFile);
        MarketRefreshCoordinator.SHARED.setCacheFile(cacheFile);

        // 1) 行情缓存立刻灌内存（market_id 已抹）——进目标服后界面毫秒级有数字。
        MarketCache.read(cacheFile).ifPresent(payload -> {
            List<CompanyView> views = MarketCache.toViewsWipingMarketId(payload);
            if (views.isEmpty()) {
                return;
            }
            Instant at = Instant.ofEpochMilli(Math.max(0L, payload.savedAtMs()));
            // apiHealthy=true：有可显示数据；market_id 全未知 → 不能下单（正确）。
            StockSnapshot fromCache = StockSnapshot.empty().withCompanies(views, true, at);
            SnapshotStore.SHARED.set(fromCache, false);
            MarketDataAge.setCompaniesAtMs(payload.savedAtMs());
            BcStockLog.info("从行情缓存恢复 {} 家（market_id 未验证，不能下单）", views.size());
        });

        // 2) JSONL 恢复持仓/余额；若缓存已灌公司行则只并持仓/余额，编号继续抹掉。
        journal.last().ifPresent(previous -> {
            StockSnapshot wiped = previous.withIdsFrom(Map.of());
            StockSnapshot cur = SnapshotStore.SHARED.get();
            if (cur.companies().isEmpty()) {
                SnapshotStore.SHARED.set(wiped, false);
            } else {
                StockSnapshot next = cur.withWallet(wiped.wallet(), wiped.at());
                if (wiped.holdingsKnown()) {
                    next = next.withHoldings(wiped.holdings(), wiped.at());
                }
                SnapshotStore.SHARED.set(next.withIdsFrom(Map.of()), false);
            }
            BcStockLog.info("从本地 JSONL 恢复上一份快照（编号已抹成未知）");
        });
    }

    /**
     * 进目标服才开账本 + API 全量；单机 / 其他服保持关闭。
     */
    private static void wireLedger() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo info = client.getCurrentServerEntry();
            String address = (info == null) ? null : info.address;
            if (!ServerGate.matches(address)) {
                ServerGate.clear();
                BcStockLog.info("非目标服（{}），BC Stock 保持关闭",
                        address == null ? "单机/无地址" : address);
                return;
            }
            ServerGate.setActive(true);
            UUID uuid = null;
            if (client.player != null) {
                uuid = client.player.getUuid();
            }
            if (uuid == null && handler != null && handler.getProfile() != null) {
                uuid = handler.getProfile().id();
            }
            String id = (uuid == null) ? "unknown" : uuid.toString();
            LedgerRuntime.onJoin(client.runDirectory, id);
            Thread poll = new Thread(() -> {
                if (!ServerGate.active()) {
                    return;
                }
                MarketRefreshCoordinator.SHARED.onJoinPoll();
                StockSnapshot snap = SnapshotStore.SHARED.get();
                DegradePolicy policy = DegradePolicy.of(snap, CommandGateway.SHARED);
                BcStockLog.info("降级：{}", policy);
                BcStockLog.info("快照打印：公司 {} 家 / 持仓 {} / 余额 {} / 降级 {}",
                        snap.companies().size(),
                        snap.holdingsKnown() ? snap.holdingsOrEmpty().size() + " 家" : "未知",
                        snap.wallet().known() ? snap.wallet().balance() : "未知",
                        policy);
            }, "bcstock-api");
            poll.setDaemon(true);
            poll.start();
            BcStockLog.info("已连目标服 {}，BC Stock 启用", address);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerGate.clear();
            LedgerRuntime.onDisconnect();
        });
        BcStockLog.info("账本：仅目标服进服后按 UUID 打开 bcstock/<uuid>/store");
    }

    /**
     * Mixin 自检（正式环境安全）。
     *
     * <p>替换掉了之前那段用 {@code Class.forName("<yarn 开发名>")} 的临时验证——那段在开发环境能跑，
     * 装进正式实例必然崩：正式环境里这个类叫 intermediary 名 {@code class_634}，开发名根本不存在
     * （2026-09-26 11:36 实测崩在这里，整局 {@code Initializing game} 直接挂）。
     *
     * <p>这里改用类字面量（构建时会被 remapJar 改写成正确名字）+ 反射查注入方法名：Mixin 会把注入方法的
     * 副本合并进目标类，名字保留 {@code bcstock$} 前缀。查到才说明「注入真的生效」，而不是「配置看着对」。
     *
     * <p>自检只报告、不抛异常——它绝不能把游戏带崩。
     */
    private static void logMixinStatus() {
        try {
            // 判据不能写精确相等：Mixin 会把注入方法改名后合并进目标类
            // （实测形如 handler$<随机>$$<mixin$方法名>，见日志里 sclp 的 handler$cnh000$sclp$injectSetName），
            // 所以按 bcstock$ 子串找，并把真实名字打出来当证据。
            final String prefix = "bcstock$";
            StringBuilder found = new StringBuilder();
            for (Method method : ClientPlayNetworkHandler.class.getDeclaredMethods()) {
                if (method.getName().contains(prefix)) {
                    if (found.length() > 0) {
                        found.append(", ");
                    }
                    found.append(method.getName());
                }
            }
            if (found.length() == 0) {
                BcStockLog.error("Mixin 自检失败：{} 里找不到任何 {} 注入方法——容器内容到达时不会通知探测器（探测键仍可用）",
                        ClientPlayNetworkHandler.class.getSimpleName(), prefix);
            } else {
                BcStockLog.info("Mixin 自检通过：{} 里的注入方法 = [{}]",
                        ClientPlayNetworkHandler.class.getSimpleName(), found);
            }
        } catch (Throwable t) {
            BcStockLog.warn("Mixin 自检执行失败（不影响继续启动）：{}", t.toString());
        }
    }
}
