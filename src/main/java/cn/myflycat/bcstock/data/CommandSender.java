package cn.myflycat.bcstock.data;

/**
 * 把一条聊天命令送出去。实现放在客户端入口，本接口<b>不引用</b>
 * {@code net.minecraft}，所以 {@link CommandGateway} 能离线测。
 *
 * <p>{@code command} <b>不带前导 {@code /}</b>，和
 * {@code ClientPlayNetworkHandler.sendChatCommand} 的签名一致
 * （Yarn {@code method_45730}；传 {@code "invest portfolio"} 不是
 * {@code "/invest portfolio"}）。
 */
@FunctionalInterface
public interface CommandSender {

    /**
     * @return {@code true} 表示已经交给客户端发出去；{@code false} 表示
     *         现在发不了（没进服 / 没有 player）。发不了就不要进入单飞，
     *         也不要记 15 分钟冷却——人还没上线，不算「发过」。
     */
    boolean send(String command);
}
