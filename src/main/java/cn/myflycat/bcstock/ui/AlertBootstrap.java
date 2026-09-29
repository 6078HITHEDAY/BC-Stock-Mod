package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.ServerGate;
import cn.myflycat.bcstock.data.BcStockSettings;
import cn.myflycat.bcstock.data.FloorCache;
import cn.myflycat.bcstock.data.MarketRefreshCoordinator;
import cn.myflycat.bcstock.data.SnapshotStore;
import cn.myflycat.bcstock.decision.AlertRules;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 提醒调度：每个客户端 tick 评估一次。渲染路径不跑这里。
 */
public final class AlertBootstrap {

    public static final AlertRules RULES = new AlertRules();

    private AlertBootstrap() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!ServerGate.active() || !BcStockSettings.alertEnabled()) {
                return;
            }
            long generation = MarketRefreshCoordinator.SHARED.generation();
            AlertRules.Switches sw = new AlertRules.Switches(
                    true,
                    BcStockSettings.alertChangeEnabled(),
                    BcStockSettings.alertFloorEnabled(),
                    BcStockSettings.alertPnlEnabled());
            // 服务端 distance_pct；无地板的公司不在 map 里。接上后仍只有真人跑过才算验证。
            Map<String, Double> distancePct = FloorCache.SHARED.distancePctByName();
            List<AlertRules.Alert> alerts = RULES.evaluate(
                    SnapshotStore.SHARED.get(), generation, sw, distancePct);
            if (!alerts.isEmpty()) {
                LocalAlertBridge.present(alerts);
            }
        });
    }
}
