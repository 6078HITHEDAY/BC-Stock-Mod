package cn.myflycat.bcstock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 只在帕拉伦股市服（{@code mc.bilicraft.com:25577}）打开副作用。
 *
 * <p>不依赖 {@code net.minecraft}，地址匹配可离线测。
 * 用玩家填写的服务器地址判断，不看 DNS 解析后的 IP。
 * 单机（无 {@code ServerInfo}）与其他服一律关闭。
 *
 * <p>{@link #generation()} 在启用 / 关闭时都会递增，用来作废断线后仍在飞的进服 API 线程。
 */
public final class ServerGate {

    public static final String TARGET_HOST = "mc.bilicraft.com";
    public static final int TARGET_PORT = 25577;
    /** 原版省略端口时的默认值；省略不等于目标端口。 */
    public static final int VANILLA_DEFAULT_PORT = 25565;

    private static volatile boolean active;
    private static final AtomicLong GENERATION = new AtomicLong();

    private ServerGate() {
    }

    public static boolean active() {
        return active;
    }

    /** 当前会话代数。进服 API 线程应捕获并在落盘前用 {@link #isCurrent} 再验。 */
    public static long generation() {
        return GENERATION.get();
    }

    /** {@code active} 且代数未变（未断线、未换服）。 */
    public static boolean isCurrent(long sessionGeneration) {
        return active && GENERATION.get() == sessionGeneration;
    }

    /**
     * 启用并换新代数。
     *
     * @return 本会话代数，交给后台线程做 {@link #isCurrent} 校验
     */
    public static long activate() {
        long gen = GENERATION.incrementAndGet();
        active = true;
        return gen;
    }

    /** 测试用；生产路径用 {@link #activate()} / {@link #clear()}。 */
    public static void setActive(boolean value) {
        if (value) {
            activate();
        } else {
            clear();
        }
    }

    public static void clear() {
        active = false;
        GENERATION.incrementAndGet();
    }

    /**
     * 解析玩家在多人列表 / 直接连接里填的地址，判断是否为目标服。
     *
     * @param address {@code ServerInfo.address}；空或 null 不匹配
     */
    public static boolean matches(String address) {
        if (address == null) {
            return false;
        }
        String raw = address.trim();
        if (raw.isEmpty()) {
            return false;
        }
        HostPort hp = parse(raw);
        if (hp == null) {
            return false;
        }
        if (hp.port != TARGET_PORT) {
            return false;
        }
        return TARGET_HOST.equalsIgnoreCase(hp.host);
    }

    /**
     * 拆 host / port。支持 {@code host:port} 与 {@code [ipv6]:port}。
     * 省略端口按原版默认 {@link #VANILLA_DEFAULT_PORT}。
     * 非法（空 host、坏端口、路径）返回 null。
     */
    static HostPort parse(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.contains("/") || s.contains("\\") || s.contains(" ")) {
            return null;
        }
        String host;
        int port = VANILLA_DEFAULT_PORT;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close <= 1) {
                return null;
            }
            host = s.substring(1, close);
            String rest = s.substring(close + 1);
            if (rest.isEmpty()) {
                // [ipv6] 无端口 → 默认
            } else if (rest.startsWith(":")) {
                Integer p = parsePort(rest.substring(1));
                if (p == null) {
                    return null;
                }
                port = p;
            } else {
                return null;
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon < 0) {
                host = s;
            } else {
                // 多个冒号且无方括号 → 当裸 IPv6，无端口
                int first = s.indexOf(':');
                if (first != colon) {
                    host = s;
                } else {
                    host = s.substring(0, colon);
                    Integer p = parsePort(s.substring(colon + 1));
                    if (p == null) {
                        return null;
                    }
                    port = p;
                }
            }
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        return new HostPort(host, port);
    }

    private static Integer parsePort(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            int p = Integer.parseInt(text);
            if (p < 1 || p > 65535) {
                return null;
            }
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record HostPort(String host, int port) {
    }
}
