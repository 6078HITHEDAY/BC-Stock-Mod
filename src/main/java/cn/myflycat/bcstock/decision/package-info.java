/**
 * 决策层。
 *
 * <p>算法从 BCquant 移植（只读参考上游 BCquant 仓库），<b>不要重写</b>。
 * 对接蓝本见 {@code docs/decision-model.md}：
 * <ul>
 *   <li>{@code bcquant/floors.py} → 服务端已算好并经 {@code GET /api/floors} 下发；
 *       mod 侧直接读 {@code floor / distance_pct / status / crash_risk_per_tick}，
 *       <b>不要</b>再用 {@code floor × risk / 0.6} 反推 {@code initialPrice}。</li>
 *   <li>{@code bcquant/simulator.py} → 照搬（蒙特卡洛 + 回测要用）。</li>
 *   <li>{@code bcquant/strategies.py} → 照搬，但参数要按实测 {@code aggr} 重跑调优。</li>
 *   <li>{@code bcquant/data.py}、{@code bcquant/db/*} → 用 Java 的 HttpClient / JSONL 重写。</li>
 *   <li>{@code web/}、{@code frontend/}、{@code semantic.py} → 不要。</li>
 * </ul>
 *
 * <p>方向预测已被实证放弃（准确率 50.01%，等于抛硬币）——<b>不要再加方向预测</b>。
 * 只在一个边际上出手：触底反弹。
 *
 * <p>出手建议走 {@link cn.myflycat.bcstock.decision.DecisionEngine}（{@link cn.myflycat.bcstock.decision.DecisionPreset}
 * 门槛：距地板 / 止盈 / 止损 / 风险）。盘面建议仍只给人看；
 * {@link cn.myflycat.bcstock.decision.AutoTradePlanner} 在 limited/full 下最多挑一笔，发令由数据层执行。
 * 五档状态机不在本期。
 *
 * <p>L1 提醒仍是 {@link cn.myflycat.bcstock.decision.AlertRules}，与建议分开。
 *
 * <p>这一层是纯数学，<b>不用起游戏就能测</b>：拿 BCquant 的 Python 输出当黄金样本逐 tick
 * 对照 {@code floor / danger_zone / crash_risk / status}。
 */
package cn.myflycat.bcstock.decision;
