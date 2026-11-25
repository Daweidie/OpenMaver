# JumpReset / Grim 排查：2026-11-25 20:20 之后

## 结论

首要问题是空中重置攻击的疾跑时序。JumpReset 在一次输入回调中开启疾跑并立即攻击；本地原版攻击产生水平 ×0.6，但所提供 Grim 源码根据上一移动帧保存的 `lastSprinting` 判断攻击减速。当前帧刚发出的 START_SPRINTING 不会立刻更新这个字段。

20:28:08 唯一一次记录到的 `AIR ATTACK` 与 AntiKB / Simulation 的大偏差高度吻合。用生产 JumpReset 回调的真实 Netty 出站包，结合引用源码的疾跑/攻击状态规则，可以复现同类差异。该验证覆盖攻击前的速度向量，未运行完整 Grim 碰撞预测，不能当成服务器端精确复现或修复验证。

## 数据范围

仅分析北京时间 **2026-11-25 20:20:00 之后**；本轮会话实际为 20:25–20:28。

- 实机动作日志：`/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/logs/openmaver.log`，从第 221726 行开始。
- Grim 告警来自同目录 `latest.log` 的聊天记录。实机 `openmaver.log` 本身没有保存 Grim 告警。
- 仓库内 `logs/openmaver.log` 包含本地回归输出，不是本轮实机证据。
- 引用源码：`references/anticheats/Grim`，提交 `f319e165e`。日志没有服务端 Grim 版本，未确认部署版本与引用源码完全一致。

| 时间 | 检查 | 次数 | 日志中的偏差 |
| --- | --- | ---: | --- |
| 20:26:40–41 | Hitboxes | 5 | `type=player` |
| 20:27:02–03 | Simulation | 3 | `.009476`、`.004753`、`.001593` |
| 20:27:09–12 | AntiKB + Simulation | 各 3 | `.142840`、`.147454`、`.142922` |
| 20:28:08 | AntiKB + Simulation | 1 + 2 | `.341079`（两检查）、`.290806`（Simulation） |
| 20:28:21、34 | Simulation | 2 | `.007287`、`.003224` |

总计 19 条告警：AntiKB 4、Simulation 10、Hitboxes 5。这是告警条数，不是累计 VL。

开启动作调试后的样本有 36 条自身 S12：24 次 `PASS RESET SKIPPED`、11 次地面重置就绪、1 次空中重置就绪；实际记录 11 次 `JUMPRESET` 和 1 次 `AIR ATTACK`。样本没有 `OUT HOLD`，S12 调试行的 Timer 均为 `1.00`。因此这段大偏差首先应检查攻击预测的一致性，现有记录没有显示 JumpReset 挂包或调整 Timer。

## 20:28:08 的证据链

实机 `openmaver.log` 第 223649–223667 行：

```text
20:28:08.511  C06，位置 (179.82, 60.10, 154.48)，ground=false
20:28:08.528  S12 / PASS AIR RESET READY，v=(-0.30, 0.38, 0.80)
20:28:08.565  RESET AIR MOVE，v=(-0.30, 0.38, 0.80)
20:28:08.566  C0B
20:28:08.566  RESET AIR ATTACK，v=(-0.18, 0.38, 0.48)
20:28:08.567  C0A → C02 → C0B → C06
20:28:08.616  新 S12，位置回到 (179.52, 60.49, 155.28)
20:28:08.617  C06 确认该位置
```

同秒 `latest.log` 第 135–137 行报告 AntiKB `.341079`、Simulation `.341079` 和 `.290806`。位置变化与服务端校正相符，但当前 Velocity 日志没有直接记录 S08，不能仅凭该变化确认校正包。

本地水平速度恰好乘了 0.6。若 Grim 没有允许本次攻击减速，起始水平向量差约为：

```text
sqrt((0.30 × 0.4)² + (0.80 × 0.4)²) = 0.341760
```

这个量级与 `.341079` 高度接近。输入加速度、原始 S12 精度、碰撞和预测容差会影响最终 offset；日志只保留两位小数，不能据此计算完全相同的服务器 offset。

## 源码对应

