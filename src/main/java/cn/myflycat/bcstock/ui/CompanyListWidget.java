package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.data.TradeSettings;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.text.Text;

/**
 * 公司盘面列表（原版 {@link ElementListWidget}）。
 * 行内买/卖是真 {@link ButtonWidget}；点按钮不改选中态。
 *
 * <p>原版 {@code ElementListWidget.isEntrySelectionAllowed()} 恒为 false，
 * 必须覆写为 true 才会画 {@code drawSelectionHighlight}，且行体点击才会选中。
 */
public final class CompanyListWidget extends ElementListWidget<CompanyListWidget.CompanyEntry> {
    public static final int ITEM_HEIGHT = UiPalette.CELL;

    private final StockBoardScreen parent;
    private BoardColumns columns;

    public CompanyListWidget(StockBoardScreen parent, MinecraftClient client,
                             int width, int height, int y) {
        super(client, width, height, y, ITEM_HEIGHT);
        this.parent = parent;
        this.columns = new BoardColumns(width);
    }

    public void setColumns(BoardColumns columns) {
        this.columns = (columns == null) ? new BoardColumns(this.width) : columns;
    }

    public BoardColumns columns() {
        return columns;
    }

    public void rebuild(List<BoardRow> rows) {
        clearEntries();
        if (rows == null) {
            return;
        }
        for (BoardRow row : rows) {
            addEntry(new CompanyEntry(this, row));
        }
    }

    @Override
    public int getRowWidth() {
        return columns.tableWidth;
    }

    @Override
    public int getRowLeft() {
        return columns.originX;
    }

    @Override
    protected int getScrollbarX() {
        return columns.originX + columns.tableWidth + 4;
    }

    /**
     * 原版 ElementListWidget 默认 false（关掉行选中与白框）。
     * 盘面需要点行选中 + {@code drawSelectionHighlight}。
     */
    @Override
    protected boolean isEntrySelectionAllowed() {
        return true;
    }

    /** 供键盘：当前选中行在列表中的下标。 */
    public int selectedIndex() {
        CompanyEntry e = getSelectedOrNull();
        if (e == null) {
            return -1;
        }
        return children().indexOf(e);
    }

    public void selectIndex(int index) {
        if (index < 0 || index >= children().size()) {
            setSelected(null);
            return;
        }
        CompanyEntry e = children().get(index);
        setSelected(e);
        scrollTo(e);
    }

    public BoardRow selectedRow() {
        CompanyEntry e = getSelectedOrNull();
        return e == null ? null : e.row;
    }

    public static final class CompanyEntry extends ElementListWidget.Entry<CompanyEntry> {
        private final CompanyListWidget list;
        private final BoardRow row;
        private final ButtonWidget buy;
        private final ButtonWidget sell;
        private final List<ClickableWidget> children;

        CompanyEntry(CompanyListWidget list, BoardRow row) {
            this.list = list;
            this.row = row;
            boolean tradeOn = TradeSettings.enabled();
            this.buy = ButtonWidget.builder(Text.literal("买"), b -> list.parent.onBuy(row))
                    .dimensions(0, 0, 32, 16).build();
            this.sell = ButtonWidget.builder(Text.literal("卖"), b -> list.parent.onSell(row))
                    .dimensions(0, 0, 32, 16).build();
            this.buy.active = TradeButtonState.buyActive(tradeOn, row.bankrupt());
            this.sell.active = TradeButtonState.sellActive(tradeOn, row.held());
            this.children = List.of(buy, sell);
        }

        public BoardRow row() {
            return row;
        }

        /**
         * 点买/卖：按钮已处理，对列表返回 false，避免 ParentElement.setFocused→setSelected。
         * 点行体：返回 true，触发列表 setFocused→setSelected + 白框。
         */
        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
            for (ClickableWidget child : children) {
                if (child.mouseClicked(click, doubled)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
            BoardColumns cols = list.columns;
            TextRenderer font = list.client.textRenderer;
            int contentX = getContentX();
            int contentY = getContentY();
            int contentH = getContentHeight();
            // 选中：交给原版 EntryListWidget.drawSelectionHighlight，不叠第二层金框。
            // hover：半透明白（原版列表本身不画行 hover）。
            if (hovered) {
                context.fill(contentX, contentY, contentX + cols.tableWidth, contentY + contentH,
                        UiPalette.HOVER);
            }
            for (BoardColumns.Col col : cols.columns) {
                if (col == BoardColumns.Col.ACTION) {
                    continue;
                }
                String text = row.text(col);
                int color = row.color(col);
                int cx = cols.contentColumnX(contentX, col);
                int ty = contentY + (contentH - 9) / 2;
                if (col.numeric) {
                    int tw = font.getWidth(text);
                    context.drawText(font, text, cx + col.width - tw - 2, ty, color, false);
                } else {
                    context.drawText(font, text, cx + 2, ty, color, false);
                }
            }
            BoardColumns.Rect action = new BoardColumns.Rect(
                    cols.contentColumnX(contentX, BoardColumns.Col.ACTION),
                    contentY, BoardColumns.Col.ACTION.width, contentH);
            int btnY = contentY + (contentH - 16) / 2;
            buy.setPosition(action.x() + 2, btnY);
            sell.setPosition(action.x() + 36, btnY);
            buy.render(context, mouseX, mouseY, delta);
            sell.render(context, mouseX, mouseY, delta);
        }

        @Override
        public List<? extends Element> children() {
            return children;
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return children;
        }

        @Override
        public void forEachChild(java.util.function.Consumer<ClickableWidget> consumer) {
            children.forEach(consumer);
        }
    }
}
