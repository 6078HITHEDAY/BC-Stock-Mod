package cn.myflycat.bcstock.ui;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.data.BcStockSettings;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.data.CompanyView;
import cn.myflycat.bcstock.data.DegradePolicy;
import cn.myflycat.bcstock.data.FloorCache;
import cn.myflycat.bcstock.data.FloorView;
import cn.myflycat.bcstock.data.HoldingView;
import cn.myflycat.bcstock.decision.DecisionEngine;
import cn.myflycat.bcstock.data.MarketDataAge;
import cn.myflycat.bcstock.data.SnapshotStore;
import cn.myflycat.bcstock.data.StockSnapshot;
import cn.myflycat.bcstock.data.TradeSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * 盘面主屏：原版 {@link CompanyListWidget} + 底栏按钮。
 * 详情 / 数量 / 确认走独立 Screen；返回时复用本实例并经 {@link BoardNavState} 恢复选中/滚动。
 * 渲染路径零发包。
 */
public final class StockBoardScreen extends Screen {

    public static final String TITLE_TEXT = "帕拉伦股市";

    private static final int HEADER_TOP = UiPalette.TOP_BAR_H + 8;
    private static final int LIST_TOP = HEADER_TOP + UiPalette.HEADER_H;

    private BoardInteraction.Sort sort = BoardInteraction.Sort.DEFAULT;
    private BoardInteraction.Filter filter = BoardInteraction.defaultFilter(BcStockSettings.showBankrupt());
    private BoardColumns columns = new BoardColumns(320);
    private List<BoardRow> visibleRows = List.of();
    /** 可见行里的破产家数（计数文案用；只在行变了时重算，渲染路径不现数）。 */
    private int bankruptVisible;
    /** 破产行一路连到末尾时的首行下标；没有破产行、或破产行插在中间 → -1（不画分隔线）。 */
    private int bankruptBlockStart = -1;
    private CompanyListWidget list;
    private final BoardNavState nav = new BoardNavState();
    private ButtonWidget sortButton;
    private ButtonWidget filterButton;

    public StockBoardScreen() {
        super(Text.literal(TITLE_TEXT));
    }

    /** 测试用：当前导航快照（选中/滚动）。 */
    BoardNavState navState() {
        return nav;
    }

    @Override
    protected void init() {
        clearChildren();
        // 注意：setScreen 返回本实例时会再调 init()。排序/筛选是字段，不重置；
        // 选中/滚动在 list 重建后从 nav 恢复（见 restoreNavAfterRebuild）。
        columns = new BoardColumns(this.width);
        int by = bottomButtonY();
        sortButton = ButtonWidget.builder(Text.literal(BottomBarLabels.sortText(sort)), b -> cycleSort())
                .dimensions(columns.originX, by, BottomBarLabels.SORT_BTN_W, BottomBarLabels.BTN_H).build();
        filterButton = ButtonWidget.builder(Text.literal(BottomBarLabels.filterText(filter)), b -> cycleFilter())
                .dimensions(columns.originX + BottomBarLabels.FILTER_BTN_X, by,
                        BottomBarLabels.FILTER_BTN_W, BottomBarLabels.BTN_H).build();
        // 先加底栏：1.21.11 hoveredElement 按 children 顺序取第一个 isMouseOver。
        // 列表是全宽，若先加列表会把底栏点击吞掉（连点无反应 / 偶发才点上）。
        addDrawableChild(sortButton);
        addDrawableChild(filterButton);

        int listHeight = Math.max(CompanyListWidget.ITEM_HEIGHT,
                this.height - LIST_TOP - UiPalette.BOTTOM_BAR_H);
        list = new CompanyListWidget(this, this.client, this.width, listHeight, LIST_TOP);
        list.setColumns(columns);
        addDrawableChild(list);
        rebuildRows();
        restoreNavAfterRebuild();
        logFilterState();
    }

    /** 从 {@link #nav} 恢复选中行与滚动；init 重建列表后必须调用。 */
    void restoreNavAfterRebuild() {
        if (list == null) {
            return;
        }
        int idx = BoardNavState.clampIndex(nav.selectedIndex(), visibleRows.size());
        if (idx >= 0) {
            list.selectIndex(idx);
        }
        list.setScrollY(nav.scrollY());
    }

