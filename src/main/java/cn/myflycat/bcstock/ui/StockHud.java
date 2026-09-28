package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.data.BcStockSettings;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.FloorCache;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.SnapshotStore;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.decision.AlertRules;
import cn.myflycat.bcstock.decision.DecisionEngine;
import cn.myflycat.bcstock.decision.DecisionPreset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * 左上角盯盘条。数据只读 {@link SnapshotStore}，渲染回调里<b>不许发命令</b>。
 */
public final class StockHud {

    public static final String KEY_TRANSLATION = "key.bcstock.hud";
    public static final int MAX_ROWS = 5;
    public static final int PAD = 4;
    public static final int LINE_H = 10;

    private static KeyBinding hudKey;
    private static boolean visible = true;

    private StockHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.of("bcstock", "hud"),
                StockHud::render);
        hudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (hudKey == null) {
                return;
            }
            while (hudKey.wasPressed()) {
                visible = !visible;
            }
        });
        BcStockLog.info("HUD 已挂到聊天层之前（默认 H 开关，渲染只读快照）");
    }

    public static boolean visible() {
        return visible;
    }

    public static void setVisibleForTest(boolean value) {
        visible = value;
    }

    /** HUD 回调。只读快照，不往服务器发任何东西。 */
    public static void render(DrawContext graphics, RenderTickCounter tickCounter) {
        if (!visible || graphics == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }
        List<HudLine> lines = lines(SnapshotStore.SHARED.get(), Instant.now());
        int y = PAD;
        for (HudLine line : lines) {
            graphics.drawText(client.textRenderer, line.text(), PAD, y, line.color(), false);
            y += LINE_H;
        }
    }

    public static List<HudLine> lines(StockSnapshot snap, Instant now) {
        List<HudLine> out = new ArrayList<>();
        List<AlertRules.Alert> alerts = LocalAlertBridge.lastForHud();
        if (!alerts.isEmpty()) {
            AlertRules.Alert a = alerts.get(0);
            out.add(new HudLine("!" + a.message(), UiPalette.WARN));
        }
        long remain = RefreshPhase.millisUntil(now);
        int countColor = remain <= 10_000L ? UiPalette.GOLD : UiPalette.MUTED;
        out.add(new HudLine("下次刷新 " + RefreshPhase.mmss(remain), countColor));
        if (snap == null) {
            return List.copyOf(out);
        }
        List<HoldingView> held = snap.heldOnly();
        int shown = Math.min(MAX_ROWS, held.size());
        DecisionPreset preset = BcStockSettings.decisionPreset();
        for (int i = 0; i < shown; i++) {
            out.add(row(snap, held.get(i), preset));
        }
        if (held.size() > MAX_ROWS) {
            out.add(new HudLine("+" + (held.size() - MAX_ROWS) + " 家", UiPalette.MUTED));
        } else if (shown < MAX_ROWS) {
            firstUnheldBuy(snap, held, preset).ifPresent(name ->
                    out.add(new HudLine("买 " + name, UiPalette.UP)));
        }
        return List.copyOf(out);
    }

    private static Optional<String> firstUnheldBuy(StockSnapshot snap,
                                                   List<HoldingView> held,
                                                   DecisionPreset preset) {
        Set<String> heldNames = new HashSet<>();
        for (HoldingView h : held) {
            heldNames.add(h.name());
        }
        for (CompanyView c : snap.companies()) {
            if (heldNames.contains(c.name())) {
                continue;
            }
            FloorView floor = FloorCache.SHARED.ofName(c.name()).orElse(null);
            DecisionEngine.Advice advice = DecisionEngine.advise(c, snap.holdingOf(c.name()), floor, preset);
            if (advice.side() == DecisionEngine.Side.BUY) {
                return Optional.of(c.name());
            }
        }
        return Optional.empty();
    }

    private static HudLine row(StockSnapshot snap, HoldingView holding, DecisionPreset preset) {
        CompanyView company = snap.companyOf(holding.name());
        String price = (company != null && company.priceKnown())
                ? BoardFormat.price(company.price()) : BoardFormat.UNKNOWN;
        String change = (company != null) ? BoardFormat.pct(company.changePct()) : BoardFormat.UNKNOWN;
        String pnl = BoardFormat.pct(holding.profitPct());
        FloorView floor = FloorCache.SHARED.ofName(holding.name()).orElse(null);
        DecisionEngine.Advice advice = DecisionEngine.advise(company, holding, floor, preset);
        String mark = switch (advice.side()) {
            case BUY -> "  买";
            case SELL -> "  卖";
            case HOLD -> "";
        };
        int color = UiPalette.TEXT;
        if (advice.side() == DecisionEngine.Side.SELL) {
            color = UiPalette.DOWN;
        } else if (advice.side() == DecisionEngine.Side.BUY) {
            color = UiPalette.UP;
        } else if (company != null && !Double.isNaN(company.changePct())) {
            color = company.changePct() >= 0 ? UiPalette.UP : UiPalette.DOWN;
        }
        return new HudLine(holding.name() + "  " + price + "  " + change + "  " + pnl + mark, color);
    }

    public record HudLine(String text, int color) {
    }
}
