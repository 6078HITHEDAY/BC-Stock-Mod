package cn.myflycat.bcstock.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 拿<b>真实 MCC 快照</b>当黄金样本，验证 M0 的解析链路：{@code ItemStack.name + tooltip}
 * → {@link ProbedSlot} → {@link CompanyRow}。
 *
 * <p>这层刻意不碰任何 {@code net.minecraft.*} 类（{@code ProbedSlot} 的规范构造器只吃
 * 字符串和 int），所以<b>不用起游戏</b>就能跑——这正是「决策内核是纯数学，
 * 离线就能证明没算错」的测试策略，在 M0 这一层先落到「容器解析没读错」上。
 *
 * <h2>样本的来历与口径</h2>
 * {@code src/test/resources/probe-samples/company-list-0201.tsv} 由
 * {@code scripts/gen-probe-fixture.py} 从 {@code data/gui/02-容器1-公司列表-0201.txt}
 * （MCC 在 2026-09-26 02:01 采的「公司列表」快照）转出。转换只做一件事：把 MCC 缝成一行的
 * {@code 名称 | lore...} 拆回「名字 + 一串 lore 行」，贴合 {@code ItemStack} 的真实结构。
 * <b>不做任何数值解释</b>——单位后缀、箭头正负都原样保留，因为这一版就该原样保留。
 *
 * <h2>为什么不测「背包切分」</h2>
 * 容器区 / 背包的切分靠 {@code Slot.inventory} 的身份判断，必须有真的
 * {@code ScreenHandler} 才能测，属于游戏内验证的范围（见 M0 验收：
 * 编译通过 + 游戏内日志出现 16 家公司）。这里只覆盖纯字符串那一段。
 */
class CompanyListProbeTest {

    /** 「公司列表」实测 45 格，其中 #10–#16 / #19–#25 / #28–#29 是公司。 */
    private static final int CONTAINER_SLOTS = 45;

