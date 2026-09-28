package cn.myflycat.bcstock.probe;

import java.util.List;

/**
 * 一次容器 dump 的完整只读快照。
 *
 * <p><b>只保留容器区（服务端给的公开盘面）的槽位内容</b>；玩家背包那 36 格只留计数，
 * 不留内容——背包里是个人装备，写进日志就违反了「往外发日志前先看一眼」那条红线
 * （{@code data/gui/} 的旧日志就是被 MCC 连背包一起 dump 才变长的）。
 *
 * <p>容器区与背包的切分<b>不靠格数猜</b>：靠 {@code Slot.inventory} 是不是玩家背包这一身份判断。
 * 旧脚本「公司列表 45 格却输出 58 条」的坑就是这么来的，这里从结构上避开。
 *
 * @param kind               识别出的容器种类
 * @param title              容器标题原文
 * @param syncId             {@code ScreenHandler.syncId}
 * @param totalSlots         {@code ScreenHandler.slots.size()}（含玩家背包）
 * @param containerSlotCount 容器区格数（公司列表应为 45）
 * @param playerSlotCount    玩家背包格数（应为 36）
 * @param playerNonEmptyCount 玩家背包里非空的数量（只看数量，不看内容）
 * @param slots              容器区的槽位快照，按 index 升序
 * @param companies          容器区里认出来的公司行
 * @param slotIdMismatch     index 与 Slot.id 不一致的槽位数量（正常情况下 0；不为 0 说明点击层要格外小心）
 * @param truncated          是否因为超过 {@link InvestProbe#MAX_LOGGED_SLOTS} 而没有逐格打印
 */
public record ProbedContainer(
        StockContainerKind kind,
        String title,
        int syncId,
        int totalSlots,
        int containerSlotCount,
        int playerSlotCount,
        int playerNonEmptyCount,
        List<ProbedSlot> slots,
        List<CompanyRow> companies,
        int slotIdMismatch,
        boolean truncated) {

    public ProbedContainer {
        slots = List.copyOf(slots);
        companies = List.copyOf(companies);
    }

    /** 容器区里非空的格数。 */
    public int nonEmptyContainerSlots() {
        int n = 0;
        for (ProbedSlot s : slots) {
            if (!s.empty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 内容签名。两次 dump 签名相同 → 盘面没变，可以不打第二遍日志。
     *
     * <p>刻意<b>不</b>包含 {@code syncId}：同一个容器重开时 syncId 会变（服务端递增分配），
     * 但内容一样时我们仍然希望日志里能看出「重开过」——所以重开由调用方强制 dump。
     */
    public long signature() {
        StringBuilder sb = new StringBuilder();
        for (ProbedSlot s : slots) {
            sb.append(s.toLogLine()).append('\n');
            for (String line : s.tooltip()) {
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString().hashCode();
    }

    /** 日志抬头那一行。 */
    public String header() {
        return "标题=「" + title + "」 识别=" + kind + " syncId=" + syncId
                + " 总格数=" + totalSlots + "（容器区 " + containerSlotCount
                + " + 背包 " + playerSlotCount + "）"
                + " 背包非空=" + playerNonEmptyCount + "（内容不打印）";
    }
}
