package cn.myflycat.bcstock.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守卫<b>黄金样本文件本身</b>的测试——不测解析逻辑，测"这份 fixture 是不是干净、可用的"。
 *
 * <p>为什么要单开一层：2026-09-26 转 fixture 时踩了两个坑，两个都不是解析器的错，
 * 而是<b>样本本身脏了</b>，而且脏得非常安静——测试照样绿，数据是错的：
 *
 * <ol>
 *   <li><b>玩家背包混进容器区。</b>{@code gen-probe-fixture.py} 的 {@code --container-slots}
 *       默认 45，跑「通知设置」（36 格）时把后面 9 格玩家背包吃了进来，
 *       fixture 里出现了 {@code 下界合金锄} / {@code 鞘翅} / {@code 烟花火箭}。</li>
 *   <li><b>无名物品被误判成空格子。</b>正式环境里 {@code ItemStack.getName()} 对玻璃板这类
 *       物品返回<b>空串</b>（mod 日志打的是 {@code #0 x1  slotId=0}，名字段是空的）。
 *       按"名字为空即空格"处理，「公司列表」的非空格子从 40 掉到 20，
 *       容器结构整个错位。真正的空格子在日志里打的是 {@code (空)}。</li>
 * </ol>
 *
 * <p>这两个坑的共同点是<b>要靠样本自己声明的事实来抓</b>：格数与非空数都写在 fixture 表头里
 * （两支生成脚本负责写），本测试逐份核。改了样本却忘了改表头，或者手改了样本，这里会红。
 *
 * <p>仍然不碰任何 {@code net.minecraft.*}：纯字符串 + TSV 解析，离线可跑。
 */
class GoldenSampleIntegrityTest {

    /** fixture 表头里声明的容器格数，例：{@code # 容器格数：45} */
    private static final Pattern DECLARED_SLOTS = Pattern.compile("^#\\s*容器格数：(\\d+)");

    /** fixture 表头里声明的非空格子数，例：{@code # 非空格子：40　公司：16} */
    private static final Pattern DECLARED_NON_EMPTY = Pattern.compile("^#\\s*非空格子：(\\d+)");

    private static final Pattern DECLARED_COMPANIES = Pattern.compile("公司：(\\d+)");

    /**
     * 只会出现在玩家背包里的东西。容器区（股市 GUI）不可能有这些——
     * 公司的图标是药水 / 钻石 / 金块 / 雪球 / 告示牌 / 火把 / 混凝土那几类。
     */
    private static final List<String> PLAYER_ONLY = List.of(
            "鞘翅", "末影珍珠", "烟花火箭", "下界合金", "水桶", "Unknown Enchantment",
            "精准采集", "自我修复", "大地恩赐", "心灵遥感", "耐久 ", "缓冲 ", "经验修补");

    /** 已知的全部 fixture：文件名 → 是否为「公司列表」。 */
    private static final Map<String, Boolean> FIXTURES = new LinkedHashMap<>();

    static {
        FIXTURES.put("company-list-0201.tsv", true);          // MCC 采集，16 家在售
        FIXTURES.put("company-list-1407.tsv", true);          // mod 真服 dump，13 在售 + 3 已破产
        FIXTURES.put("portfolio-0201.tsv", false);            // MCC，空仓投资组合
        FIXTURES.put("portfolio-1426-held.tsv", false);       // mod 真服 dump，两笔持仓
        FIXTURES.put("portfolio-1426-sold-zero.tsv", false);  // mod 真服 dump，卖到 0 但行还在
        FIXTURES.put("notification-0202.tsv", false);         // MCC，36 格容器（不是 45）
    }

    private static final List<Integer> COMPANY_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29);

    // ------------------------------------------------------------------
    // 坑 1：玩家背包不许混进来
    // ------------------------------------------------------------------

    @Test
    @DisplayName("integrity：任何 fixture 都不许含玩家背包物品（--container-slots 给错的后果）")
    void noFixtureLeaksThePlayerInventory() {
        for (String name : FIXTURES.keySet()) {
            Fixture fixture = load(name);
            for (Slot slot : fixture.slots) {
                for (String forbidden : PLAYER_ONLY) {
                    assertTrue(!slot.name.contains(forbidden) && !slot.lore.contains(forbidden),
                            name + " 的 #" + slot.index + " 混进了玩家背包物品「" + forbidden + "」"
                                    + "（多半是 --container-slots 给错了：通知设置是 36 格，不是 45）");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 坑 2：无名物品不等于空格子
    // ------------------------------------------------------------------

    @Test
    @DisplayName("integrity：非空格子数必须与表头声明一致（无名物品被当成空格的后果）")
    void declaredNonEmptyMatchesParsed() {
        for (String name : FIXTURES.keySet()) {
            Fixture fixture = load(name);
            long parsed = fixture.slots.stream().filter(s -> !s.empty).count();

            assertNotNull(fixture.declaredNonEmpty, name + " 表头没写「# 非空格子：N」");
            assertEquals(fixture.declaredNonEmpty.intValue(), parsed,
                    name + " 表头声明非空 " + fixture.declaredNonEmpty + " 格，实际解析出 " + parsed + " 格");
            assertTrue(parsed > 0, name + " 一个非空格子都没有，样本多半是坏的");
        }
    }

    @Test
    @DisplayName("integrity：「公司列表」玻璃板那类无名物品是非空的，名字是空串")
    void unnamedItemsAreNotEmpty() {
        // 坑 2 的直接证据：公司列表 #0 是灰色染色玻璃板，正式环境里名字就是空串。
        // 它必须被读成"非空"，否则非空格子会从 40 掉到 20。
        Fixture fixture = load("company-list-1407.tsv");     // 这份来自 mod 真服 dump

        Slot slot0 = fixture.slotAt(0);
        assertEquals("", slot0.name, "mod dump 里无名物品的名字就该是空串");
        assertTrue(!slot0.empty, "名字为空 ≠ 空格子；#0 是玻璃板，它是非空的");

        long nonEmpty = fixture.slots.stream().filter(s -> !s.empty).count();
        assertEquals(40, nonEmpty, "「公司列表」实测非空 40 格（45 格中 #30–#34 是空的）");
    }

    // ------------------------------------------------------------------
    // 表头与实际必须自洽
    // ------------------------------------------------------------------

    @Test
    @DisplayName("integrity：格数、下标连续性与表头声明一致")
    void slotCountAndIndicesAreConsistent() {
        for (String name : FIXTURES.keySet()) {
            Fixture fixture = load(name);

            assertNotNull(fixture.declaredSlots, name + " 表头没写「# 容器格数：N」");
            assertEquals(fixture.declaredSlots.intValue(), fixture.slots.size(),
                    name + " 表头声明 " + fixture.declaredSlots + " 格，实际 " + fixture.slots.size() + " 行");

            TreeSet<Integer> indices = new TreeSet<>();
            for (Slot s : fixture.slots) {
                indices.add(s.index);
            }
            assertEquals(fixture.declaredSlots, indices.size(), name + " 下标有重复");
            assertEquals(0, indices.first().intValue(), name + " 下标不是从 0 开始");
            assertEquals(fixture.declaredSlots - 1, indices.last().intValue(), name + " 下标没到最后一格");
        }
    }

    @Test
    @DisplayName("integrity：公司列表的公司家数与表头声明一致，且槽位是实测那 16 个")
    void companyListsDeclareTheirCompanyCount() {
        for (Map.Entry<String, Boolean> entry : FIXTURES.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            Fixture fixture = load(entry.getKey());

            assertNotNull(fixture.declaredCompanies, entry.getKey() + " 表头没写公司家数");
            assertEquals(fixture.declaredCompanies.intValue(), fixture.companies.size(),
                    entry.getKey() + " 表头声明 " + fixture.declaredCompanies
                            + " 家，实际解析出 " + fixture.companies.size() + " 家");

            List<Integer> actual = new ArrayList<>();
            for (Slot s : fixture.companies) {
                actual.add(s.index);
            }
            assertEquals(COMPANY_SLOTS, actual, entry.getKey() + " 公司槽位与实测的 #10–#16/#19–#25/#28–#29 不符");
        }
    }

    // ------------------------------------------------------------------
    // 两条独立来源的结构必须同意
    // ------------------------------------------------------------------

    @Test
    @DisplayName("integrity：MCC 样本与 mod dump 样本对「公司列表」的槽位结构必须一致")
    void mccAndModAgreeOnCompanyListLayout() {
        Fixture mcc = load("company-list-0201.tsv");     // MCC 采集
        Fixture mod = load("company-list-1407.tsv");     // mod 自己 dump

        assertEquals(mcc.slots.size(), mod.slots.size(), "两条来源的容器格数应一致");
        assertEquals(indicesOf(mcc.companies), indicesOf(mod.companies),
                "两条来源认出的公司槽位应完全一致（这是跨来源的结构交叉验证）");

        // 非公司格（玻璃板 / 入口按钮 / 翻页）的槽位也要一致
        assertEquals(emptyIndices(mcc), emptyIndices(mod), "空格子位置应一致");
    }

    // ------------------------------------------------------------------
    // 装载
    // ------------------------------------------------------------------

    /** 一份 fixture 的解析结果。 */
    private record Fixture(List<Slot> slots, List<Slot> companies,
                           Integer declaredSlots, Integer declaredNonEmpty, Integer declaredCompanies) {
        Slot slotAt(int index) {
            for (Slot s : slots) {
                if (s.index == index) {
                    return s;
                }
            }
            throw new IllegalArgumentException("fixture 里没有 #" + index);
        }
    }

    /** 一格：{@code empty} 为真时是真空格（日志里的 {@code (空)}）。 */
    private record Slot(int index, String name, String lore, boolean empty) {
    }

    private static Fixture load(String fixtureName) {
        String path = "/probe-samples/" + fixtureName;
        List<Slot> slots = new ArrayList<>();
        List<Slot> companies = new ArrayList<>();
        Integer declaredSlots = null;
        Integer declaredNonEmpty = null;
        Integer declaredCompanies = null;

        try (InputStream in = GoldenSampleIntegrityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "找不到 fixture：" + path);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("#")) {
                        Matcher m = DECLARED_SLOTS.matcher(line);
                        if (m.find()) {
                            declaredSlots = Integer.valueOf(m.group(1));
                        }
                        m = DECLARED_NON_EMPTY.matcher(line);
                        if (m.find()) {
                            declaredNonEmpty = Integer.valueOf(m.group(1));
                            Matcher c = DECLARED_COMPANIES.matcher(line);
                            if (c.find()) {
                                declaredCompanies = Integer.valueOf(c.group(1));
                            }
                        }
                        continue;
                    }
                    // split("\t", -1) 的 -1 不能省：lore 里确实有空行，默认 split 会吃掉尾部空段
                    String[] fields = line.split("\t", -1);
                    if ("EMPTY".equals(fields[0])) {
                        slots.add(new Slot(Integer.parseInt(fields[1].trim()), "", "", true));
                        continue;
                    }
                    int index = Integer.parseInt(fields[0].trim());
                    StringBuilder lore = new StringBuilder();
                    for (int i = 2; i < fields.length; i++) {
                        lore.append(fields[i]).append('\u0001');
                    }
                    Slot slot = new Slot(index, fields[1], lore.toString(), false);
                    slots.add(slot);
                    if (fields[1].contains("风险度") || lore.toString().contains("风险度")) {
                        companies.add(slot);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读 fixture 失败：" + path, e);
        }
        return new Fixture(List.copyOf(slots), List.copyOf(companies),
                declaredSlots, declaredNonEmpty, declaredCompanies);
    }

    private static List<Integer> indicesOf(List<Slot> slots) {
        List<Integer> out = new ArrayList<>(slots.size());
        for (Slot s : slots) {
            out.add(s.index);
        }
        return out;
    }

    private static List<Integer> emptyIndices(Fixture fixture) {
        List<Integer> out = new ArrayList<>();
        for (Slot s : fixture.slots) {
            if (s.empty) {
                out.add(s.index);
            }
        }
        return out;
    }
}
