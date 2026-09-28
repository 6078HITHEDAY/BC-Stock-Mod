package cn.myflycat.bcstock.probe;

import java.util.List;
import net.minecraft.item.ItemStack;

/**
 * 容器里单个格子的只读快照。
 *
 * <p>{@code index} 和 {@code slotId} 都留着，因为这是 <b>M4 执行层最要紧的一对数字</b>：
 * {@code handleInventoryMouseClick(containerId, slotIndex, ...)} 收的是<b>槽位在
 * {@code ScreenHandler.slots} 里的下标</b>，而 {@code Slot.id} 是服务端认的编号。
 * vanilla 里两者通常相等，但<b>没有保证</b>——一旦不等，展示层重排格子后就会买错标的。
 * 所以这里两个都记下来，不相等时由 {@link InvestProbe} 打警告。
 *
 * @param index   在 {@code ScreenHandler.slots} 里的下标（点击用这个）
 * @param slotId  {@link net.minecraft.screen.slot.Slot#id}
 * @param empty   格子是否为空
 * @param itemName {@code ItemStack.getName().getString()}，形如 {@code 药水 - 联邦健保 [ ↑ 1.18% ]}
 * @param count   堆叠数
 * @param tooltip 完整 tooltip 文本（name 之外的 lore 行），已 toString 去掉颜色码
 */
public record ProbedSlot(
        int index,
        int slotId,
        boolean empty,
        String itemName,
        int count,
        List<String> tooltip) {

    public ProbedSlot {
        tooltip = List.copyOf(tooltip);
    }

    /** 从 ItemStack 造快照。{@code tooltip} 由调用方负责生成（要 TooltipContext，比较重）。 */
    public static ProbedSlot of(int index, int slotId, ItemStack stack, List<String> tooltip) {
        return new ProbedSlot(
                index,
                slotId,
                stack.isEmpty(),
                stack.isEmpty() ? "" : stack.getName().getString(),
                stack.getCount(),
                stack.isEmpty() ? List.of() : tooltip);
    }

    /** 一行摘要，日志用。 */
    public String toLogLine() {
        if (empty) {
            return "#" + index + " (空) slotId=" + slotId;
        }
        return "#" + index + " x" + count + " " + itemName + " slotId=" + slotId;
    }
}
