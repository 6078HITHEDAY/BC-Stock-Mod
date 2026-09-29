package cn.myflycat.bcstock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGateTest {

    @AfterEach
    void reset() {
        ServerGate.clear();
    }

    @Test
    @DisplayName("目标地址匹配：主机忽略大小写，端口必须 25577")
    void matchesTarget() {
        assertTrue(ServerGate.matches("mc.bilicraft.com:25577"));
        assertTrue(ServerGate.matches("MC.Bilicraft.COM:25577"));
        assertTrue(ServerGate.matches("  mc.bilicraft.com:25577  "));
    }

    @Test
    @DisplayName("省略端口按原版 25565，不匹配")
    void omittedPortNoMatch() {
        assertFalse(ServerGate.matches("mc.bilicraft.com"));
    }

    @Test
    @DisplayName("错误端口 / 子域 / 空 / 路径不匹配")
    void rejectsLookalikes() {
        assertFalse(ServerGate.matches("mc.bilicraft.com:25565"));
        assertFalse(ServerGate.matches("mc.bilicraft.com:25578"));
        assertFalse(ServerGate.matches("evil.mc.bilicraft.com:25577"));
        assertFalse(ServerGate.matches("mc.bilicraft.com:25577/extra"));
        assertFalse(ServerGate.matches(""));
        assertFalse(ServerGate.matches(null));
        assertFalse(ServerGate.matches("   "));
        assertFalse(ServerGate.matches("localhost:25577"));
        assertFalse(ServerGate.matches("127.0.0.1:25577"));
    }

    @Test
    @DisplayName("进程内 active 标志：JOIN 开、DISCONNECT 关")
    void activeFlag() {
        assertFalse(ServerGate.active());
        ServerGate.setActive(true);
        assertTrue(ServerGate.active());
        ServerGate.clear();
        assertFalse(ServerGate.active());
    }
}
