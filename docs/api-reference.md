# 公开 API 参考（BCquant / tool.myflycat.cn）

> **性质声明**：这是一凡自己的 BCquant FastAPI（`https://tool.myflycat.cn/quant`），
> 数据与游戏官方采集同源但是超集。旧站 `https://www.pleasance.icu` 仍可能存在，
> 但 **mod 默认已迁到本 base**。字段和路径以本节 2026-09-27 实测为准。

## 基础

```
BASE = https://tool.myflycat.cn/quant
```

⚠️ **带路径前缀**，不是域名根。

**配置优先级**：JVM `-Dbcstock.api.base` / `-Dbcstock.api.timeoutMs`
＞ 配置文件 `config/bcstock.json` 的 `api.*` ＞ 硬编码默认。
面向用户请改配置文件或 ModMenu；验收文档里的 `-D` 步骤仍有效（覆盖优先）。

- 默认超时 **15s**（`api.timeoutMs`，可配）。实测 `/api/companies` 冷启动 1.3~9s，旧 5s 会偶发超时。
- 客户端启动会先读本地 `bcstock/market-cache.json`（秒开），再后台刷新；
  成功则覆盖缓存。失败保留旧数据并在底栏显示**数据年龄**，不因单次失败清空盘面。
- `market-cache.json` 与 `snapshots.jsonl` **并存**：前者可覆盖的最新行情，后者 append-only 历史。
- 缓存恢复时 **`market_id` 一律抹成未知**——只有当次 API 确认后才允许下单。

- 全部免鉴权 `GET`（mod **绝不**调会改服务状态的 `POST`）
- 时间戳：`time` 字段是**毫秒级** Unix 时间戳；`*_at` 类字段是 ISO 8601 字符串
- `interval` 只接受四档：`15m` `1h` `4h` `24h`
- 非法 interval → `400 {"error": "invalid interval, ..."}`
- 不存在公司：**本 mod 实际调用的 `/api/history/candles` 返回 `200` + 空 `candles`**，不按 404 处理；
  `404 {"error": "not found"}` 是本 base 上旧路径（`/api/kline/{id}`、`/api/company/{id}`、`/api/available-shares/{id}`）的行为

## 接口清单（mod 使用）

| 路径 | 作用 | 状态 |
| --- | --- | --- |
| `/api/health` | 采集器健康（**mod 拉行情不再先探**；脚本/验收仍可单独 GET） | ✅ |
| `/api/companies` | 全部公司列表（`fetch()` 只打这一次） | ✅ 字段名与旧服务一致 |
| `/api/history/candles?company_id={id}&interval=15m[&limit=N]` | K 线序列 | ✅ **替代**旧 `/api/kline/{id}` |
| `/api/floors` | 地板监测快照 | ✅ 新；服务端已算好 |

## 旧服务端点（本 base 上不存在 / 不用）

| 路径 | 说明 |
| --- | --- |
| `/api/kline/{id}` | **404**。请用 `/api/history/candles` |
| `/api/company/{id}` | 旧 pleasance 有；本 base **404**（2026-09-28 实测），未使用 |
| `/api/available-shares/{id}` | 旧 pleasance 有；本 base **404**（2026-09-28 实测）；剩余股数改从 `/api/companies` 的 `available_shares` 拿 |

> `/api/overview` 不在上表：**本 base 上存在（200，含 concentration / events 等字段）**，只是 mod 没用到
> （样本未随仓库发布）。
| `POST /api/floors/refresh` | **禁止**。改服务状态；后台 poller 自己刷 |

旧 pleasance 接口的键名可能是 `data` 而非 `candles`，引用旧接口时注意。

## /api/health

```json
{"companies":54,"last_update_marker":"...","latest_cash_at":"...",
 "latest_price_at":"...","prices":115652}
```

> `prices` 是**活数字**（2026-09-28 线上 119700），引用时现拉。

`latest_cash_at` 停在 7 月 8 日 → **个人资金数据源已死**，别指望它。

## /api/companies

数组，字段名与旧服务**完全一致**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | number | API 内部 ID（**用它去请求 candles / 映射地板 company_id**）|
| `market_id` | number | 页面展示的市场编号（和 id 不是一回事）|
| `name` | string | 公司名（中文）|
| `risk_level` | number | 风险度 1–5 |
| `status` | string | `交易中` / `已破产` |
| `latest_price` | number | 最新价 |
| `change_pct` | number / null | 涨跌幅百分比，无可比数据时为 null |
| `available_shares` | number / null | 剩余可售股数，破产公司为 null |

```json
{"id":10,"market_id":0,"name":"帕拉伦联合储蓄","risk_level":1,
 "status":"交易中","latest_price":61.76,
 "change_pct":-0.19392372333549537,"available_shares":159727}
```

> `id` 与 `market_id` 是两个体系。GUI 按 `market_id` 排，请求 K 线必须用 `id`。

## /api/history/candles?company_id={apiId}&interval=15m&limit=500

**替代**已 404 的 `/api/kline/{id}`。

```json
{"company_id":10,"interval":"15m",
 "candles":[{"time":1783526790070,"open":144.14,"high":144.14,"low":144.14,"close":144.14}, ...]}
```

- 🔴 数组键叫 **`candles`**（不是旧服务的 `data`）。mod 解析 **candles 优先，data 兜底**。
- 🔴 `company_id` 传 **`CompanyView.apiId()`**（companies 里的 `id`），不是 `marketId()`。
- mod 默认带 `limit=500`（全量 7k+ 点每帧 Bresenham 会卡核显）。
  返回不足 500 条（早期历史短）照常渲染。
- 最高/最低是**窗口内**的，不是全历史。
- 验收口径：渲染点数 == **本次请求实际返回的** candles 条数。

**注意**：每根 bar 的 OHLC 通常四个值完全相同 → 单点采样塞进 OHLC 结构，
不是真正的区间聚合。

## /api/floors

服务端后台轮询缓存的地板监测快照。`poll_interval=300`（5 分钟）→ 可能滞后。

```json
{"interval":"15m",
 "floors":[{"company_id":10,"company_name":"帕拉伦联合储蓄","risk_level":1,
   "current":61.76,"floor":60.0,"initial_price":100.0,"danger_zone":60.4,
   "distance_pct":2.9333,"low_tick_age":5590,"bounce_confirmed":true,
   "in_danger_zone":false,"status":"floor_confirmed_low",
   "crash_risk_per_tick":0.0,"reason":"..."}],
 "external_source":"...","last_refreshed":1790505689.95,"last_error":null,
 "last_external_error":null,"poll_interval":300.0}
```

硬约束：

- 🔴 `floor` 是**服务端已算好的地板价**，直接读，**不要**用 `floor × risk / 0.6` 反推。
- 🔴 `distance_pct` = `(current - floor) / floor × 100`（实测）。
- 🔴 **`floors` 只覆盖还活着的公司**（破产的无）。不在表里 → 地板显示未知，
  不许当 0、不许当「安全」。
- 🔴 `in_danger_zone` / `crash_risk_per_tick` / `status` 直接用，别自己重算。
- ⚠️ `external` / `external_match` 可疑，**不要依赖**。
- ⚠️ 检查 `last_error` / `last_external_error` 是否非空，以及 `last_refreshed` 新鲜度。
- 🔴 **绝对不要调 `POST /api/floors/refresh`**。

## 本机已有的历史成果（可复用）

- BCquant 采集库（在本机，不在本仓库内）
- 源码只读参考：[BCquant](https://codeberg.org/myflycat/BCquant)（**不许改**）