    void rememberNav() {
        if (list != null) {
            nav.save(list.selectedIndex(), list.getScrollY());
        }
    }

    @Override
    protected void refreshWidgetPositions() {
        if (list == null) {
            return;
        }
        columns = new BoardColumns(this.width);
        list.setColumns(columns);
        int listHeight = Math.max(CompanyListWidget.ITEM_HEIGHT,
                this.height - LIST_TOP - UiPalette.BOTTOM_BAR_H);
        list.position(this.width, listHeight, LIST_TOP);
        if (sortButton != null) {
            int by = bottomButtonY();
            sortButton.setPosition(columns.originX, by);
            filterButton.setPosition(columns.originX + BottomBarLabels.FILTER_BTN_X, by);
        }
    }

    private int bottomButtonY() {
        return this.height - UiPalette.BOTTOM_BAR_H
                + (UiPalette.BOTTOM_BAR_H - BottomBarLabels.BTN_H) / 2;
    }

    /**
     * 底栏必须先于列表处理。原版 {@code ParentElement.mouseClicked} 只问
     * {@code hoveredElement}（第一个 isMouseOver 的 child），列表全宽时
     * 点到底栏也会打到列表空区，按钮永远收不到。
     * {@code doubled} 是原版连点标记，再 cycle 会跳档，看起来像没反应或自己跳。
     */
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (sortButton != null && sortButton.isMouseOver(click.x(), click.y())) {
            if (doubled) {
                return true;
            }
            return sortButton.mouseClicked(click, false);
        }
        if (filterButton != null && filterButton.isMouseOver(click.x(), click.y())) {
            if (doubled) {
                return true;
            }
            return filterButton.mouseClicked(click, false);
        }
        return super.mouseClicked(click, doubled);
    }

    private void rebuildRows() {
        visibleRows = List.of();
        // 先归零再刷新：新快照也是空的话，refreshRowsIfNeeded 会因为「没变」直接跳过，
        // 破产家数不能留着上一次的值（否则会写出「共 0 家（含 43 家已破产）」）。
        recountVisible();
        refreshRowsIfNeeded();
    }

    private void refreshRowsIfNeeded() {
        StockSnapshot snap = SnapshotStore.SHARED.get();
        List<BoardRow> next = BoardInteraction.apply(toRows(snap), sort, filter);
        if (!next.equals(visibleRows)) {
            String keepName = list != null && list.selectedRow() != null ? list.selectedRow().name() : null;
            visibleRows = next;
            recountVisible();
            if (list != null) {
                list.rebuild(visibleRows);
                if (keepName != null) {
                    for (int i = 0; i < visibleRows.size(); i++) {
                        if (keepName.equals(visibleRows.get(i).name())) {
                            list.selectIndex(i);
                            break;
                        }
                    }
                }
            }
        }
        // 2026-09-28：文案无条件跟档位走。原先这段塞在「列表没变就 return」之后，
        // 两档结果相同时按下去按钮文字停住，看着就是「按了还是原来那个」。
        refreshControlLabels();
    }

    /**
     * 行数变了才重算：破产家数（计数文案用）与破产块起点（分隔线用）。
     * 渲染路径只读这两个字段，不现数。
     */
    private void recountVisible() {
        int bankrupt = 0;
        int start = -1;
        for (int i = 0; i < visibleRows.size(); i++) {
            if (visibleRows.get(i).bankrupt()) {
                bankrupt++;
                if (start < 0) {
                    start = i;
                }
            }
        }
        bankruptVisible = bankrupt;
        // 只有破产行一路连到末尾（默认序就是这种：前 11 家活的，后面 43 家破产）
        // 才画分隔线；换成按涨跌/风险/盈亏排，破产行会插在中间，那时候画线只会误导。
        bankruptBlockStart = (start >= 0 && bankrupt == visibleRows.size() - start) ? start : -1;
    }

    private void refreshControlLabels() {
        if (sortButton == null) {
            return;
        }
        sortButton.setMessage(Text.literal(BottomBarLabels.sortText(sort)));
        if (filterButton != null) {
            filterButton.setMessage(Text.literal(BottomBarLabels.filterText(filter)));
        }
    }

    @Override
    public void tick() {
        refreshRowsIfNeeded();
    }

    void onBuy(BoardRow row) {
        openQty(TradeDraft.Side.BUY, row);
    }

    void onSell(BoardRow row) {
        openQty(TradeDraft.Side.SELL, row);
    }

    private void openQty(TradeDraft.Side side, BoardRow row) {
        if (!TradeSettings.enabled() || row == null || this.client == null) {
            return;
        }
        if (side == TradeDraft.Side.BUY && row.bankrupt()) {
            return;
        }
        if (side == TradeDraft.Side.SELL && !row.held()) {
            return;
        }
        rememberNav();
        StockSnapshot snap = SnapshotStore.SHARED.get();
        CompanyView company = snap.companyOf(row.name());
        HoldingView holding = snap.holdingOf(row.name());
        this.client.setScreen(new QtyDialogScreen(this, side, company, holding, snap.wallet(),
                draft -> this.client.setScreen(new TradeConfirmScreen(this, draft))));
    }

    private void openDetail() {
        BoardRow sel = list == null ? null : list.selectedRow();
        if (sel == null || this.client == null) {
            return;
        }
        rememberNav();
        this.client.setScreen(new DetailScreen(this, sel));
    }

    @Override
    public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        StockSnapshot snap = SnapshotStore.SHARED.get();
        DegradePolicy policy = DegradePolicy.of(snap, CommandGateway.SHARED);
        graphics.drawText(this.textRenderer, TITLE_TEXT, columns.originX, 8, UiPalette.TEXT, false);
        String bal = BoardRenderer.walletText(snap.wallet());
        graphics.drawText(this.textRenderer, bal,
                columns.originX + columns.tableWidth - this.textRenderer.getWidth(bal),
                8, UiPalette.TEXT, false);

        int hx = columns.originX;
        int hy = HEADER_TOP + 2;
        for (BoardColumns.Col col : columns.columns) {
            int color = UiPalette.MUTED;
            if (col.numeric) {
                int tw = this.textRenderer.getWidth(col.header);
                graphics.drawText(this.textRenderer, col.header, hx + col.width - tw - 2, hy, color, false);
            } else {
                graphics.drawText(this.textRenderer, col.header, hx + 2, hy, color, false);
            }
            hx += col.width;
        }

        // 2026-09-28：空档给一句人话；「全部」里那 43 家破产行之前画一条 1px 线，
        // 免得「全部 54 家」和「排除破产 11 家」在屏幕上看起来一模一样（都是前 11 家活的）。
        if (visibleRows.isEmpty()) {
            String hint = BottomBarLabels.emptyHint(filter);
            int hw = this.textRenderer.getWidth(hint);
            graphics.drawText(this.textRenderer, hint,
                    columns.originX + (columns.tableWidth - hw) / 2, LIST_TOP + 6,
                    UiPalette.MUTED, false);
        } else if (bankruptBlockStart > 0 && list != null) {
            int y = list.getRowTop(bankruptBlockStart);
            if (y >= LIST_TOP && y <= this.height - UiPalette.BOTTOM_BAR_H) {
                graphics.fill(columns.originX, y, columns.originX + columns.tableWidth, y + 1,
                        UiPalette.DISABLED);
            }
        }

        drawBottomStatus(graphics, policy);
    }

    private void drawBottomStatus(DrawContext g, DegradePolicy policy) {
        String age = MarketDataAge.formatLabel(System.currentTimeMillis());
        String prefix = BottomBarLabels.prefix(policy, columns.narrow, age);
        String tradeHint = BottomBarLabels.tradeHint(TradeSettings.enabled());
        int ty = this.height - UiPalette.BOTTOM_BAR_H + 5;
        int pageRight = columns.originX + columns.tableWidth;
        int idx = (list == null) ? -1 : list.selectedIndex();
        // 计数说人话：共几家 + 这一档为什么是这么多。右端挤不下就退成短写法。
        String count = BottomBarLabels.countText(filter, visibleRows.size(), bankruptVisible, idx);
        int countW = this.textRenderer.getWidth(count);
        if (!BottomBarLabels.countFits(columns.originX, countW, pageRight)) {
            count = BottomBarLabels.countTextShort(visibleRows.size());
            countW = this.textRenderer.getWidth(count);
        }
        boolean countDrawn = BottomBarLabels.countFits(columns.originX, countW, pageRight);
        // 交易提示 / 计数占用右端；状态文案不得侵入
        int rightLimit = countDrawn ? pageRight - countW - 8 : pageRight - 40;
        if (!tradeHint.isEmpty()) {
            int hintW = this.textRenderer.getWidth(tradeHint);
            int hintX = rightLimit - hintW;
            if (hintX > columns.originX + BottomBarLabels.STATUS_AFTER_FILTER_X) {
                g.drawText(this.textRenderer, tradeHint, hintX, ty, UiPalette.MUTED, false);
                rightLimit = hintX - 8;
            }
        }
        if (!prefix.isEmpty()) {
            int tw = this.textRenderer.getWidth(prefix);
            if (BottomBarLabels.statusFits(columns.originX, tw, rightLimit)) {
                // 2026-09-27：筛选右侧 + 原版白（不再用 WARN 黄）
                g.drawText(this.textRenderer, prefix,
                        BottomBarLabels.statusTextX(columns.originX), ty, UiPalette.TEXT, false);
            }
        }
        if (countDrawn) {
            g.drawText(this.textRenderer, count, pageRight - countW, ty, UiPalette.MUTED, false);
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            openDetail();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            moveSelection(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN) {
            moveSelection(1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            moveSelection(-Math.max(1, listHeightRows()));
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            moveSelection(Math.max(1, listHeightRows()));
            return true;
        }
        if (key == GLFW.GLFW_KEY_S) {
            cycleSort();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F) {
            cycleFilter();
            return true;
        }
        return super.keyPressed(input);
    }

    private int listHeightRows() {
        int h = Math.max(CompanyListWidget.ITEM_HEIGHT, this.height - LIST_TOP - UiPalette.BOTTOM_BAR_H);
        return Math.max(1, h / CompanyListWidget.ITEM_HEIGHT);
    }

    private void moveSelection(int delta) {
        if (list == null || visibleRows.isEmpty()) {
            return;
        }
        int cur = list.selectedIndex();
        if (cur < 0) {
            cur = 0;
        } else {
            cur = Math.max(0, Math.min(visibleRows.size() - 1, cur + delta));
        }
        list.selectIndex(cur);
        rememberNav();
    }

    private void cycleSort() {
        sort = sort.next();
        rebuildRows();
        if (list != null) {
            list.selectIndex(-1);
            list.setScrollY(0);
        }
        rememberNav();
    }

    private void cycleFilter() {
        filter = filter.next();
        rebuildRows();
        if (list != null) {
            list.selectIndex(-1);
            list.setScrollY(0);
        }
        rememberNav();
        logFilterState();
    }

    private void logFilterState() {
        long bankrupt = 0;
        for (BoardRow r : visibleRows) {
            if (r.bankrupt()) {
                bankrupt++;
            }
        }
        BcStockLog.info("账本 筛选={} showBankrupt={} 可见{}行/破产{}",
                filter.label, BcStockSettings.showBankrupt(), visibleRows.size(), bankrupt);
    }

    static List<BoardRow> toRows(StockSnapshot snap) {
        List<BoardRow> rows = new ArrayList<>();
        if (snap == null) {
            return rows;
        }
        for (CompanyView c : snap.companies()) {
            HoldingView holding = snap.holdingOf(c.name());
            FloorView floor = FloorCache.SHARED.ofName(c.name()).orElse(null);
            DecisionEngine.Advice advice = DecisionEngine.advise(
                    c, holding, floor, BcStockSettings.decisionPreset());
            rows.add(BoardRow.from(c, holding, advice));
        }
        return rows;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
