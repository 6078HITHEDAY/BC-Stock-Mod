package cn.myflycat.bcstock.config;

import cn.myflycat.bcstock.data.AutoTradeSettings;
import cn.myflycat.bcstock.decision.DecisionPreset;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Cloth Config 设置页。文案用人话，不出现 JVM 参数名。
 * 保存写回 {@code config/bcstock.json}（与手改文件同一份真相）。
 *
 * <p>仅在 cloth-config 已加载时由 {@link BcStockModMenu} 调用。
 */
public final class BcStockClothConfig {

    private BcStockClothConfig() {
    }

    public static Screen create(Screen parent) {
        BcStockConfig draft = ConfigRuntime.snapshot();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Text.literal("BC Stock 设置"))
                .setSavingRunnable(() -> ConfigRuntime.saveFromGui(draft));
        ConfigEntryBuilder entries = builder.entryBuilder();

        ConfigCategory board = builder.getOrCreateCategory(Text.literal("盘面"));
        board.addEntry(entries.startBooleanToggle(Text.literal("显示破产公司"), draft.board.showBankrupt)
                .setDefaultValue(true)
                .setTooltip(Text.literal("关掉后开盘默认「排除破产」；切到「全部」仍能看见。通知从不报破产。"))
                .setSaveConsumer(v -> draft.board.showBankrupt = v)
                .build());

        ConfigCategory trade = builder.getOrCreateCategory(Text.literal("交易"));
        trade.addEntry(entries.startBooleanToggle(Text.literal("允许下单"), draft.trade.enabled)
                .setDefaultValue(false)
                .setTooltip(Text.literal("关掉时买卖按钮不会发出任何命令"))
                .setSaveConsumer(v -> draft.trade.enabled = v)
                .build());

        ConfigCategory collect = builder.getOrCreateCategory(Text.literal("采集与提醒"));
        collect.addEntry(entries.startBooleanToggle(Text.literal("定时采集持仓"), draft.collect.enabled)
                .setDefaultValue(false)
                .setSaveConsumer(v -> draft.collect.enabled = v)
                .build());
        collect.addEntry(entries.startBooleanToggle(Text.literal("启用提醒"), draft.alert.enabled)
                .setDefaultValue(false)
                .setSaveConsumer(v -> draft.alert.enabled = v)
                .build());
        collect.addEntry(entries.startBooleanToggle(Text.literal("涨跌提醒"), draft.alert.changeEnabled)
                .setDefaultValue(false)
                .setSaveConsumer(v -> draft.alert.changeEnabled = v)
                .build());
        collect.addEntry(entries.startBooleanToggle(Text.literal("距地板提醒"), draft.alert.floorEnabled)
                .setDefaultValue(false)
                .setSaveConsumer(v -> draft.alert.floorEnabled = v)
                .build());
        collect.addEntry(entries.startBooleanToggle(Text.literal("盈亏提醒"), draft.alert.pnlEnabled)
                .setDefaultValue(false)
                .setSaveConsumer(v -> draft.alert.pnlEnabled = v)
                .build());

        ConfigCategory auto = builder.getOrCreateCategory(Text.literal("自动化"));
        auto.addEntry(entries.startEnumSelector(Text.literal("自动化档位"), AutoTradeSettings.Mode.class,
                        AutoTradeSettings.Mode.parse(draft.auto.mode))
                .setDefaultValue(AutoTradeSettings.Mode.ADVICE)
                .setEnumNameProvider(m -> Text.literal(switch ((AutoTradeSettings.Mode) m) {
                    case ADVICE -> "仅建议（仍要手动确认）";
                    case LIMITED -> "限额内自动";
                    case FULL -> "全自动";
                }))
                .setSaveConsumer(v -> draft.auto.mode = v.key())
                .build());
        auto.addEntry(entries.startIntField(Text.literal("单笔上限（股）"), draft.auto.maxPerTrade)
                .setDefaultValue(10)
                .setMin(1)
                .setSaveConsumer(v -> draft.auto.maxPerTrade = v)
                .build());
        auto.addEntry(entries.startIntField(Text.literal("单日上限（股）"), draft.auto.maxPerDay)
                .setDefaultValue(100)
                .setMin(1)
                .setSaveConsumer(v -> draft.auto.maxPerDay = v)
                .build());
        auto.addEntry(entries.startBooleanToggle(Text.literal("紧急停止"), draft.auto.kill)
                .setDefaultValue(false)
                .setTooltip(Text.literal("打开后立刻禁止任何下单"))
                .setSaveConsumer(v -> draft.auto.kill = v)
                .build());
        auto.addEntry(entries.startBooleanToggle(Text.literal("允许自动买"), draft.auto.allowBuy)
                .setDefaultValue(true)
                .setTooltip(Text.literal("档位是「仅建议」时不会发令"))
                .setSaveConsumer(v -> draft.auto.allowBuy = v)
                .build());
        auto.addEntry(entries.startBooleanToggle(Text.literal("允许自动卖"), draft.auto.allowSell)
                .setDefaultValue(true)
                .setSaveConsumer(v -> draft.auto.allowSell = v)
                .build());
        auto.addEntry(entries.startIntField(Text.literal("两笔自动单间隔（秒）"), draft.auto.cooldownSec)
                .setDefaultValue(60)
                .setMin(0)
                .setSaveConsumer(v -> draft.auto.cooldownSec = v)
                .build());
        auto.addEntry(entries.startDoubleField(Text.literal("买入后现金底线"), draft.auto.cashFloor)
                .setDefaultValue(0.0)
                .setMin(0.0)
                .setTooltip(Text.literal("买完后钱包不得低于这个数"))
                .setSaveConsumer(v -> draft.auto.cashFloor = v)
                .build());
        auto.addEntry(entries.startStrField(Text.literal("公司白名单"), draft.auto.whitelist)
                .setDefaultValue("")
                .setTooltip(Text.literal("逗号分隔公司名或 market_id；空 = 全部可交易标的"))
                .setSaveConsumer(v -> draft.auto.whitelist = v)
                .build());

        ConfigCategory decision = builder.getOrCreateCategory(Text.literal("决策"));
        decision.addEntry(entries.startEnumSelector(Text.literal("参数档位"), DecisionPreset.class,
                        DecisionPreset.parse(draft.decision.preset))
                .setDefaultValue(DecisionPreset.DEFAULT)
                .setEnumNameProvider(p -> Text.literal(switch ((DecisionPreset) p) {
                    case DEFAULT -> "默认";
                    case AGGRESSIVE -> "激进";
                    case CONSERVATIVE -> "保守";
                }))
                .setSaveConsumer(v -> draft.decision.preset = v.key())
                .build());

        ConfigCategory api = builder.getOrCreateCategory(Text.literal("网络"));
        api.addEntry(entries.startStrField(Text.literal("行情接口地址"), draft.api.base)
                .setDefaultValue(BcStockConfig.safeDefaults().api.base)
                .setSaveConsumer(v -> draft.api.base = v)
                .build());
        api.addEntry(entries.startIntField(Text.literal("请求超时（毫秒）"), draft.api.timeoutMs)
                .setDefaultValue(15_000)
                .setMin(1_000)
                .setTooltip(Text.literal("冷启动拉取可能较慢，建议不少于 10000"))
                .setSaveConsumer(v -> draft.api.timeoutMs = v)
                .build());

        return builder.build();
    }
}
