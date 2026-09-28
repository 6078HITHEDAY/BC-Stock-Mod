package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.TradeSettings;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 下单确认：独立 Screen。Enter 确认、Esc 取消（零发包）。
 * 打开后先查实时价；发命令<strong>只</strong>在确认动作里走 {@link TradeFlow#confirm}。
 * 发出失败或排队时<strong>不关屏</strong>，等买卖回执才关。
 */
public final class TradeConfirmScreen extends Screen {

    private static final int BTN_W = 100;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 12;

    private final Screen parent;
    private TradeDraft draft;
    private final TradeFlow flow = new TradeFlow();
    private final LiveQuoteFlow quote = new LiveQuoteFlow();
    private ButtonWidget confirmButton;
    private boolean booked;

    public TradeConfirmScreen(Screen parent, TradeDraft draft) {
        super(Text.literal("确认"));
        this.parent = parent;
        this.draft = draft;
    }

    @Override
    protected void init() {
        clearChildren();
        if (flow.phase() != TradeFlow.Phase.SUBMITTING) {
            flow.tryOpenDraft(draft);
        }

        int y = this.height / 2 + 40;
        int cx = this.width / 2;
        confirmButton = ButtonWidget.builder(Text.literal("确认"), b -> doConfirm())
                .dimensions(cx - BTN_W - BTN_GAP / 2, y, BTN_W, BTN_H).build();
        ButtonWidget cancel = ButtonWidget.builder(Text.literal("取消"), b -> doCancel())
                .dimensions(cx + BTN_GAP / 2, y, BTN_W, BTN_H).build();
        refreshConfirmActive();
        addDrawableChild(confirmButton);
        addDrawableChild(cancel);
    }

    @Override
    public void tick() {
        super.tick();
        if (draft == null) {
            return;
        }
        LiveQuoteFlow.Phase before = quote.phase();
        quote.tick(CommandGateway.SHARED, draft.marketId());
        if (quote.phase() != before && quote.phase() == LiveQuoteFlow.Phase.READY) {
            quote.livePrice().ifPresent(live -> {
                draft = draft.withLivePrice(live);
                if (flow.phase() != TradeFlow.Phase.SUBMITTING) {
                    flow.tryOpenDraft(draft);
                }
                BcStockLog.info("下单查价：{} 实时价 {}", draft.name(), TradeDraft.money(live));
            });
        }
        flow.tick(CommandGateway.SHARED);
        if (flow.shouldClose()) {
            onFilled();
            return;
        }
        refreshConfirmActive();
    }

    private void refreshConfirmActive() {
        if (confirmButton == null) {
            return;
        }
        confirmButton.active = TradeSettings.enabled()
                && flow.pending().isPresent()
                && quote.phase() != LiveQuoteFlow.Phase.QUOTING
                && flow.phase() != TradeFlow.Phase.SUBMITTING
                && draft != null
                && draft.canAfford();
    }

    private void doConfirm() {
        if (quote.phase() == LiveQuoteFlow.Phase.QUOTING) {
            return;
        }
        if (draft != null && !draft.canAfford()) {
            return;
        }
        if (flow.phase() == TradeFlow.Phase.SUBMITTING) {
            return;
        }
        boolean accepted = flow.confirm(CommandGateway.SHARED);
        BcStockLog.info("下单确认：{} → {}", draft == null ? "-" : draft.command(), accepted);
        refreshConfirmActive();
    }

    private void onFilled() {
        if (booked) {
            return;
        }
        booked = true;
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private void doCancel() {
        if (CommandGateway.SHARED.inFlightUserTrade()) {
            return;
        }
        flow.cancel(CommandGateway.SHARED);
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        List<String> lines = TradeConfirmDialog.lines(draft, quote.phase(), flow.statusLine());
        int top = this.height / 2 - 56;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int color = (i == lines.size() - 1) ? UiPalette.MUTED : UiPalette.TEXT;
            context.drawText(this.textRenderer, line,
                    this.width / 2 - this.textRenderer.getWidth(line) / 2,
                    top + i * 12, color, false);
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            doCancel();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            doConfirm();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