1. [JumpResetMode.java:69](../mc/antikb/JumpResetMode.java#L69) 写 `forward=1` 并调用 `setSprinting(true)`，随后在同一回调执行 `tryAirAttack()`。第 94 行调用原版攻击入口。
2. [OpenMaverFeatures.java:1051](../mc/OpenMaverFeatures.java#L1051) 的 `syncSprintBeforeAttack()` 保证必要的 C0B 在攻击之前，并更新客户端 `serverSprintState`。这个字段表示客户端已经发过的状态，不能代表 Grim 的 `lastSprinting`。
3. [PacketEntityAction.java:41](../references/anticheats/Grim/common/src/main/java/ac/grim/grimac/events/packets/PacketEntityAction.java#L41) 收到 START/STOP_SPRINTING 时仅更新 `isSprinting`。
4. [PacketPlayerAttack.java:119](../references/anticheats/Grim/common/src/main/java/ac/grim/grimac/events/packets/PacketPlayerAttack.java#L119) 根据 `lastSprinting` 或击退附魔决定攻击减速。普通无击退附魔的 1.8 攻击，在 `lastSprinting=false` 时不会加入 ×0.6 分支。
5. [MovementCheckRunner.java:633](../references/anticheats/Grim/common/src/main/java/ac/grim/grimac/predictionengine/MovementCheckRunner.java#L633) 在处理移动后才把 `isSprinting` 保存为 `lastSprinting`。

因此能够发生：

```text
上一移动帧未疾跑
→ 本帧 START_SPRINTING：isSprinting=true，lastSprinting=false
→ 本帧 ATTACK：客户端执行 ×0.6；Grim 不加入攻击减速
→ 原版攻击结束疾跑，发 STOP_SPRINTING
→ MOVE：双方按不同速度预测；lastSprinting 仍可能保存为 false
```

日志没有打印 C0B 的 action，所以 START/STOP 的具体类型是根据生产路径和下述复现推断；缺少服务端预测跟踪，不能声称已经直接读到事发时 Grim 的字段值。

## 验证与现有测试缺口

临时验证程序 `/tmp/OpenMaverJumpResetGrimProbe.java` 调用当前构建的生产 JumpReset，通过现有 Netty Fixture 获取出站包；再按引用 Grim 源码的字段更新规则消费包。固定普通玩家目标、无击退附魔、客户端伤害被接受：

```text
上一帧未疾跑：
START_SPRINTING → C0A → ATTACK(lastSprinting=false) → STOP_SPRINTING → MOVE
clientXZ=(-0.180000,0.480000)
GrimXZ=(-0.300000,0.800000)
起始向量差=0.341760

上一帧已疾跑：
C0A → ATTACK(lastSprinting=true) → STOP_SPRINTING → MOVE
clientXZ=(-0.180000,0.480000)
GrimXZ=(-0.180000,0.480000)
起始向量差=0.000000
```

两种场景均通过验证程序的断言。这证明当前路径存在与引用 Grim 规则不一致的条件，没有证明全部实机告警都来自它。

[Protocol18Test.java:121](../tools/attackreduce-test/Protocol18Test.java#L121) 目前把“未疾跑 → START → 攻击 → 移动”作为成功包序，但没有模拟 `lastSprinting`，因此原有测试无法发现这个问题。

## 其余告警与后续方向

- 20:27:09–12 的三次 AntiKB 发生在 20:27:26 开启逐包调试之前。偏差量级与攻击减速问题相容，但缺少当时 S12、动作及移动记录，不能逐次确认。
- Hitboxes 表示服务端视线没有命中目标的碰撞箱，不能仅凭它认定丢失击退。当时同时开启 KeepSprint、AutoClicker 和 BlockHit；应检查相机用于选目标的方向与发给服务器的静默移动方向是否一致。该时段缺少逐包数据，现阶段只是检查方向。
- 20:28:21 和 20:28:34 的小 Simulation 均附近有地面 JumpReset、AutoClicker 攻击和疾跑切换。当前日志没有 C0B action、C02 目标/action、C03 yaw/pitch、精确位移或各阶段 sprint 状态，无法唯一归因。
- 修复空中路径应以已经经过正常移动帧的疾跑状态为依据决定是否攻击；本帧刚开启疾跑时保留原始击退，待后续正常帧满足条件再尝试，并保留窗口过期、落地、目标失效和共享攻击名额的限制。单纯提前 C0B、改随机概率或提高阈值不能解决这个状态差异。还需防止同帧 AutoClicker 经统一攻击入口触发同类情况。
- 下一轮调试应补充上述包字段，并让回归测试覆盖“前一移动帧未疾跑”和“前一移动帧已疾跑”两种条件。完整修复仍需在实际服务器上重测。

本次交付为日志和源码排查记录；未改动运行逻辑或游戏配置。
