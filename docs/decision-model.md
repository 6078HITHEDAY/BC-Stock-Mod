# 核心算法（BCquant 地板模型）

> 来源：`https://codeberg.org/myflycat/BCquant`（只读参考，**没有搬进本项目**）
> 这一份是把那套算法讲清楚，供游戏内 mod 复用。对应代码位置都标了 `文件:函数`。
>
> **2026-09-28 口径更新（mod 侧现状，读本文前先看这三条）**：
> ① **地板不反推**：服务端 `GET /api/floors` 直接给 `floor` / `danger_zone` / `distance_pct` / `in_danger_zone` /
>    `crash_risk_per_tick`，客户端只读（`FloorView`、`FloorCache`）；
> ② **写操作不点容器**：下单走聊天命令 `/invest buy|sell <market_id> <数量>`，且必须人在确认屏按回车（红线）；
> ③ **价格有三种口径**：盘面/ API 的展示价 ≠ `/invest company info` 的实时价（下单按实时价）。
> 详见 §2 注记、§6.3、§8。

## 0. 一句话

这套东西**不是预测方向**，而是：

1. 复刻服务端的价格**生成器**（BlockStreet `StocksRandomizer`），拿到它的硬边界（地板/天花板/危险区）
2. 用「地板 + 崩盘概率」把每家公司归到一个**状态**
3. 只在统计上唯一显著的那个边际上出手：**触底反弹**

方向预测被**主动放弃**了。实测 6307 个 tick，mid-range 方向预测准确率 **50.01%**，等于抛硬币；
唯一有统计显著性的边际是「价格触到地板后的反弹」：危险区 **72%** 反弹率（z=3.11，p≈0.001）——
但这个边际**同时伴随归零风险**，这就是为什么整个模型的风控核心是"崩盘概率"。

模型是**规则 + 统计推断**，不含机器学习（仓库历史里有 `重构为地板看板，删除 ML 预测模块`）。

---

## 1. 价格生成器（服务端行为复刻）

`simulator.py:StockSimulator` 逐行复刻 BlockStreet 的 `StocksRandomizer` + `InterestRateScheduler`。

### 1.1 三条硬边界

```java
floor   (minLimit) = initialPrice × 0.6 / risk
ceiling (maxLimit) = initialPrice × 3 × risk
dangerZone         = floor + (initialPrice − floor) × 0.01
```

`risk` 是服务器的风险等级 1–5。**`initialPrice` 服务端 API 不返回**（见 §2），
所以实际用「反推」拿到：`initial = floor × risk / 0.6`（`floors.py:infer_initial_price`）。

代入即得：

| risk | floor / initial | ceiling / initial | danger zone 上界距地板 |
| ---: | ---: | ---: | ---: |
| 1 | 0.60 | 3 | +0.67% |
| 2 | 0.30 | 6 | +2.33% |
| 3 | 0.20 | 9 | +4.00% |
| 4 | 0.15 | 12 | +5.67% |
| 5 | 0.12 | 15 | +7.33% |

> danger zone 上界 = `floor + (initial − floor) × 1%`，也就是"地板↔初始价这段距离的 1%"。
> 换算成相对地板的高度，risk 越高这条带越宽（0.67% → 7.33%）。
> 价格**低于**这条上界才可能崩盘——这就是"危险区"。

### 1.2 每 tick 的报价（`simulator.py:_get_random_quote`）

```
signal    = 50% 涨 / 50% 跌                            (getRandomSignal: nextBoolean)
step      = U(0, 1.5 × risk) / 100                     (getRandomQuoteAsPercentage)
noise     = 概率 P_noise = 0.01 × (risk × 5) = 5% × risk 时
            叠加 U(10%, 30%) 的冲击                    (generateNoise)
quote     = step + noise
change    = current × quote

# 触边翻转（地板机制的核心）
if 看涨 and current + change > ceiling:  quote = −quote
elif 看跌 and current − change > floor:   quote = −quote   # ← 强制反弹，这就是"地板"
else:                                      quote = |quote|

newPrice  = current + current × quote
```

两个关键推论：

- **地板不是墙，是翻身**：跌到会穿地板时，信号被强制翻成正，价格从地板弹起
- **噪声尖峰是 10%~30% 的大跳**，而正常步长上限只有 `1.5×risk%`（risk=1 时 1.5%）。
  两者之间有明确间隙（risk=1：1.5% ~ 10% 之间不会有值）→ 可以 **100% 识别**噪声尖峰
  （`strategies.py:detect_noise_spike`）

### 1.3 崩盘（`simulator.py:_should_crash`）

