package cn.myflycat.bcstock.mixin;

import cn.myflycat.bcstock.BcStockLog;
import cn.myflycat.bcstock.ServerGate;
import cn.myflycat.bcstock.data.CommandGateway;
import cn.myflycat.bcstock.probe.InvestProbe;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 唯一的 Mixin：把「服务端推来一个槽位更新」这件事告诉 {@link InvestProbe}。
 *
 * <h2>为什么必须 Mixin</h2>
 * Fabric API 没有「容器槽位变化」事件（{@code ScreenEvents} 只有开屏/渲染/关闭，没有内容更新），
 * 而容器内容是在开屏包<b>之后</b>由一串 {@code ScreenHandlerSlotUpdateS2CPacket} 逐个送到的。
 * 想在不轮询的前提下知道「数据到齐了」，只能挂在收包这一层。
 *
 * <h2>为什么是 TAIL 注入而不是 MixinExtras 的 @WrapOperation</h2>
 * MixinExtras 的价值在于替掉脆弱的 {@code @Redirect} / {@code @ModifyVariable} 链
 * （典型场景是 M1 的 GUI 覆盖层要去改渲染调用）。这里要的是「方法体跑完后加一句话」，
 * 没有可包装的调用点、也没有要改写的返回值——{@code @Inject(at = TAIL)} 就是这件事的标准工具，
 * 硬套 {@code @WrapOperation} 只会把简单事情写复杂。
 * MixinExtras（Loom 1.17.21 自带 <b>0.5.5</b>，不是 PLAN.md 里记的 0.5.0-rc.2）仍在编译期与产物里，
 * M1 需要时直接用。
 *
 * <h2>线程</h2>
 * {@code ClientPlayNetworkHandler} 的方法跑在<b>主线程</b>（包在 netty 线程上只做 decode，
 * 真正的处理经 {@code client.execute()} 派回主线程）。所以这里可以安全地只置一个标志位，
 * 不需要额外同步——{@link InvestProbe#onSlotPacket} 本身也只碰主线程状态。
 *
 * <h2>只读</h2>
 * 这里只读 {@code packet.getSyncId()} 与 {@code packet.getSlot()} 两个数字，不碰 ItemStack，
 * 更不发任何点击。执行层（M4，PLAN.md §5 已定本期不做）才会碰 {@code handleInventoryMouseClick}。
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    /**
     * 收到槽位更新包后，通知探测器「有新数据，重新开始稳定计时」。
     *
     * <p>方法名前缀 {@code bcstock$} 是 Mixin 的命名约定，避免和 MC 自己的方法或别的 mod 撞名。
     * 处理方法是 {@code private}：注入不需要被外部调用，越小可见性越好。
     *
     * <p>不检查 {@code packet.getStack()} 是否为空——空格子也是有效信息（公司列表里
     * #30–#34 就是空的），交由 {@link InvestProbe} 统一判断。
     *
     * @param packet 槽位更新包，含 syncId / 槽位下标 / 新 ItemStack
     * @param ci     注入回调，这里不取消原方法（探测不该改变游戏行为）
     */
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void bcstock$onSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        if (!ServerGate.active()) {
            return;
        }
        InvestProbe.onSlotPacket(packet.getSyncId(), packet.getSlot());
    }

    /**
     * 收到「容器内容整包」后，通知探测器「初始内容到了」。
     *
     * <p>为什么除了逐格包还要挂这个：{@code OpenScreenS2CPacket} 只带标题不带内容，而内容分两条路来——
     * 开屏后的<b>初始内容</b>走 {@link InventoryS2CPacket} 整包（{@code onInventory}），
     * 之后的<b>单格变动</b>才走 {@code ScreenHandlerSlotUpdateS2CPacket}。
     * 只挂后者的话，内容包比开屏包晚到 ≥2 tick 时，第一次快照会读到一屏空槽且不会补 dump。
     *
     * <p>实测签名（javap 核过命名映射）：{@code InventoryS2CPacket} 是 record，
     * {@code syncId() / revision() / contents() / cursorStack()}；
     * {@code ClientPlayNetworkHandler.onInventory(InventoryS2CPacket)} 存在。
     *
     * @param packet 整包内容，这里只读 {@code syncId} 一个数字，不碰 {@code contents()} 里任何 ItemStack
     * @param ci     注入回调，不取消原方法
     */
    @Inject(method = "onInventory", at = @At("TAIL"))
    private void bcstock$onInventoryContents(InventoryS2CPacket packet, CallbackInfo ci) {
        if (!ServerGate.active()) {
            return;
        }
        InvestProbe.onInventoryContents(packet.syncId());
    }

    /**
     * 收系统聊天（命令回执走这里）。
     *
     * <p>注入目标用 Yarn 名 {@code onGameMessage}（中介名 {@code method_43596}），
     * 包是 {@code GameMessageS2CPacket}（{@code class_7439}），字段 {@code content}。
     * 与已有的 {@code onInventory} 同一套写法，Loom 会 remap。
     *
     * <p>异常必须吞掉——排查用的代码不该把游戏带崩。
     */
    @Inject(method = "onGameMessage", at = @At("TAIL"))
    private void bcstock$onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        if (!ServerGate.active()) {
            return;
        }
        try {
            CommandGateway.SHARED.onChatLine(packet.content().getString());
        } catch (Throwable t) {
            BcStockLog.warn("聊天注入出错（已吞，不影响游戏）：{}", t.toString());
        }
    }
}
