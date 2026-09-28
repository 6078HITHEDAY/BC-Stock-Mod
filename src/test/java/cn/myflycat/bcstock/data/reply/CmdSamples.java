package cn.myflycat.bcstock.data.reply;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 读 {@code cmd-samples/} 里从 {@code docs/commands.md} 抄下来的回执原文。 */
public final class CmdSamples {

    private CmdSamples() {
    }

    public static List<String> lines(String name) {
        String path = "cmd-samples/" + name;
        try (InputStream in = CmdSamples.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到黄金样本 " + path);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                out.add(line);
            }
            if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                out.remove(out.size() - 1);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String firstLine(String name) {
        List<String> lines = lines(name);
        return lines.isEmpty() ? "" : lines.get(0);
    }
}