```
每 tick 归零概率 P = 0.3 × (1 + 0.35 × aggr)^risk − 0.35     # 下限截 0
仅当 价格 < dangerZone 时才判定（canCrash）
判中就 close = 0，公司破产，持仓作废
```

`aggr` = 服务端 config 的 `StockCrash.Aggressiveness`。

**`aggr` 的推断是本项目最重要的结论**（`backtest.py:infer_aggressiveness`，极大似然，
观测 = 每家公司的 (risk, danger zone 内 tick 数, 是否已破产)，对 aggr ∈ [0,1] 网格搜索最大化似然）：

| risk | 按默认 aggr=0.7 | **实测推断 aggr≈0.14** |
| ---: | ---: | ---: |
| 1 | 2.3% | **0.0%** |
| 2 | 11.5% | **0.0%** |
| 3 | 22.9% | **0.0%** |
| 4 | 37.1% | 1.3% |
| 5 | 54.7% | 3.1% |

**结论翻转**：按 aggr=0.7 算时"risk≥3 不可交易"；按实测 0.14 算，**risk 1–3 崩盘概率为 0**。
这条直接决定 mod 里该不该给 risk 3 标红。

> 该推断只基于 2 家 risk=5 破产样本，样本量小，**上线前应重新跑一次** `infer-aggressiveness`。

---

## 2. 地板价是怎么得到的（三个坑）

`floors.py:compute_floor_state`

1. **`initialPrice` 拿不到** → 只能反推（§1.1）。所以 danger zone 是**估值**，不是服务端原值。
2. **地板用「全历史最低价」代替**（`historyLow`）：
   - 触过底的成熟公司：`historyLow ≈ minLimit` 精确成立
   - 刚创新低的新公司：`historyLow` 是**当前 tick 刚创的**，下一 tick 可能又被打破 →
     **地板在动，不能当买点**
3. **K 线窗口有界**（约 661 点 ≈ 6.9 天），远古触底会滚出窗口 →
   用 SQLite `floor_cache` 缓存全历史最低，每次刷新取 `min(cached, kline.low)`
   （`db/stores.py:FloorCacheStore`）

> **mod 侧注记（2026-09-28）**：这三个坑**在 mod 里已经绕开**——地板直接读服务端 `/api/floors`，
> 既不反推 `initialPrice`，也不用「全历史最低价」当代理（`FloorGauge.MODE = FLOOR`）。
> 上面的 `historyLow` / `floor_cache` 是 BCquant 离线侧的做法，保留作参考。

## 3. 状态机（算法的输出）

### 3.1 三个输入量

```
distance_pct    = (最新价 − floor) / floor
low_tick_age    = 距「创下 floor 的那根 K 线」过去了多少 tick
bounce_confirmed = low_tick_age ≥ 10  且  触底后存在某根 close > floor × 1.05
```

`BOUNCE_MIN_TICK_AGE = 10`、`BOUNCE_MARGIN = 0.05` 是 Phase 0 用 live K 线校准的
（K=10 / margin=5% 时，所有触过底的公司 100% 能被确认）。

### 3.2 判定顺序（严格按此顺序，先命中先返回）

```
1. 不在 10% 以内                        → safe              🟢 安全
2. 在 10% 以内但不在 5% 以内             → near              🟡 接近
3. 在 5% 以内 且 反弹未确认              → new_low_warning   🟣 新低警告（不买）
4. 在 5% 以内 且 反弹已确认 且 在危险区   → floor_confirmed_high / _low（按 risk≥3?）
5. 在 5% 以内 且 反弹已确认 且 不在危险区 → floor_confirmed_low / _high（按 risk≤2?）
```

| 状态 | 含义 | 交易含义 |
| --- | --- | --- |
| `safe` | 距地板 > 10% | 观望 |
| `near` | 距地板 5–10% | 进观察名单 |
| `new_low_warning` | 触底但反弹未确认，**地板可能仍在下移** | **不买**（这是最容易亏的一档）|
| `floor_confirmed_low` | 触底 + 反弹确认，risk ≤ 2 | 可买 |
| `floor_confirmed_high` | 触底 + 反弹确认，risk ≥ 3 | 可买但崩盘风险高 |

`crash_risk_per_tick` 只有在 `in_danger_zone` 时才 > 0，否则直接归零
（这是把"危险区外零风险"精确化了，修掉了原先"哪都算概率"的错）。

### 3.3 排序与事件

- 排序键：`(状态档位, −崩盘风险, distance_pct)`
  档位 `new_low_warning(0) → confirmed_high(1) → confirmed_low(2) → near(3) → safe(4)`
