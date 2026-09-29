# BCStock

**Bilicraft 19 周目「帕拉伦股市」的客户端行情工具。**

*A client-side Fabric mod for the Palaren stock market on the Bilicraft Minecraft server.*

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62b47a?logo=minecraft)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%200.19.5%2B-dbb69c)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-22-orange)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

---

## 这是什么

Bilicraft 19 周目内置了一个玩家股市（「帕拉伦股市」）。这个 mod 把它的行情搬进游戏内的
HUD 和一张盘面里，让你不用反复敲 `/invest` 就能盯盘、比较地板距离、并且**手动**下单。

因为股市跑在别人的服务器上、服务端一行都不能改，**所有功能都只在客户端完成**：

- 公开行情来自一个第三方 HTTP API（读）
- **个人持仓、成本、资产只能从游戏内聊天回执和 GUI 容器读到**（公开 API 完全没有个人数据）
- **下单走聊天命令**，没有也不会模拟点击容器槽位

> ⚠️ **只对 Bilicraft 19 周目有效。** mod 依赖该服特有的命令与界面结构，在其他服务器上没有意义。
> 该服有白名单，所以这个 mod 实际上只对服内成员有用。

## 功能

| 功能 | 说明 |
| --- | --- |
| **盯盘 HUD** | 常驻小窗，显示关注公司的价格、涨跌、地板距离 |
| **盘面** | 全市场表格：价格、涨跌、剩余股数、地板距离，可筛选、可排序 |
| **公司详情** | 单家公司的 K 线、地板/危险区、成本与持仓 |
| **手动下单** | 点「买/卖」→ 选数量 → **确认屏按回车**才发命令 |
| **决策建议** | 基于地板模型给出 BUY / SELL / HOLD 建议（**只显示，不自动执行**） |
| **本地账本** | 内嵌 H2 数据库记录成交与快照流水，重启不丢 |
| **降级不崩** | API 挂掉时自动降档：只显示命令能给的数据，下单入口灰显禁用 |
| **只对目标服生效** | 只在填写地址为 `mc.bilicraft.com:25577` 时启用；单机与其他服不发包、不画 HUD、不开账本 |

## 安装

**前置：**