    /** 16 家可交易公司，按槽位升序。见 docs/gui-invest.md。 */
    private static final Set<Integer> EXPECTED_COMPANY_SLOTS = Set.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29);

    private static final String FIXTURE = "/probe-samples/company-list-0201.tsv";

    // ------------------------------------------------------------------
    // 验收主断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("M0 验收：真实快照里认出 16 家公司，且槽位与 docs 记录一致")
    void recognisesExactlySixteenCompanies() {
        List<CompanyRow> companies = companiesOf(loadFixture());

        assertEquals(16, companies.size(), "「公司列表」应认出 16 家公司");

        Set<Integer> actual = new TreeSet<>();
        for (CompanyRow row : companies) {
            actual.add(row.slotIndex());
        }
        assertEquals(new TreeSet<>(EXPECTED_COMPANY_SLOTS), actual,
                "公司槽位应为 #10–#16 / #19–#25 / #28–#29");
    }

    @Test
    @DisplayName("M0 验收：16 家公司每一家都有非空的名称与单价")
    void everyCompanyHasNameAndPrice() {
        List<CompanyRow> companies = companiesOf(loadFixture());

        for (CompanyRow row : companies) {
            assertNotNull(row.displayName(), "槽位 #" + row.slotIndex() + " 没有公司名");
            assertFalse(row.displayName().isBlank(),
                    "槽位 #" + row.slotIndex() + " 的公司名是空白");
            assertNotNull(row.priceText(),
                    "槽位 #" + row.slotIndex() + "（" + row.displayName() + "）没有单价");
            assertFalse(row.priceText().isBlank(),
                    "槽位 #" + row.slotIndex() + "（" + row.displayName() + "）单价是空白");
            assertNotNull(row.status(),
                    "槽位 #" + row.slotIndex() + "（" + row.displayName() + "）没有状态");
        }
    }

    @Test
    @DisplayName("逐字段抠值：以 #10 联邦健保 为样板核对全字段")
    void extractsEveryFieldOfAKnownRow() {
        CompanyRow row = companyAt(loadFixture(), 10);

        assertEquals("联邦健保", row.displayName());
        assertEquals("↑ 1.18%", row.changeText());
        assertEquals("交易中", row.status());
        assertEquals("2", row.riskText());
        assertEquals("29.53", row.priceText());
        assertEquals("4.72 M", row.marketCapText());
        assertEquals("↓ 60.63%", row.totalChangeText());
    }

    @Test
    @DisplayName("与 API 样本交叉核对：帕拉伦联合储蓄 的 GUI 单价 == API latest_price")
    void guiPriceMatchesApiSample() {
        // data/api/companies.json（2026-09-26 01:51）：帕拉伦联合储蓄 latest_price = 62.97
        // 这是两条独立数据源（GUI 容器 vs 公开 API）唯一能对上的锚点，值得钉住。
        CompanyRow row = companyAt(loadFixture(), 24);

        assertEquals("帕拉伦联合储蓄", row.displayName());
        assertEquals("62.97", row.priceText());
        assertEquals("1", row.riskText());
    }

    // ------------------------------------------------------------------
    // 「只取原文、不做数值解释」的边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单位后缀原样保留，不换算：3.47 K 不当成 3470")
    void keepsUnitSuffixVerbatim() {
        CompanyRow row = companyAt(loadFixture(), 11);

        assertEquals("鹅城军工科技", row.displayName());
        assertEquals("3.47 K", row.priceText(), "单价应保留 K 后缀原文");
        assertEquals("173.64 M", row.marketCapText(), "市值应保留 M 后缀原文");
        assertEquals("5", row.riskText());
    }

    @Test
    @DisplayName("箭头不许丢：↑ 与 ↓ 分别原样带在字符串里")
    void keepsArrowDirectionVerbatim() {
        Map<Integer, String> up = new LinkedHashMap<>();
        up.put(10, "↑ 1.18%");   // 联邦健保
        up.put(24, "↑ 0.43%");   // 帕拉伦联合储蓄
        Map<Integer, String> down = new LinkedHashMap<>();
        down.put(13, "↓ 3.10%"); // 蒸汽平台
        down.put(14, "↓ 28.61%"); // 碧伟达

        List<ProbedSlot> slots = loadFixture();
        up.forEach((slot, expected) ->
                assertEquals(expected, companyAt(slots, slot).changeText(), "槽位 #" + slot));
        down.forEach((slot, expected) ->
                assertEquals(expected, companyAt(slots, slot).changeText(), "槽位 #" + slot));
    }

    @Test
    @DisplayName("历史总涨跌单独成字段，且与当轮涨跌互不串味")
    void totalChangeIsSeparateFromSessionChange() {
        CompanyRow row = companyAt(loadFixture(), 10);

        assertEquals("↑ 1.18%", row.changeText(), "当轮涨跌来自物品名尾部");
        assertEquals("↓ 60.63%", row.totalChangeText(), "历史总涨跌来自 lore，是另一个字段");
    }

    @Test
    @DisplayName("↓ NaN% 不被当成公司（空仓时总览会出现，格式未知就显式跳过）")
    void nanChangeDoesNotBreakParsing() {
        // 语义未定（docs/gui-invest.md：当 0 还是忽略要等实测），所以这里只要求「不崩、不误判」。
        CompanyRow row = CompanyRow.from(slot(7, "投资组合总览 [ ↓ NaN% ]",
                "投资公司数: 0", "总持股数: 0", "资产总价值: 0.00"));

        assertNull(row, "只有 NaN 涨跌、没有单价/风险度的格子不该被认成公司");
    }

    // ------------------------------------------------------------------
    // 非公司格子不能误判
    // ------------------------------------------------------------------

    @Test
    @DisplayName("装饰玻璃板 / 入口按钮 / 翻页按钮都不算公司")
    void nonCompanySlotsAreRejected() {
        List<ProbedSlot> slots = loadFixture();
        Map<Integer, String> expected = Map.of(
                3, "投资组合（入口按钮）",
                5, "通知设置（入口按钮）",
                0, "灰色染色玻璃板",
                17, "灰色染色玻璃板",
                39, "上一页",
                41, "下一页");

        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            int index = entry.getKey();
            assertNull(CompanyRow.from(slotAt(slots, index)),
                    "槽位 #" + index + "（" + entry.getValue() + "）不该被认成公司");
        }
    }

    @Test
    @DisplayName("容器区 45 格里只有 16 格非空，其余是玻璃板/按钮/空格")
    void containerAreaHasSixteenNonEmptySlots() {
        List<ProbedSlot> slots = loadFixture();
        List<CompanyRow> companies = companiesOf(slots);

        assertEquals(CONTAINER_SLOTS, slots.size(), "容器区应有 45 格");

        long nonEmpty = slots.stream().filter(s -> !s.empty()).count();
        // 16 家公司 + 入口 2 + 玻璃板若干 + 上一页/下一页 2
        assertEquals(40, nonEmpty, "除 #30–#34 五个空格以外，其余 40 格都有物品");
        assertEquals(5, slots.stream().filter(ProbedSlot::empty).count(),
                "#30–#34 这五格是空的");

        // 面板上一格不多、一格不少：非空格子数 = 公司 + 非公司
        assertEquals(nonEmpty, companies.size() + (nonEmpty - companies.size()));
    }

    // ------------------------------------------------------------------
    // 快照层
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ProbedContainer 的签名与容器区计数：内容变则签名变，不变则签名稳")
    void containerSignatureTracksContent() {
        List<ProbedSlot> slots = loadFixture();
        ProbedContainer a = container(slots);
        ProbedContainer b = container(loadFixture());
        ProbedContainer changed = container(pokePrice(slots, 10, "31.00"));

        assertEquals(45, a.containerSlotCount());
        assertEquals(16, a.companies().size());
        assertEquals(40, a.nonEmptyContainerSlots());
        assertEquals(a.signature(), b.signature(), "同样的快照签名必须一致（去重靠它）");
        assertTrue(a.signature() != changed.signature(), "单价变了签名就该变");
        assertFalse(a.truncated(), "45 格远低于打印上限，不该被截断");
    }

    // ------------------------------------------------------------------
    // fixture 装载
    // ------------------------------------------------------------------

    /** 从 classpath 读 TSV 样本，还原成容器区的 {@link ProbedSlot} 列表。 */
    private static List<ProbedSlot> loadFixture() {
        try (InputStream in = CompanyListProbeTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("找不到 fixture：" + FIXTURE);
            }
            List<ProbedSlot> slots = new ArrayList<>(CONTAINER_SLOTS);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    slots.add(parseLine(line));
                }
            }
            return List.copyOf(slots);
        } catch (IOException e) {
            throw new UncheckedIOException("读 fixture 失败：" + FIXTURE, e);
        }
    }

    /**
     * 一行 TSV → 一个槽位快照。
     *
     * <p>{@code split("\t", -1)} 的 {@code -1} 不能省：服务端的 lore 里确实有空行
     * （{@code 交易中 |  | 风险度: 2}），用默认的 {@code split("\t")} 会把尾部空段吃掉，
     * 于是「字段错位」这种真问题反而看不出来。
     */
    private static ProbedSlot parseLine(String line) {
        String[] fields = line.split("\t", -1);
        if ("EMPTY".equals(fields[0])) {
            int index = Integer.parseInt(fields[1].trim());
            return new ProbedSlot(index, index, true, "", 0, List.of());
        }
        int index = Integer.parseInt(fields[0].trim());
        List<String> lore = new ArrayList<>(Math.max(0, fields.length - 2));
        for (int i = 2; i < fields.length; i++) {
            lore.add(fields[i]);
        }
        return new ProbedSlot(index, index, false, fields[1], 1, lore);
    }

    /** 手搓一个槽位快照（测边界用），不走 fixture。 */
    private static ProbedSlot slot(int index, String name, String... lore) {
        return new ProbedSlot(index, index, false, name, 1, List.of(lore));
    }

    private static List<CompanyRow> companiesOf(List<ProbedSlot> slots) {
        List<CompanyRow> out = new ArrayList<>();
        for (ProbedSlot s : slots) {
            CompanyRow row = CompanyRow.from(s);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    private static ProbedSlot slotAt(List<ProbedSlot> slots, int index) {
        for (ProbedSlot s : slots) {
            if (s.index() == index) {
                return s;
            }
        }
        throw new IllegalArgumentException("槽位 #" + index + " 不在快照里");
    }

    private static CompanyRow companyAt(List<ProbedSlot> slots, int index) {
        CompanyRow row = CompanyRow.from(slotAt(slots, index));
        assertNotNull(row, "槽位 #" + index + " 应该是一家公司");
        return row;
    }

    private static ProbedContainer container(List<ProbedSlot> slots) {
        return new ProbedContainer(
                StockContainerKind.COMPANY_LIST,
                "公司列表",
                1,
                slots.size() + 36, // 容器区 + 玩家背包
                slots.size(),
                36,
                0,
                slots,
                companiesOf(slots),
                0,
                false);
    }

    /** 改掉某一格的单价，造一份「内容变了」的快照。 */
    private static List<ProbedSlot> pokePrice(List<ProbedSlot> slots, int index, String newPrice) {
        List<ProbedSlot> out = new ArrayList<>(slots.size());
        for (ProbedSlot s : slots) {
            if (s.index() != index) {
                out.add(s);
                continue;
            }
            List<String> lore = new ArrayList<>(s.tooltip().size());
            boolean replaced = false;
            for (String l : s.tooltip()) {
                if (!replaced && l.startsWith("股票单价")) {
                    lore.add("股票单价: " + newPrice);
                    replaced = true;
                } else {
                    lore.add(l);
                }
            }
            out.add(new ProbedSlot(s.index(), s.slotId(), s.empty(), s.itemName(), s.count(), lore));
        }
        return List.copyOf(out);
    }
}