- **入区事件**：状态从"非入区集合"变为 `{new_low_warning, floor_confirmed_*, near}` 时写一条事件
  （`floors.py:_ENTRY_STATUSES`）→ 这就是"某家公司刚进入买点"的时间戳，写进 `floor_events` 表

---

## 4. 策略层（怎么真的下手）

`strategies.py`，全部实现同一个 `decide(ctx) -> Action{signal, size}` 接口。

| 策略 | 买点 | 卖点 | 默认参数 |
| --- | --- | --- | --- |
| `floor-rebound` | 状态 ∈ {confirmed_low, confirmed_high} 且崩盘风险 ≤ 0.5 | 浮盈 ≥ take_profit | TP=2.0(200%)，size=0.50 |
| `noise-spike` | 检测到 **向下噪声尖峰**（\>10% 暴跌）且不在危险区；或常规触底 | 暴涨尖峰时 TP/2 止盈；常规 TP | TP=0.5，size=0.30 |
| `hybrid` | `risk ≤ 3` 用 noise-spike，`> 3` 用 floor-rebound | 同子策略 | risk_threshold=3 |
| `buy-hold` | 首个 bar 全仓 | 不卖 | 基准 |
| `sma-cross` | 10/30 均线金叉/死叉 | 死叉 | 已被证伪（无 edge）|

### 为什么"200% 止盈"这种反直觉参数

调优结论（蒙特卡洛 100 runs × 2000 ticks）：

1. **take_profit 随崩盘风险反向变化**：
   - aggr=0.7（崩盘凶）→ TP=2.0，必须让利润跑，因为退出主要靠崩盘清仓，早止盈会截断大反弹
   - aggr=0.14（崩盘弱）→ TP=0.5 最优，及时止盈更高效
2. **risk ≤ 2 才有正期望**（aggr=0.7 视角）；切到 aggr=0.14 后 risk 1–3 都为正、零破产
3. **size 与 risk 反向**：低 risk 用 0.5，高 risk 降到 0.1–0.2
4. `max_crash_risk_for_buy=0.5` 只在 risk=5（P≈54.7%）时生效

### 策略横向对比（蒙特卡洛 100×2000）

| risk | 策略 | 平均收益 | 胜率 | 破产率 | Sharpe |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 (aggr=0.7) | floor-rebound | +139% | 62% | 9% | 0.64 |
| 1 (aggr=0.7) | noise-spike | +110% | **86%** | 9% | **0.79** |
| 1 (aggr=0.7) | buy-hold | +9% | 49% | 9% | 0.15 |
| 1 (aggr=0.7) | sma-cross | −13% | 34% | 9% | −0.32 |
| 1 (aggr=0.14) | hybrid | +71% | 88% | **0%** | **1.08** |
| 2 (aggr=0.14) | hybrid | +100% | 90% | **0%** | 0.82 |
| 3 (aggr=0.14) | hybrid | +136% | 90% | **0%** | 0.97 |
| 4 (aggr=0.14) | hybrid | +258% | 56% | 28% | 0.48 |

组合分散（aggr=0.14）：risk 1–3 三公司 floor-rebound → +1988%、99% 胜率、**0% 破产**。

### 真实数据对照（不是模拟）

| 公司 | risk | 策略 | 收益 | buy-hold |
| --- | ---: | --- | ---: | ---: |
| HESCO商贸 | 2 | floor-rebound | +254.7% | −52.0% |
| 帕拉伦铁路 | 2 | floor-rebound | +34.4% | −19.8% |

---

## 5. 回测引擎的假设（移植时必须知道）

`backtest.py:run_backtest` / `run_monte_carlo` / `grid_search` / `run_portfolio_monte_carlo`

- **成交价 = 该 bar 的 close**，无滑点、**无手续费**、无最小变动单位
- 前 `warmup=30` 根 bar 不交易（用来积累 FloorState 历史）
- 买入按 `size` 比例花现金：`shares = int(cash × size / price)`，
  且受 `available_shares`（剩余股数）上限约束 → 模拟了"售罄买不到"
- 卖出按比例减仓，清仓时 `avg_cost` 归零
- 价格归零 → `handle_bankrupt()` 直接清仓、权益归现金（**没有止损机会**，这是真实规则）
- 方向准确率 = 信号方向与**下一根 bar** 实际涨跌是否一致

⚠️ 这些假设和真实游戏有差距（游戏里成交是否等于展示价、有没有手续费，都还没验证）。
移植到 mod 时，**回测结论只能当参考，不能当保证**。

