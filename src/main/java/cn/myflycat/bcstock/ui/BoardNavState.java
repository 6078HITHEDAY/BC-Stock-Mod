package cn.myflycat.bcstock.ui;

/**
 * 盘面导航状态（选中行 / 滚动）。纯数据，不 import {@code net.minecraft}。
 *
 * <p>打开详情 / 数量 / 确认屏再返回时，{@link StockBoardScreen#init()} 会重建子控件；
 * 必须从这里恢复，否则列表跳回顶部。
 */
public final class BoardNavState {

    private int selectedIndex = -1;
    private double scrollY;

    public void save(int selectedIndex, double scrollY) {
        this.selectedIndex = selectedIndex;
        this.scrollY = Math.max(0.0, scrollY);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public double scrollY() {
        return scrollY;
    }

    /** 测试：模拟「记下 → 假装 init 重建 → 读回」不丢。 */
    public BoardNavState copy() {
        BoardNavState n = new BoardNavState();
        n.save(selectedIndex, scrollY);
        return n;
    }

    /**
     * 返回盘面后要把下标夹在可见行内。
     * {@code rowCount==0} 或越界 → {@code -1}（无选中）。
     */
    public static int clampIndex(int selectedIndex, int rowCount) {
        if (rowCount <= 0 || selectedIndex < 0 || selectedIndex >= rowCount) {
            return -1;
        }
        return selectedIndex;
    }
}
