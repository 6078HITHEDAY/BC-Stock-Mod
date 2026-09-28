package cn.myflycat.bcstock.config;

/**
 * 读 JVM 系统属性的抽象，方便单测注入假属性表。
 * {@code null} 表示「没设」——此时用配置文件或默认值。
 */
@FunctionalInterface
public interface PropertySource {

    /** 属性值；未设置返回 {@code null}。 */
    String get(String key);

    static PropertySource system() {
        return System::getProperty;
    }

    static PropertySource empty() {
        return key -> null;
    }
}
