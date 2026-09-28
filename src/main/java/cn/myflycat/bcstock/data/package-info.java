/**
 * 数据层（计划 §4）。D0 统一快照已落地：{@link cn.myflycat.bcstock.data.CompanyView} /
 * {@link cn.myflycat.bcstock.data.HoldingView} / {@link cn.myflycat.bcstock.data.WalletView}
 * 三条源归一到同一个形状，界面只吃 {@link cn.myflycat.bcstock.data.StockSnapshot}。
 * D1 命令网关：{@link cn.myflycat.bcstock.data.CommandGateway}（单飞 / 节流 / 静默失败），
 * 解析器在 {@code data.reply}。D2：{@link cn.myflycat.bcstock.data.ApiClient} /
 * {@link cn.myflycat.bcstock.data.SnapshotJournal} / {@link cn.myflycat.bcstock.data.DegradePolicy}。
 * 本包仍然<b>不引用</b> {@code net.minecraft}。
 *
 * <p>本包<b>不引用任何 {@code net.minecraft} 类型</b>，全部能跑离线测试——这是刻意的：
 * 数据是错的比界面难看严重得多。
 *
 * <p>三个数据源，<b>不可互相替代</b>：
 * <ul>
 *   <li>公开 API {@code https://tool.myflycat.cn/quant/api/*}（BCquant）—— 行情 /
 *       K 线（{@code /api/history/candles}）/ 地板（{@code /api/floors}，服务端直接给
 *       {@code floor}，<b>不反推</b>）。可用 {@code -Dbcstock.api.base} 覆盖。
 *       HTTP 用 JDK 自带的 {@code java.net.http.HttpClient}，不引 OkHttp。</li>
 *   <li>{@code /invest} 容器 —— <b>个人持仓 / 成本 / 资产只有这里能读到</b>
 *       （API 的个人资金线已死，{@code latest_cash_at} 停在 2026-07-08）。
 *       由 {@code cn.myflycat.bcstock.probe} 采集。</li>
 *   <li>本地积累 —— 游戏根目录 {@code bcstock/<玩家UUID>/store} 的 H2 文件库
 *       是真相（schema 可增量迁移）。旧 JSONL / 行情缓存仅在该 UUID 的库为空时导入一次。
 *       不引 SQLite；MySQL 以后再说。</li>
 * </ul>
 *
 * <p>必须做降级：API 挂了只显示 GUI 能拿到的数据，功能降级但不崩。
 */
package cn.myflycat.bcstock.data;
