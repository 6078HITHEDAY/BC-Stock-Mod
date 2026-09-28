package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.BcStockSettings;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.data.TradeSettings;
import cn.myflycat.bcstock.data.WalletView;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 数量选择：独立 Screen，全部原版控件。
 * 选完数量回调上层；本屏<strong>不发命令</strong>。
 */
public final class QtyDialogScreen extends Screen {

    private static final int BTN_W = 160;
    private static final int BTN_H = 20;
    private static final int FIELD_W = 160;
    private static final int FIELD_H = 20;
    private static final int GAP = 4;

    private final Screen parent;
    private final TradeDraft.Side side;
    private final CompanyView company;
    private final HoldingView holding;
    private final WalletView wallet;
    private final Consumer<TradeDraft> onDraftReady;

    private boolean customMode;
    private TextFieldWidget field;
    private ButtonWidget okButton;
    private String error;

    public QtyDialogScreen(Screen parent, TradeDraft.Side side, CompanyView company,
                           HoldingView holding, WalletView wallet,
                           Consumer<TradeDraft> onDraftReady) {
        super(Text.literal(side == TradeDraft.Side.SELL ? "卖出数量" : "买入数量"));
        this.parent = parent;
        this.side = side;
        this.company = company;
        this.holding = holding;
        this.wallet = wallet;
        this.onDraftReady = onDraftReady;
    }

    @Override
    protected void init() {
        clearChildren();
        customMode = false;
        field = null;
        okButton = null;
        error = null;
        if (!TradeSettings.enabled()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> closeBack())
                    .dimensions(centerX() - BTN_W / 2, this.height / 2, BTN_W, BTN_H).build());
            return;
        }
        buildChoiceButtons();
    }

    private void buildChoiceButtons() {
        List<QtyDialogLogic.Choice> choices = QtyDialogLogic.choices(side);
        int top = this.height / 2 - (choices.size() * (BTN_H + GAP)) / 2;
        int x = centerX() - BTN_W / 2;
        for (int i = 0; i < choices.size(); i++) {
            QtyDialogLogic.Choice choice = choices.get(i);
            int y = top + i * (BTN_H + GAP);
            addDrawableChild(ButtonWidget.builder(Text.literal(choice.label), b -> onChoice(choice))
                    .dimensions(x, y, BTN_W, BTN_H).build());
        }
    }

    private void onChoice(QtyDialogLogic.Choice choice) {
        if (choice == QtyDialogLogic.Choice.CUSTOM) {
            openCustom();
            return;
        }
        long held = (holding == null) ? 0L : holding.shares();
        OptionalInt qty = QtyDialogLogic.resolve(choice, side, held);
        if (qty.isEmpty()) {
            closeBack();
            return;
        }
        finishWithQty(qty.getAsInt());
    }

    private void openCustom() {
        customMode = true;
        clearChildren();
        error = null;
        int x = centerX() - FIELD_W / 2;
        int y = this.height / 2 - 20;
        field = new TextFieldWidget(this.textRenderer, x, y, FIELD_W, FIELD_H, Text.literal(""));
        field.setMaxLength(6);
        field.setTextPredicate(s -> s == null || s.isEmpty() || s.chars().allMatch(Character::isDigit));
        int presetQty = QtyDialogLogic.defaultCustomQty(side, BcStockSettings.decisionPreset());
        if (presetQty > 0) {
            field.setText(Integer.toString(presetQty));
        }
        field.setPlaceholder(Text.literal("股数"));
        field.setChangedListener(t -> refreshOkActive());
        addDrawableChild(field);
        setFocused(field);
        field.setFocused(true);

        okButton = ButtonWidget.builder(Text.literal("确定"), b -> submitCustom())
                .dimensions(x, y + FIELD_H + GAP, FIELD_W, BTN_H).build();
        addDrawableChild(okButton);
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> closeBack())
                .dimensions(x, y + 2 * (FIELD_H + GAP), FIELD_W, BTN_H).build());
        refreshOkActive();
    }

    private void refreshOkActive() {
        if (okButton == null) {
            return;
        }
        String text = (field == null) ? "" : field.getText();
        okButton.active = QtyDialogLogic.parseCustom(text).ok();
    }

    private void submitCustom() {
        String text = (field == null) ? "" : field.getText();
        QtyDialogLogic.ParseResult parsed = QtyDialogLogic.parseCustom(text);
        if (!parsed.ok()) {
            error = parsed.error();
            refreshOkActive();
            return;
        }
        finishWithQty(parsed.qty());
    }

    private void finishWithQty(int qty) {
        Optional<TradeDraft> draft = TradeDraft.create(company, side, qty, wallet, holding);
        if (draft.isEmpty()) {
            closeBack();
            return;
        }
        if (onDraftReady != null) {
            onDraftReady.accept(draft.get());
        }
    }

    private void closeBack() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    private int centerX() {
        return this.width / 2;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawText(this.textRenderer, this.title, centerX() - this.textRenderer.getWidth(this.title) / 2,
                this.height / 2 - 60, UiPalette.TEXT, false);
        if (!TradeSettings.enabled()) {
            context.drawText(this.textRenderer, "交易未启用",
                    centerX() - this.textRenderer.getWidth("交易未启用") / 2,
                    this.height / 2 - 40, UiPalette.MUTED, false);
        }
        if (customMode && error != null && !error.isBlank()) {
            context.drawText(this.textRenderer, error,
                    centerX() - this.textRenderer.getWidth(error) / 2,
                    this.height / 2 - 36, UiPalette.DOWN, false);
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            closeBack();
            return true;
        }
        if (customMode && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            submitCustom();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // 自己处理 Esc，回到父屏而非关游戏菜单链
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** 测试用：当前是否自定义模式。 */
    boolean customModeForTest() {
        return customMode;
    }
}
