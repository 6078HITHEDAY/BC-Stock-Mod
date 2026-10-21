package cn.myflycat.bcstock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 版本号只有一个来源：{@code gradle.properties} 的 {@code mod_version}。 {@code fabric.mod.json} 是硬编码的，曾是「jar 叫
 * r6、元数据还是 r5」的漂移点，这里钉死。
 */
class VersionConsistencyTest {

  @Test
  @DisplayName("★ fabric.mod.json 的 version 必须等于 gradle.properties 的 mod_version")
  void metadataVersionMatchesGradleVersion() throws Exception {
    Properties props = new Properties();
    try (InputStream in = Files.newInputStream(Path.of("gradle.properties"))) {
      props.load(in);
    }
    String gradleVersion = props.getProperty("mod_version");
    JsonObject meta =
        JsonParser.parseString(
                Files.readString(
                    Path.of("src/main/resources/fabric.mod.json"), StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertEquals(
        gradleVersion,
        meta.get("version").getAsString(),
        "改版本号要同时改 gradle.properties 与 fabric.mod.json");
  }
}