- Minecraft **1.21.11**
- [Fabric Loader](https://fabricmc.net/use/installer/) **0.19.5+**
- [Fabric API](https://modrinth.com/mod/fabric-api) `0.141.6+1.21.11`

**可选（装了才有图形设置界面）：**

- [Mod Menu](https://modrinth.com/mod/modmenu)
- [Cloth Config](https://modrinth.com/mod/cloth-config)

**步骤：**

1. 从 [Releases](../../releases) 下载 `bcstock-<版本>.jar`
2. 放进 `.minecraft/mods/`
3. **确认 `mods/` 里只有一个 bcstock 的 jar** —— 两个同 id 的包会让 Fabric 直接拒载
4. 启动游戏，进服后按 `K` 打开盘面

## 使用

| 按键 | 作用 |
| --- | --- |
| `K` | 打开 / 关闭盘面 |
| `H` | 开关盯盘 HUD |
| `P` | 强制探测当前容器（把槽位与 lore 写进日志，排查用） |
| `B` | 调试：发一条查询命令 |

**手动下单流程：** 盘面里选中一家公司 → 点「买」或「卖」→ 弹出数量框 → 确认屏
**按回车**才会真正发出 `/invest buy|sell <market_id> <数量>`。按 Esc 取消。

## 配置

配置文件在 `<游戏目录>/config/bcstock.json`，首次启动自动生成。装了 Mod Menu 的话
可以在游戏内图形化修改。

```jsonc
{
  "board":   { "showBankrupt": true },              // 盘面是否列出已破产公司
  "collect": { "enabled": true },                   // 是否定时采集快照
  "alert":   { "enabled": true, "changeEnabled": true,
               "floorEnabled": true, "pnlEnabled": true },
  "trade":   { "enabled": true },                   // 手动下单总开关
  "auto":    { "mode": "advice",                    // advice / limited / full
               "maxPerTrade": 10, "maxPerDay": 100,
               "kill": false, "allowBuy": true, "allowSell": true,
               "cooldownSec": 60, "cashFloor": 0.0, "whitelist": "" },
  "decision": { "preset": "default" },              // default / aggressive / conservative
  "api":      { "base": "https://tool.myflycat.cn/quant", "timeoutMs": 15000 }
}
```

行情 API 也可用启动参数覆盖：`-Dbcstock.api.base=<url>`。

**本地数据：**

- 账本：`<游戏目录>/bcstock/<uuid>/store.mv.db`
- 快照流水：`<游戏目录>/bcstock/snapshots.jsonl`

## 关于自动交易（默认关闭）

`auto.mode` 有三档，**默认是 `advice` —— 只出建议，一个包都不发。**

| 档位 | 行为 |
| --- | --- |
| `advice`（默认） | 只显示建议，绝不自动发包 |
| `limited` | 满足条件时自动发一条，受单笔/单日上限与冷却约束 |
| `full` | 放宽限制的自动档 |

> **在把 `auto.mode` 改成 `limited` 或 `full` 之前，请先确认目标服务器允许客户端自动化。**
> 本 mod 的作者只在确认服务器规则允许的场合使用自动档，默认配置永远是 `advice`。
> 无论哪一档，真正的写操作都只有一条路径：聊天命令。

## 从源码构建

需要 **JDK 22**。构建链：Gradle 9.8.0（wrapper 已带）+ Fabric Loom 1.17.21。

```bash
./gradlew build          # 构建 + 跑全部测试
./gradlew test           # 237 个用例，离线秒级跑完
```

产物在 `build/libs/bcstock-<版本>.jar`。

> ⚠️ 不要单独升降版本网里的任何一项（`gradle.properties` / `build.gradle.kts`）。
> 特别是 Loom —— 1.18 换用了 FFM，需要 Java 25，升上去编不过。

## 架构

```
数据层   ApiClient（公司 / K 线 / 地板）+ CommandGateway（聊天命令回执）+ InvestProbe（容器，兜底）
         → 归一到 CompanyView / HoldingView / WalletView / StockSnapshot，界面只认这一套类型
决策层   FloorCache（只读服务端算好的地板）+ DecisionEngine / DecisionPreset（BUY / SELL / HOLD 建议）
展示层   HUD + 盘面 StockBoardScreen + 详情 / 数量 / 确认三张独立 Screen
执行层   手动：买/卖 → 数量弹窗 → 确认屏回车 → CommandGateway 发聊天命令
         自动：行情刷新后 → AutoTradePlanner（仅限 non-advice 档）
```

**两条设计硬规矩：**

- **写操作不点容器槽位**，只发聊天命令
- **渲染路径零发包零发网**，只读本地快照；发命令统一在 tick 里做

## 两个容易踩的坑

**地板价是估值，不是原值。** 地板由服务端算好后通过 API 下发，客户端只读 `distance_pct`，
未知时不当 0。曾被放弃的做法是用 `floor × risk / 0.6` 在客户端反推，实测校准误差约 44%。

**不做方向预测。** 涨跌方向预测在本市场已实证无效（准确率 50.01%，等于抛硬币），
所以这个 mod 只在**触底反弹**这一个边际上给建议，不猜涨跌。

## 数据来源与限制

- **行情 API 是第三方服务**（`tool.myflycat.cn`），**没有任何稳定性承诺**。
  mod 做了降级：API 不可用时自动切到只读能拿到的数据，而不是崩溃。
- 价格 **每 15 分钟**刷新一次，落在 `:03 / :18 / :33 / :48`。客户端拿不到更细的粒度
  —— 游戏内和 API 是同一份数据。
- K 线的每根 bar **OHLC 四个值通常完全相同**，是单点采样塞进 OHLC 结构，不是区间聚合。
  做技术指标前请先记住这点。

## 延伸阅读

仓库的 `docs/` 下有更细的事实文档：

- [`docs/api-reference.md`](docs/api-reference.md) —— 行情 API 的路径与字段
- [`docs/decision-model.md`](docs/decision-model.md) —— 地板模型的算法说明
- [`docs/data-sources.md`](docs/data-sources.md) —— 三个数据源的边界
- [`docs/server-and-market.md`](docs/server-and-market.md) —— 服务器与股市机制
- [`docs/design/ui-spec.md`](docs/design/ui-spec.md) —— 界面控件与配色规格

## 许可

[MIT](LICENSE)。

与 Mojang、Microsoft 及 Bilicraft 服务器运营方均无隶属关系。