⚠️ **参数代际不一致**：`simulator.py` 默认 aggr 已是 0.14（实测），
但 `FloorReboundStrategy` 的默认参数（TP=2.0、max_crash_risk=0.5）是在 **aggr=0.7** 下调出来的；
`NoiseSpikeStrategy` 的（TP=0.5、size=0.3）才是 0.14 时代调的。混用会有偏差，移植时要重新跑 tune。

---

## 6. 移植到游戏内 mod 的接口设计

### 6.1 决策层输入 / 输出

```
输入  K线序列(15m，从 API 或本地累积) + risk_level + 当前持仓与均价 + 剩余股数
       ↓  §1 §2 §3 全部照搬
输出  { status, distance_pct, floor, danger_zone, in_danger_zone,
        low_tick_age, bounce_confirmed, crash_risk_per_tick, reason }
      + { signal: BUY/SELL/HOLD, size, strategy_name }
```

`FloorState.to_dict()` 已经是现成的序列化结构，直接作为 mod 内部数据结构照抄即可。

### 6.2 展示层直接用得上的字段

| HUD/覆盖层要显示的 | 取自 |
| --- | --- |
| 距地板 % | `distance_pct` |
| 距地板（**只画一行文字，进度条未实现**）| `distance_pct`（服务端给）；要定位区间可用 `current` / `floor` / `ceiling` |
| 附近警告颜色 | `status` 五档 |
| 崩盘风险 | `crash_risk_per_tick`（危险区内才非 0）|
| 反弹确认 | `bounce_confirmed` / `low_tick_age` |
| 信号 | 策略输出的 `signal` + `size` |

### 6.3 执行层的动作映射

| 策略信号 | 游戏内动作（**2026-09-28 现行**）|
| --- | --- |
| 建议买入 | 盘面「买」→ 数量（`1` / `10` / 自定义）→ 确认屏 **按回车** → 发 `/invest buy <market_id> <数量>` |
| 建议卖出 | 盘面「卖」→ 数量（含「全部抛出」）→ 确认屏 **按回车** → 发 `/invest sell <market_id> <数量>` |
| 自动执行 | ⚠️ 支持（`auto.mode=limited` / `full` 才会自动发一条，默认 `advice` 只出建议）；**改档前请先确认服务器规则允许客户端自动化** |

> ~~旧设计：右键 1 股 / Shift+右键 10 股 / Shift+左键 100 股，点容器格子~~ —— **已作废**：
> 点公司格子是真钱（红线 1）；写操作改走聊天命令后，容器点击这条路径已从代码里消失。
> 卖出通道也已实测（不是「完全空白」）：`/invest sell` 有回执、卖出不另扣税。

---

## 7. 能直接搬 / 必须重写的

| 模块 | 处置 | 说明 |
| --- | --- | --- |
| `floors.py` 全部公式 | ✅ 照搬 | 纯数学，语言无关 |
| `simulator.py` | ✅ 照搬 | 蒙特卡洛验证 + 回测都要它 |
| `strategies.py` | ✅ 照搬 | 调参要按新 aggr 重跑 |
| `backtest.py` | ⚠️ 只作离线参考 | 不用进 mod，但结论影响默认参数 |
| `data.py`（Pleasance 客户端）| ⚠️ 要重写 | mod 里用 Java HttpClient |
| `db/*`（SQLite）| ⚠️ 要重写 | mod 侧存历史与持仓 |
| `web/*`、`frontend/` | ❌ 不要 | GUI 换成游戏内界面 |
| `semantic.py`（自然语言筛选，TypeSafe）| ❌ 不要 | 看板专用 |

## 8. 待验证项（决定这套模型在实盘是否成立）

1. **aggr=0.14 只有 2 个破产样本**，样本太少 → 用更长时间的数据重跑 `infer-aggressiveness`
2. ~~游戏内成交价 = 展示价吗？有手续费/滑点吗？~~ → **已实测（2026-09-26/28）**：展示价 ≠ 实时价
   （一笔 98.49 → 105.26，买入按实时价成交 ≈ +6.87%）；**卖出不另扣税**。回测的「零成本」假设要按这个改
3. **`available_shares` 售罄后**是否真的买不进（回测假设是）
4. `/invest` 界面价与 API 价同步（已抽查 3 家一致，仍可加样本）；但 `/invest company info` 的**实时价**
   与这两者都不同（见第 2 条），下单取的是它
5. ~~卖出通道：卖出口位置、卖价规则、有无折价 —— 完全空白~~ → **已实测**：`/invest sell` 可用、有回执、
   卖出不另扣税；还没验的是大额卖出有没有滑点
6. `new_low_warning` 状态下"地板可能仍下移"的实测频率（决定这一档能不能放宽）
