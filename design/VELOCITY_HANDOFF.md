# Velocity（防击退）实现说明

> **2026-11-16 19:1x 结论（18:53 那局：`Simulation` vl 涨到 83、`AntiKB o:.046~.254`）**
>
> 这一轮是拿 Grim 2.0 的源码对的（`PacketPlayerAttack` / `MovementCheckRunner` /
> `PredictionEngine` / `KnockbackHandler`），不是靠猜。结论：`SPRINT SLAP` 的
> 「用冲刺攻击的 ×0.6 削击退」这条思路是对的，但**分支成立条件用错了**。
>
> ### 0.6 分支到底什么时候存在
>
> ```java
> // common/.../events/packets/PacketPlayerAttack.java
> final boolean isLegacyPlayer = clientVersion <= 1.8;
> final boolean sufficientCooldownProgress = isLegacyPlayer || attackCooldown > 0.9F; // 1.8 恒 true
>
> if (player.lastSprinting && !hasNegativeKB && sufficientCooldownProgress || knockbackLevel > 0) {
>     player.minAttackSlow++;
>     player.maxAttackSlow++;
>     if (knockbackLevel == 0) player.maxAttackSlow = player.minAttackSlow = 1; // 一 tick 只有一条
> }
> ```
>
> 也就是说：**没拿击退附魔的 1.8 客户端，0.6 分支完全取决于 `player.lastSprinting`**。
> 而它是 Grim 在 `MovementCheckRunner` 末尾赋值的
> `player.lastSprinting = player.isSprinting` —— **上一 tick 结束时的冲刺状态**，
> 不是这一 tick 的。
>
> ### 所以老实现每一下 ×0.6 都没分支
>
> ```
> tick 开头  setSprinting(true)                      ← 我们
> 紧跟       attackTargetEntityWithCurrentItem
>            └ motionXZ *= 0.6; setSprinting(false)  ← 原版（`wn.f(Lpk;)V` 已用字节码核对：
>                                                      if (flag && i>0) { pk.g(...); v*=0.6; x*=0.6; d(false); }）
> tick 末尾  冲刺状态 = false  →  next tick 的 lastSprinting = false
> ```
>
> 于是下一 tick 乘的 0.6 在服务端**没有任何分支**，offset 恰好是 `0.4 × 当前速度`，
> 和日志逐条对得上：
>
> | 日志 | 反解 | 含义 |
> | --- | --- | --- |
> | `AntiKB/Simulation: .143976` | `.143976 / 0.4 = 0.3599` | 击退那一下 `|kb| = 0.36` |
> | `.046243` | `/0.4 = 0.1156` | 窗口第 2 击的速度 |
> | `.006153 / .006157 / .006184` | `/0.4 ≈ 0.0154` | 窗口尾部残余速度（每 tick 一条，vl +1） |
> | `.235468 / .254650` | `/0.4 ≈ 0.59 / 0.64` | 被击退棒/大击退时的速度 |
>
> `vl` 每 tick +1、一路涨到 83，就是「每一下 ×0.6 都没被预测」累出来的。
>
> ### 改法
>
> | 位置 | 改动 |
> | --- | --- |
> | `ensureSprinting()` | 原版乘完 `setSprinting(false)` 之后**立刻把冲刺打开**：tick 结束时状态仍是 true → 下一发的 `lastSprinting` 是 true → 分支存在。不发 C0B（true→true，原版 `serverSprintState` 比对认为没变） |
> | `tickSprintSlap` 闸门 | `isSprinting(player) && sprintAtLastTickEnd`（`onTickEnd` 每 tick 记录）；不满足就这一 tick 不出手 —— 打了也只有 0.4·v 的 offset |
> | `ATTACKS_THIS_TICK` | 出站旁路统计 C02；本 tick 已有别的攻击（KillAura / AutoClicker）就不出手。Grim 一 tick 只给一条 0.6 分支，双刀 = 0.36 对 0.6 |
> | `beginSlap` | 窗口起点 = `kbArriveTick + 1`。Grim 的击退是「两个 transaction 夹住 S12」结算的，同批事务下一 tick 才把速度写进 `player.velocity`；早一 tick 乘的是 `0.6·v_old`（`KnockbackHandler` 的 `offsetToFlag` 默认只有 **0.001**，`AntiKB` 一 flag 就是 setback） |
> | `tickSprintSlap` / `noXzAttack` | 删掉「原版没乘就自己补乘 0.6」：`flag==false`（目标在 10 tick 无敌帧里）时原版根本不乘，服务端也没有分支 |
> | 距离 | `SLAP_MAX_REACH = 2.9`。1.8 服务端只认 3.0，超出的 C02 被丢掉 → 没有分支 |
> | 窗口长度 | `SLAP_MAX_BRANCHES = 5`（`PredictionEngine.addAttackSlowToPossibilities` 里 `Math.min(maxAttackSlow, 5)`） |
> | `reconcileSprint()` | 只在「服务端已知状态 ≠ 客户端状态」时补一条 C0B（AutoSprint / Scaffold 在 tick 开头改状态时会出现，1.8 的冲刺包是 tick 末尾才补的）。配置项 `velocity.slapSprintSync`，默认开，关掉 = 老行为，仅供对测 |
>
> ### 与参考实现的关系
>
> | 参考实现 | 借鉴到的点 | OpenMaver 对应 |
> | --- | --- | --- |
> | OpenMyau-Fix `Velocity`（`Slap_Attack`） | `calculateSlapTicks` 的 `\|kb\|` 拟合、`fakeCheck`/`badPackets()` 式出手闸门、每次 slap 都是**真·冲刺攻击** | `slapTicksFor()`、`ATTACKS_THIS_TICK`、`isSprinting + sprintAtLastTickEnd` |
> | OpenZen `AntiKB/JumpResetMode` | 受击后先记后动、落地/超时才放包，不追着击退改速度 | `MODE_NOXZ` 的挂起/放包时序 |
> | OpenZen `AntiKB/NoXZMode` | `attackAmount` 连打、`onGround` 才放包 | `MODE_NOXZ`（本服有 `TransactionOrder`，扣 S32 那条默认关闭） |
> | OpenZen `AntiKB/MixMode` | 每 tick 一次 0.6，别在窗口里改别的状态 | `tickSprintSlap` 每 tick 最多一次 |
>
> ### 验证
>
> - `cmake --build build -j` 通过（reobf 后的 `VelocityFeature.class` 已进 jar）。
> - `tools/velocity-test/run.sh`：`91 passed, 0 failed` + `16 passed, 0 failed`
>   （新增 4 项：窗口起点 = `kbArriveTick+1`、冲刺同步默认开 / 可关 / 会落盘、窗口上限 ≤ 5）。
> - 实机判据：被打后 `logs/openmaver.log` 应出现
>   `sprint-slap 攻击 #n target=… 距离=1.9格 motion=(…)->(…) 原版乘过·冲刺已同步`，
>   并且 `Simulation` 的 `.006` 系列与 `AntiKB` 归零；`slapSkip` 显示
>   `没在冲刺（0.6 分支只认上一 tick 的 lastSprinting）` 或
>   `原版没乘 0.6（目标无敌帧 / 伤害没结算）` 都是**正确**的放弃。
> - `NoFall (vl:0.0)` 与本模块无关：那是 Criticals 伪造 `onGround=false` 的包
>   （`mc/OpenMaverFeatures.java` 的 criticals 段）。

---

> **2026-11-16 18:55 结论（18:48 实机：手打没击退但 vl:109，武器打仍有击退）**
>
> ### 那局跑的根本不是 SPRINT SLAP
>
> ```
> [18:48:01] 击退 #80 mode=NOXZ kb=(-0.059, 0.072, 0.000)
> [18:48:13] 击退 #100 mode=NOXZ kb=(4.096, 0.400, 4.096)
> [18:48:50] 击退 #120 mode=NOXZ kb=(-0.312, 0.321, 0.180)
> ```
>
> 18:46:55 加载时日志还是 `配置: mode=SPRINT SLAP ... -> 实际=SPRINT SLAP`，
> 但运行期 `mode` 又被写回 8（配置/GUI），于是整局实际走的是 NoXZ。这正好解释两个现象：
>
> | 现象 | NoXZ 的行为 |
> | --- | --- |
> | 手打「没有击退」 | 地面小击退走 NOXZ 的 slap 分支（`sprint-slap 攻击 #n`），5~6 次 ×0.6 后几乎为零 |
> | 武器（击退棒）「有击退」 | 大击退把人打上天 → 进挂起 → 落地时 `applyNoXzKnockback` 把 **S12 原样补给客户端**，等于全额吃回；同时扣住的 S32 触发 `TransactionOrder (vl:0.0): skipped=2` |
>
> ### vl 109 的账
>
> `AntiKB (vl:17.0): o: .239005` 紧跟一条同值 `Simulation` —— 这是在**服务端还没把
> 这次击退算进 velocity** 的时候乘了 0.6：我们乘的是 `0.6·v_kb`，它算的是
> `0.6·v_old`，差值正好是被削掉的那 .239。后面的 `.179 / .006 / .0038 / .0017`
> 是这条链按摩擦衰减的尾巴。Grim 的击退是靠 transaction 夹住 S12 结算的，
> 回执之前它不下结论。
>
> ### 这轮改动
>
> | 位置 | 改动 |
> | --- | --- |
> | `shouldCancelVelocityPacket` | 所有入站 `S32` 一律计数（`TRANSACTIONS`），收到 S12 时记住当时的计数（`kbTransactionMark`） |
> | `tickSprintSlap` | 出手前两道闸：①`TRANSACTIONS > kbTransactionMark`（服务端已结算这次击退）②击退真的进了 motion（`currentHorizontal ≥ kb*0.5`）。任一不满足就顺延，绝不拿 `v_old` 或走路速度去乘 0.6 |
> | `effectiveMode` | **NoXZ 默认降级成 SPRINT SLAP**（新配置项 `velocity.noxzTx=false`）；这样即使配置/GUI 里还留着 `mode=8`，也不会再走扣回执那条已经被 TransactionOrder 盯上的路 |
> | 配置 | 运行中的 `config/openmaver.properties` 已强制 `velocity.mode=7 / noxzTx=false / settings.version=12`，并留了 `.bak-1848` |
>
> ### 预期与边界
>
> - 手打 / 普通剑：5~6 次真·冲刺攻击 → 保留 0.6^5~0.6^6（8%~5%），`vl` 应停在 0。
> - **击退 II 武器把你打出 3 格**：这一段没有合法分支可削 —— 0.6 必须由「一次被服务端
>   接受的攻击」换来，够不着就是够不着。此时去打空气只会白送 Simulation，
>   所以模块会安静地什么都不做（日志 `sprint-slap 空转：3 格内没有可攻击实体`）。
> - 如果哪天要重新对测 NoXZ，把 `velocity.noxzTx=true` 再跑。
>
> ---

> **2026-11-16 18:45 结论（18:38 实机日志之后再修一轮）**
>
> 上一轮的 void 修复确实生效了（日志 `sprint-slap 攻击 #1~#5`，命中从 0 变 5），
> 但随即暴露出两个新问题 —— 一个让它「还是没用」，一个让它被 Grim 连着记：
>
> ### 1. 只有第 1 次攻击是合法分支，后面 4 次全是凭空少位移
>
> ```
> sprint-slap 攻击 #1 ... motion=(-0.038,0.193)->(-0.023,0.116)  原版乘过
> sprint-slap 攻击 #2 ... motion=(-0.021,0.105)->(-0.013,0.063)  本模块补乘
> ...
> [GrimAC] failed Simulation (vl:1.0): .048482 … vl 一路涨到 46
> ```
>
> 原版的 `motionXZ *= 0.6` 带 `setSprinting(false)`：第 1 击之后客户端已经不是
> 冲刺状态，后面几 tick 自己乘 0.6 在服务端**没有对应预测分支**（Grim 的
> attack-slow 只为冲刺攻击生成），于是每 tick 一条 Simulation。改法：每次 slap
> 之前 `setSprinting(true)`，让原版自己去乘 0.6 并复位冲刺 —— 和真人 w-tap
> 完全同一条路径。
>
> ### 2. 第一 tick 的大头被整段吃掉了
>
> 击退 0.36~0.4 格/tick 的第一 tick 是最大一块，原来的 `slapStartTick = 命中 tick + 1`
> 把它完整放过了。日志实测位移约 0.32 vs 原版 0.40 —— 只削 20%，体感就是
> 「Velocity 没用」。现在从命中那一 tick 就开始尝试出手，并用
> 「击退是否已经进 motion（`currentHorizontal ≥ kb*0.5`）」判断：S12 是 Netty
> 线程异步到的，没吃到就自动顺延一 tick，绝不拿走路速度去乘 0.6。
>
> ### 3. NoXZ 在这台服的 Grim 上已经死了
>
> ```
> [GrimAC] Mincx1203 failed TransactionOrder (vl:0.0): skipped=2
> ```
>
> 那是 18:38:23 的 noxz 挂起（扣了 2 tick 的 S32）。这个 Grim 构建有
> TransactionOrder 检查，「回执不回」的前提直接失效。所以 GRIM 预设改回
> SPRINT SLAP，`settings.version 12` 会把旧配置里的 `velocity.mode=8` 搬到 7；
> NoXZ 仍可在 MODE 里手动选来做对测（`noxzAttacks` 等参数保留）。
>
> ### 验证
>
> - `tools/velocity-test/run.sh`：`84 passed, 0 failed` + `16 passed, 0 failed`
>   （预设断言改成 SPRINT SLAP，附 TransactionOrder 证据注释）。
> - 实机判据：站地上被打 → 日志连续 5~6 条
>   `sprint-slap 攻击 #n ... 原版乘过`（**不该再出现「本模块补乘」连发**），
>   位移约为原版的 0.6^k，`Simulation / AntiKB / TransactionOrder` 应为 0。
>
> ---

> **2026-11-16 18:35 结论（「启用后没用」的根因，覆盖下面所有历史结论）**
>
> 两个独立 bug 叠在一起，让模块在实机上完全无感：
>
> 1. **`PlayerControllerMP#attackEntity` 在 1.8.9 是 void**
>    （`joined.srg`: `func_78764_a (Lwn;Lpk;)V`）。代码却用 `call()` 的返回值判成败，
>    而 `Method.invoke` 对 void 方法恒返回 `null` → 每一次攻击都被当成失败直接
>    `return`：C02 照发（有伤害），但 0.6 一次也没乘上，`SLAP_HITS` 永远是 0。
>    实机日志（18:20 那局）就是证据：`上次跳过=attackEntity 返回 null` +
>    `累计 命中=0 空转=34`。
> 2. **NOXZ 在地面根本不进状态机**：`noxzInboundFilter` 对「站地上 / 冷却中」的 S12
>    直接 `return false`，既不吞也不记 `pendingKb`；`applyPendingKnockback` 里又没有
>    `MODE_NOXZ` 分支，于是掉进 `default` 的 MODIFY，而 GRIM 预设的
>    horizontal/vertical 都是 100% —— 击退原封不动写回去。地面 PvP 是绝大多数场景，
>    体感就是「模块开着、什么都没发生」。
>
> ### 改法
>
> | 位置 | 改动 |
> | --- | --- |
> | `tickSprintSlap` / `noXzAttack` | `attackEntity` 改用 `callVoid` 判成败；「原版有没有乘过 0.6」仍靠攻击前后的 motion 实测 |
> | `noxzInboundFilter` | 地面/冷却放行的 S12 也要写 `pendingKb*`，否则后面没人知道发生过击退 |
> | `applyPendingKnockback` | 新增 `case MODE_NOXZ`：开 slap 窗口（每 tick 一次 ×0.6），不再落进 100/100 的 MODIFY |
> | `tickSprintSlap` | 允许 `MODE_NOXZ` 复用攻击窗口；NOXZ 挂起期间关窗（S12 还没补给客户端，此刻乘 0.6 是白乘，还会和 Grim 的预测分支错位） |
> | `tickNoXz` | 放包时不再要求「正在冲刺」（`noXzAttack` 自己先关冲刺再自乘 0.6，Grim 的 0.6 分支是按 C02 生成的）；没目标/超时也要 `beginSlap()`，不能直接放弃 |
>
> ### 验证
>
> - `cmake --build build -j` 通过（reobf 后的 `VelocityFeature.class` 已进 jar）。
> - `tools/velocity-test/run.sh`：`84 passed, 0 failed` 与 `16 passed, 0 failed`。
>   新增 5 项：`call()` 读 void 恒为 null（旧 bug 的回归钉）、`callVoid()` 成功、
>   真实调用确实发生、NOXZ 地面放行后开窗、地面放行不伪造 motion。
> - 实机判据：站地上被打，日志应出现
>   `sprint-slap 攻击 #n ... 本模块补乘`（或「原版乘过」）且 `命中` 不再为 0，
>   HUD 的 `KB n` 递增。
>
> ---

> **2026-11-16 18:00 结论（实机 flag 之后，覆盖本文后面的历史结论）**
>
> 17:47~17:49 那局（`mode=UNCERTAINTY / server=GRIM / guard=on`）在 GrimAC 上
> 同时吃了六类 flag。**没有一个是「参数不够狠」，全部是协议语义或时序写错**：
>
> | Grim check | verbose | 根因 | 位置 |
> | --- | --- | --- | --- |
> | `FabricatedPlace` | `cursor=6.3125, -2.0, -4.6875 limit=-7.45e-9` | farm 的 C08 把**世界坐标**当 cursor 发出去（协议要 `[0,1]` 的方块内相对坐标） | `farmUncertainty` |
> | `AntiKB` | `o: .167 / .262 / .624` | 吞掉 S12 后只写回了一部分（UNCERTAINTY 直接写 0）—— Grim 用两个 transaction 把 S12 夹住，再看位移里有没有这段速度；`farm` 撑开的是**碰撞/0.03 分支**的不确定度，`UncertaintyHandler` 里根本没有放置相关项 | `farmAndFreeze` / `writeHorizontal` |
> | `AntiKB` | `o: 5.58` | 憋包 + 归零造成的位移缺口，一次记一大笔 | `startBlink` + `blinkKeep=0` |
> | `Timer` | — | `releaseHeld` 在一个 tick 内补发多条 C03（`uncertaintyTicks=2` 起，连续受击时叠加） | `releaseHeld` |
> | `Simulation` | `.31 / .29 / .37 …` | 补发的轨迹是「归零后」的真实轨迹，与逐 tick 预测对不上 | 同上 |
> | `GroundSpoof` | `claimed false` / `claimed true` | 补发的包各自带自己的 `onGround`，而服务端位置已落后 1~2 tick | 同上 |
> | `BadPacketsN` | — | `shouldHoldOutbound` 按类名前缀匹配，把 `C05/C06` 也扣住了 —— **连传送确认一起憋** | `shouldHoldOutbound` |
> | `Hitboxes` | `type=player` | `fireBurst` 只按「到 P0 的距离」筛人，被轮到的目标根本不在射线上 | `fireBurst` |
>
> ### FabricatedPlace 的数值证明（不是猜）
>
> 服务端侧 cursor 被按 1/16 量化成 byte（`x*16 mod 256`）。把日志值反解：
>
> | 分量 | 日志值 | byte | 反解出的实际发送值 | 说明 |
> | --- | --- | --- | --- | --- |
> | y | `-2.0` | −32 | `62.0` | 正好是玩家脚下那格方块顶面 |
> | x | `6.3125` | 101 | `246.3125` | 与 blink 起点 `249.86` 同区 |
> | z | `-4.6875` | −75 | `187.3125` | 与 `186.96` 同区 |
>
> 三个分量都是**世界坐标**，且 `y` 精确等于脚下方块顶面 —— farm 发的是
> `mc.objectMouseOver.hitVec` 原值。修法：减去 `BlockPos`（原版
> `PlayerControllerMP` 就是这么算的），并在越界时放弃 farm。
>
> ### 为什么 UNCERTAINTY 这条路本身走不通
>
> 1. 吞 S12 = 事实上的 AntiKB。想让 offset 为 0，只能把**原始向量**写回去
>    （`MODIFY 100/100` 那条 passthrough）。任何比例 < 1 都是 offset。
> 2. farm 一个方块换不到速度容差。`UncertaintyHandler` 的字段全是
>    `pistonX/Y/Z / slimePistonBounces / onGroundUncertain / collidingEntities /
>    fishingRodPulls / …`，没有任何一项和「放置方块」有关；放置只影响
>    `PositionPlace`/`RotationPlace` 这类几何判据。
> 3. 憋包在 1.8 上必然同时踩 `Timer`（包速率）+ `Simulation`（逐 tick 预测）+
>    `GroundSpoof`（onGround 时序），因为「位置迟到」并不等于「位移消失」。
>
> ### 现在的答案：`SPRINT SLAP`（GRIM 预设默认）
>
> 用 1.8 原版就存在的机制削击退，让削减落在 Grim 的**预测分支**里：
>
> - `EntityPlayer.attackTargetEntityWithCurrentItem`（obf `wn.f(Lpk;)V`）里
>   `i = 击退附魔 + (isSprinting ? 1 : 0)`，`i > 0` 时执行
>   `motionX *= 0.6; motionZ *= 0.6; setSprinting(false)`。
>   已用 `minecraft-1.8.9-client.jar` 的字节码核对（`ldc2_w 0.6d` 两次 `dmul`
>   + `wn.d(Z)V`）。
> - Grim 的 `PredictionEngine.addAttackSlowToPossibilities` 为**每个攻击包**
>   生成一条 `(0.6, 1, 0.6)` 分支（`Math.min(maxAttackSlow, 5)`）。
> - 所以：S12 原样放行 → 每 tick 打一次准星里的实体 → 0.6^N，N 由 `|kb|` 查表
>   （沿用 OpenMyau-Fix 的 `6.43153527E-4 * |kb| + 2.9419087136`），上限
>   `velocity.slapMaxTicks`（默认 6 ≈ 95% 削减）。
> - 实现要点（`VelocityFeature`）：
>   - `shouldCancelVelocityPacket` 在 `MODE_SPRINT_SLAP` 下**只记录不放吞**；
>   - `tickSprintSlap` 只在「准星里真的有实体 + 正在冲刺 + Scaffold 本 tick 不改
>     rotation」时攻击，攻击与 ×0.6 必须成对出现（多乘一次 = 0.36 而预测只有
>     0.6 → Simulation）；
>   - 攻击走 `PlayerControllerMP.attackEntity`，**不再自己乘 0.6**（原版会乘）。
>
> ### 2026-11-16 18:2x：GRIM 预设换成 OpenZen 的 NoXZ（`velocity.mode=8`）
>
> SPRINT SLAP 的天花板是「必须有目标可打」——站着挨打就完全失效。OpenZen 的
> `AntiKB/NoXZ`（`modules/impl/combat/antikb/NoXZMode.java`）走的是另一条路：
> **动 Grim 的 transaction 回执时机**，而不是动运动数据。
>
> 移植后的状态机（`VelocityFeature` 的 NOXZ 段）：
>
> | 时机 | 动作 |
> | --- | --- |
> | 收 S12（本方、Y>0）且**不在地上** | 吞掉、记住它，进入挂起 |
> | 挂起期间 · 出站 | C03 全部扣住（服务端看不到位移） |
> | 挂起期间 · 入站 | `S32PacketConfirmTransaction` 全部扣住 —— 回执不回，Grim 的 `KnockbackHandler` 只能把这段击退当成「可能还没到」，预测里「没吃」也是合法分支 |
> | 收 `S08PacketPlayerPosLook`（被 setback） | 立刻放全部队列、退出挂起、冷却 2 tick |
> | 落地（或 12 tick 超时） | 先放 C03（不含击退的轨迹，落在允许分支上）→ 补给客户端 S12 → 连打 `NOXZ ATTACKS` 次（每次先关冲刺再自己 ×0.6） |
> | 下一 tick | 放掉扣住的 S32，Grim 这时才把击退算进自己的 velocity，两边重新对齐 |
>
> 关键细节：
> - `doAttack` 先 `setSprinting(false)` 再打：原版那段 ×0.6 需要 `i>0`
>   （击退附魔 + 冲刺），关掉冲刺就让它**不乘**，由我们乘一次 —— 每条 C02 恰好
>   对应一次 0.6，与 `addAttackSlowToPossibilities` 的分支一一对应。
> - 关模块 / 换世界 / 摘管线都必须 `stopNoXz()`：位置包丢了就是一次 setback。
> - 只扣 S32，不扣聊天/音效/实体（OpenZen 扣得更多，但那是为了它自己的渲染一致性）。
> - 没有移植 `ZenClient.serverTickRate`（改客户端 tick 速率）和 Strafe 加速 ——
>   那些在 Grim 上是 `Timer`。
>
> ### 顺带修掉的布尔读取 bug（影响面很大）
>
> `OpenMaverFeatures.number()` 只认 `Number`，而 `onGround` 是 **boolean** 字段，
> 所以 `number(player, "onGround", "field_70122_E")` **永远返回 0**。受影响的三处：
> - `writeVerticalKb` 的「落地时不写垂直」守卫从没生效 → 落地也写 motionY
>   → `GroundSpoof: claimed false`；
> - `syncRotation` 发的 C05 永远 `onGround=false` → 同一条 GroundSpoof；
> - 新写的 NOXZ 落地检测。
> 统一改成 `OpenMaverFeatures.scaffoldOnGround(player)`（`read()` + `Boolean.TRUE`）。
>
> ### 第一轮实机（18:11）暴露的两个问题
>
> 1. **「准星里没有实体」= 什么都不做**。日志 `sprint-slap 空转：准星里没有可攻击实体`
>    连着刷。没有 C02 就不能乘 0.6，所以站着挨打时这个模式完全无感。
>    现在加了 `velocity.slapAim`（默认开）：准星没目标时**自己把视角转过去**
>    （只转前半身 ≤75°，`continuousYaw` 累加不取模；tick END 恢复相机），
>    并且 `crosshairTarget` 在 MOP 过期时会按当前 rotation 再扫一遍候选。
> 2. **挨打那一 tick 就出手反而吃 flag**。18:11:56 已经打出去了（`sprint-slap 攻击 #1~#3`），
>    Grim 仍然给了 `AntiKB o: .143991` + `Simulation .143991/.104390/.094995`（一次误差
>    按摩擦衰减）。原因是 Grim 的击退用 transaction 对齐 —— 我们在 tick START 发 C02
>    时它的 `player.velocity` 还是挨打前的值，`addAttackSlowToPossibilities` 生成的是
>    `0.6·v_old`，客户端乘的是 `0.6·v_kb`，两边对不上。
>    现在 `slapStartTick = 挨打 tick + 1`：**挨打那一 tick 是满受力，从下一 tick 开始削**。
>    （代价：6 次变 5 次，0.6^5 ≈ 8%。）
>
> 另外 `MODE_BLINK` 也加进了守护映射：BLINK 的 `blinkKeep=100%` 本来就**一点击退都不削**
> （它只是把位置包推迟），在 Grim 上还白送 Timer/Simulation —— 用户 18:12 那局就是切到了
> BLINK，日志 `blink #1 开始：憋 5 tick（受力 100%）`，体感就是「Velocity 没用了」。
>
> ### 同时修掉的其它坑
>
> - `posZ` 的 SRG 是 `field_70161_v`，不是 `field_70166_s`（后者是 lastTickPosZ）。
>   `recordBlinkOrigin` / `burstDistance` / `dropAndRewind` 之前都读错了字段，
>   reach 筛选和回退坐标因此偏一个 tick 的位移。
> - `shouldHoldOutbound` 不再扣纯视角包 `C05PacketPlayerLook`，骑马时不扣。
> - `OpenMaverFeatures.claimPlacement()`：Scaffold 与 farm 共用「一 tick 一个 C08」
>   的名额（两边各维护自己的 `placedThisTick` 挡不住对方，tick 顺序是 Scaffold
>   先跑、Velocity 后跑，正好一个 tick 两条 C08）。
> - 配置迁移 `settings.version 10`：`mode ∈ {1,4,5}` 且 `server ∈ {null,3}` 的旧
>   配置自动搬到 `mode=7 / server=3 / slapMaxTicks=6`。
>
> ### 验证
>
> - `tools/velocity-test/run.sh`：88 项（新增 SPRINT SLAP 的放行/开窗/不写 motion/
>   窗口长度断言）。
> - 实机判据：只开 Velocity 打一场，`AntiKB` 应为 0；`FabricatedPlace / Timer /
>   GroundSpoof / BadPacketsN / Hitboxes` 全为 0；HUD 显示 `SPRINT SLAP 6t Np`。
>
> ---
>
> 下面是历史记录（LiquidBounce 式模式表与踩坑过程），保留原文。

> 参考 LiquidBounce 的 `Velocity` 策略划分（Modify / Reversal / Strafe / JumpReset
> + 服务器定向模式），并加入本工程的对测思路：**blink 5 tick，然后一口气发 5 个
> 攻击包**。
> OpenMaver 没有 Mixin / ASM，所以用 **Netty 管线注入 + 反射** 实现；管线注入失败
> 时退化为运动差分嗅探。

## 文件

- `java/module/movement/Velocity.java`：模块开关（`movement.velocity`），只存开关。
- `mc/VelocityFeature.java`：全部行为（模式状态机、运动改写、blink、攻击包爆发）。
- `mc/VelocityPacketHook.java`：Netty `ChannelDuplexHandler`，入站吞 S12、出站扣 C03。
- `mc/OpenMaverClientHandler.java`：`ClientTickEvent.START` 调
  `VelocityFeature.onTickStart()`，`END` 调 `onTickEnd()`。
- `mc/OpenMaverFeatures.refreshSettings()/saveSettings()`：`velocity.*` 的读写。
- `mc/GuiOpenMaverClickGui.addVelocitySettings()`：ClickGUI 设置面板。
- `mcstubs/io/netty/channel/*`：**仅编译期** stub（仓库内没有 Netty 依赖）。

## 管线位置（最容易写错的地方）

1.8.9 的 `NetworkManager` 用 `ChannelInitializer` 按顺序 `addLast`：

```
timeout -> splitter -> decoder -> prepender -> encoder -> packet_handler
```

- **入站**方向 head -> tail：`decoder` 把 ByteBuf 还原成 `Packet`，再交给
  `packet_handler`（即 `NetworkManager` 自己）。
- **出站**方向 tail -> head：`packet_handler` 先拿到 `Packet`，然后才轮到
  `encoder` 做序列化。也就是说出站是 **`packet_handler` -> `encoder`**。

因此：

| 插入点 | 入站看到 | 出站看到 |
| --- | --- | --- |
| `addBefore("encoder", ...)` | Packet | **ByteBuf**（序列化之后）❌ |
| `addBefore("packet_handler", ...)` | Packet | **Packet** ✅ |

本模块用后者。`VelocityFeature.pickBaseName()` 只认 `packet_handler`；找不到时
`addLast`（出站仍能拿到 Packet，入站退化为事后修正）。

> 这套顺序与名字是用真实 1.8.9 client jar 里 `NetworkManager$ChannelInitializer`
> 的常量池校验过的，不是凭记忆写的。

## 数据流

```
Netty I/O 线程                         主线程（ClientTickEvent）
──────────────────                     ───────────────────────────
S12(自己) ──► 解析 4 个 int 字段         onTickStart:
             记 pendingKb(XYZ)            sniffKnockback()（无管线时）
             吞掉（不交给原版）     ──►   applyPendingKnockback()  ──► 写 motion
                                          tickJumpReset() / tickReversal()
                                          tickBlink()（窗口到点 -> finishBlink）

C03 移动包 ──► blink 窗口内扣住        onTickEnd: 记录 motion 快照（嗅探用）
             promise 立即 setSuccess
```

- **S12 字段顺序**：`entityId, motionX, motionY, motionZ` 四个 int，按类文件声明
  顺序读取（已用真实 jar 的 `readPacketData` putfield 顺序校验）。
  值域：motion 是 `short`，即 `(int)(v * 8000)`，最大 ±4.09 格/tick。
- **只处理自己**：`entityId == 本地实体 id`，别人的击退包原样放行。
- **线程约束**：I/O 线程绝不碰 `World` / `Entity`，只写 volatile 字段；所有运动
  修改都在主线程做。
- **憋包的 promise**：被扣住的消息立刻 `promise.setSuccess()`，否则
  `NetworkManager.dispatchPacket` 会一直等在未完成的 future 上。

## 模式

| 模式 | 处理 |
| --- | --- |
| `MODIFY` | `motion = kb * (horizontal, vertical)`，0% 完全无视、100% 原版 |
| `REVERSAL` | 先归零，延迟 `reversalDelay` 后 `motionXZ = -kbXZ * strength`（推向对手） |
| `STRAFE` | 按 `Entity.moveFlying` 的方向分解把 `|kb|` 投到输入方向；无输入时沿击退法线侧移 |
| `JUMP RESET` | 击退事件（可延迟 0~3 tick）触发 `jump()`，水平按比例削掉 |
| `BLINK BURST` | 憋 5 tick 位置包 → 一口气发 5 条 C0A+C02 → 再补发位置包（满受力，过 Grim 但不减免） |
| `UNCERTAINTY` | 先 ghost-block（放方块 + 立刻开始挖同一格）撑开 Grim 的容差，再归零，憋 2 tick 后爆发 |
| `COUNTER` | 一个字节的速度都不改：受击当 tick 起跳 + 立刻反打（零 offset，零风险） |

### 为什么「位移层硬改」在 Grim 上走不通

`REVERSAL` / `BLINK` 都是在**位移**上做文章。Grim 的 `Simulation` 是逐 tick 全量预测，
服务端知道自己发过 S12，那一段位移它一定在等：

```
AntiKB (vl:0.0): o: .507910        # 缺掉的那一次击退水平量
Simulation (vl:0.0→78): .194 / .151 / .143 / .375 …   # 每 tick 一条
```

`BLINK BURST` 的 5 tick 憋包能把这些 flag 挤到窗口外，但代价是**满受力**
（`BLINK MOTION=100%`）—— 位移照吃。想既不挨打又不被 flag，就不能在位移上做文章。

### UNCERTAINTY：把「算不准」这件事本身当成资源

Grim 的判定不是「位移对不对」，而是「位移对不对 **在它算出来的不确定度之内**」。
`UncertaintyHandler` 会为这些**合法动作**主动加容差（碰撞箱变了，它没法确定你脚下是什么）：

| 动作 | 包 | 备注 |
| --- | --- | --- |
| 放置方块 | `C08PacketPlayerBlockPlacement` | 点侧面地面的上表面，新方块落在玩家旁边一格（不卡自己） |
| 开始挖掘 | `C07PacketPlayerDigging(START_DESTROY_BLOCK)` | 只发 START 不发 STOP：服务端只记录进度，方块不会真的消失 |
| 手持切换 | `C09PacketHeldItemChange` | 没有方块时的退化（Grim 的 slot-switch 也有 uncertainty） |

所以受击那一刻的顺序是：**先 farm 容差，再归零**（反过来第一 tick 就白欠了），
然后只憋 `UNCERTAINTY TICKS`（默认 2）tick 位置包 —— 断档短到 `Post` 类检查看不出来 ——
窗口结束时 `C05` 对齐视角 + 从 P0 爆发 N 条 C02。

**自适应**：farm 失败（空手 / 那一格放不了 / 类名对不上）时**不会**硬归零，而是直接
退化成 `COUNTER`（不碰速度 + 起跳 + 反打）。硬归零 + 没容差 = 又回到每 tick 一条 flag。

### COUNTER：零风险兜底

不碰 `motion`、不憋包、不伪造任何包 —— Grim 的预测和实际完全一致，结构上产生不了
offset。做的事只有两件：受击当 tick `jump()`（水平滑行被跳跃吃掉一大截）+ 立刻反打。
位移削减不如归零，但永远不会被 flag。

`BLINK BURST` 的时序（`finishBlink`）：

1. `syncRotation`：先发一条**只改视角**的 `C05PacketPlayerLook`（yaw/pitch/onGround，
   没有坐标）。服务端结算 `C02` 时用的是它自己那份 yaw/pitch 做射线，憋了 5 tick
   视角早就变了，不先对齐的话 5 条 `C02` 全打在旧方向上（看着像"打了没伤害"）。
   纯视角包不带坐标，Grim 不跑位置预测，所以这条不会带出 offset；
2. **再**打攻击包：服务端仍在用憋包前的旧位置（`blinkOrigin`）结算这一轮攻击/击退；
3. **最后**补发缓存的位置包，把这几 tick 的位移一次交出去；
4. `FLUSH POSITION` 关掉 + `BLINK REWIND` 打开时改为「丢弃位置包 + 本地坐标回退到
   `blinkOrigin`」（表现成瞬移，服务端眼里我们没动过；代价见下）。

### 三个和 Grim 直接相关的设计点

| 点 | 做法 | 原因 |
| --- | --- | --- |
| **窗口内必须受力** | `BLINK MOTION`（`velocity.blinkKeep`）默认 100%，击退照比例写进 motion | Grim 的 Simulation 是逐 tick 全量预测。服务端**知道**它发了 S12，那一段位移它一定在等；憋包只让位置**迟到**，不会让它消失。补发时 delta 对得上预测，vl 才不涨 |
| **垂直不能削** | `writeVerticalKb()`：`onGround == true` 时**不写**垂直分量 | 站在地上把 `motionY` 抬成 0.2~0.36 会离地 2~3 tick，服务端看到"悬空却说没在地上"→ `GroundSpoof: claimed false` |
| **reach 按 P0 算** | `recordBlinkOrigin()` 在扣第一个位置包**之前**记坐标，`burstDistance()` 用它筛目标 | 这 5 tick 里服务端一直认为我们站在 P0，reach 是拿 P0 到目标的距离判的。用客户端当前位置筛，被推开 2 格后就"客户端觉得 3 格、服务端算出 5 格"，5 条 C02 全被 reach 丢掉 |

### Grim 守护（`GRIM GUARD`，默认开）

真正踩到的坑是**配置没切**：`SERVER=INTAVE` 给的预设就是 `REVERSAL`（水平归零 +
延迟反向），代码里改好的 `BLINK BURST` 一次都没执行，日志里依旧
`#20 mode=REVERSAL kb=(0.119, 0.072, 0.111)` + `AntiKB o: .507` + `GroundSpoof`。

所以守护做在**模式解析层**（`effectiveMode()`），而不是靠人记得去点：

| 配置 | 守护介入后 |
| --- | --- |
| `REVERSAL` | → `BLINK BURST`（归零那段全是 offset） |
| `MODIFY` 且水平或垂直 < 30% | → `BLINK BURST`（等价于归零） |
| `MODIFY` 45/85% 之类 | 原样保留 |
| 水平比例 / 垂直比例 | 下限抬到 45% / 35%（垂直归零 = 滞空 = `GroundSpoof`） |
| `SERVER` 明确选了 VANILLA / HYPIXEL / INTAVE | 守护不介入，尊重该服务端判断 |
| `GRIM GUARD` 关掉 | 完全按配置执行 |

被守护改写时 HUD 摘要会带 `GUARD`（`BLINK BURST 5t x5 K100% GUARD`），
`logs/openmaver.log` 在加载配置时也会打一行：

```
[Velocity] 配置: mode=REVERSAL server=GRIM guard=on -> 实际=BLINK BURST（H=100% V=100%） [守护改写了归零类模式]
```

**配置迁移（settings.version 8）**：老配置里 `velocity.mode=1`（REVERSAL）或
`velocity.server=4`（INTAVE）时，自动搬到 `server=3 / mode=4`、水平垂直 1.0、
blink 5t、burst 5、`blinkKeep=1.0`，并写入 `velocity.grimGuard=true`。

### 为什么 `REVERSAL` 在 Grim 上必被抓

`REVERSAL` 先把水平速度**归零**、延迟后再反向打出去。对 Grim 的逐 tick 预测来说，
归零那一刻起每一 tick 都欠它一段位移：日志里
`AntiKB (vl:0.0): o: .537119`（缺的正是那次击退的水平量）+
`Simulation (vl:0→78): .218 / .158 / .146 / .338 …`（每 tick 一条）就是这么堆出来的。
**归零 = 每 tick 一条 flag**，vl 涨到几十必然触发 setback/踢出。

`BLINK BURST` 的形状完全不同：窗口内没有位置包 → Grim 拿不到可判定的输入（不记账）；
窗口结束时先打攻击包、再补发**真实**轨迹 → 逐包预测吻合。收益是「服务端在旧位置
多结算了 N 条攻击」，而不是「假装没被打」。真要做到"完全不吃击退"，只能走
`FLUSH POSITION = false` + `BLINK REWIND = true`（弃包回退）：服务端眼里我们没动过，
代价是之后那一个 tick 会缺一段击退位移，AntiKB/Simulation 记**一次**账，
而不是原来每 tick 记一次。所以它适合低频用（被打 → 弃包 → 立刻反打）。

攻击目标选择：`loadedEntityList` 里按距离排序的可碰撞生物（最多 8 个），
`fireBurst` 用 `i % size` 轮流打——同一目标连吃 C02 会被服务端无敌帧丢掉，
轮转能多打几个目标；只有一个目标时就把 N 条全给它。这里刻意不读
`hurtResistantTime`（SRG 名相近、猜错只会静默失效）。

## 服务器预设

| 预设 | 模式与参数 | 理由 |
| --- | --- | --- |
| `VANILLA` | `MODIFY 0/0%` | 原版击退完全由 S12 决定，抹平即可 |
| `HYPIXEL` | `MODIFY 12/0%`，起跳延迟 1t | 自研击退结算，全抹水平太显眼 |
| `GRIM` | `UNCERTAINTY 2t x5`，`UNCERTAIN FARM` 开，水平 0%/垂直 100% | 位移层硬改必被抓，改为先把 Grim 的容差撑开再归零；垂直留满避免 GroundSpoof |
| `GRIM` + 想要绝对安全 | `COUNTER x5` | 一个字节的速度都不改，零 offset |
| `INTAVE` | `REVERSAL 100ms`，垂直 35% | 击退判定有时间窗，延迟反向更贴合 |

## 与 bypass profile 的联动

`bypass.enabled=true` 且 `bypass.profile=legit` 时：

- `BLINK BURST` → 降级为 `JUMP RESET`（位置包断档在 Post 检查里太显眼）；
- 水平/垂直保留比例下限抬到 45% / 65%（`hRatio()` / `vRatio()`）；
- ClickGUI 的模块摘要会显示 `OFF (LEGIT)`。

## 配置项（`config/openmaver.properties`）

```
velocity.mode=0              # 0 modify, 1 reversal, 2 strafe, 3 jumpreset, 4 blink
velocity.server=0            # 0 custom, 1 vanilla, 2 hypixel, 3 grim, 4 intave
velocity.horizontal=0.0      # 0..1
velocity.vertical=0.0        # 0..1
velocity.reversalDelay=120   # ms, 0..600
velocity.reversalStrength=1.0
velocity.strafeFactor=1.0
velocity.jumpDelay=0         # tick, 0..3
velocity.blinkTicks=5        # 1..20
velocity.burstAttacks=5      # 1..8
velocity.burstRange=3.0      # 2..6 格（服务端 1.8 交互上限 3）
velocity.blinkFlush=true
velocity.blinkKeep=1.0        # blink 窗口内保留的击退比例（Grim 用 1.0）
velocity.burstRotate=true     # 爆发前先发 C05 对齐视角（关掉基本打不中）
velocity.blinkRewind=false    # 弃包时把本地坐标回退到 blinkOrigin（真·免击退，低频用）
velocity.grimGuard=true       # 归零类模式自动升级成 UNCERTAINTY，比例下限 45%/35%
velocity.uncertaintyTicks=2   # UNCERTAINTY 憋包 tick（1~3）
velocity.uncertaintyFarm=true # 是否发 ghost-block 撑容差；失败自动退化 COUNTER
```

## 选型

| 目标 | 用哪个 |
| --- | --- |
| 位移削减 + 尽量不被 flag | `UNCERTAINTY`（GRIM 预设默认） |
| 绝对不被 flag（放弃大幅减伤） | `COUNTER` |
| 只要「不挨打、随便被抓」的对测 | `MODIFY 0/0`（守护关掉） |
| 纯原版服 | `MODIFY 0/0` 或 `VANILLA` 预设 |

## 诊断

`logs/openmaver.log`：

```
[Velocity] 配置: mode=UNCERTAINTY server=GRIM guard=on -> 实际=UNCERTAINTY（H=0% V=100%）
[Velocity] uncertainty farm 已生效：ghost-block 目标=(163, 64, -12)，本窗口 2 tick 内归零击退
[Velocity] blink #3 开始：憋 2 tick（受力 0%），然后爆发 5 条攻击包；服务端坐标=(163.61, 64.0, -12.4)
[Velocity] blink 结束：补发 2 条位置包，累计爆发攻击 15 条（原样补发）
```

- 只看到 `配置: … UNCERTAINTY` 但**没有** `uncertainty farm 已生效` → 手里没方块 /
  那一格放不了 → 实际跑的是 COUNTER（HUD 摘要会显示 `COUNTER`，不是 `UNCERTAINTY`）。
- `burst 无目标：憋包起点 (x,y,z) 的 3.0 格内没有可攻击生物` → reach 是按服务端认定的
  坐标算的，被推开后就是会空。`posY` 的 SRG 是 `field_70163_u`（不是 `field_70164_h`，
  那是 `prevPosY`）—— 写错会让竖直距离恒为 0。

## 已经踩过的坑：换连接后模块整体失效

第一版只在第一次成功注入后置 `hookInstalled = true` 就再也不检查了。实际流程是：

```
单人世界开启模块  ->  hook 挂到「集成服务器」的管线上，hookInstalled = true
加入服务器/切服   ->  客户端新建 NetworkManager + Channel + Pipeline
                      installHook() 看到 hookInstalled 直接 return
                      sniffKnockback() 又因为 hookInstalled 提前 return
                      => 两条路径同时失效，模块完全静默
```

所以现在 `maintainHook()` 用 **`NetHandlerPlayClient` 实例作为连接标识**：

- `netHandler != hookNetHandler` → 认为换连接，先 `detachHook()` 再重挂；
- 每 20 tick（1 秒）校验一次 `pipeline.names()` 里还有没有我们的 handler，
  被别的插件摘掉时自动补回；
- 摘除时先把憋住的位置包尽力补发，避免服务端停在上一个坐标。

对应测试：`tools/velocity-test/VelocityInstallTest.java` 的
`hook re-attached on the new connection` 与 `hook self-heals after external removal`。
验证时务必走「单人→服务器」或「服务器 A→服务器 B」的流程，只测第一次连接测不出来。

## 两个坑必须记住

1. **插入点**：`encoder` 之后（`addBefore("packet_handler", ...)`）。插在 `encoder`
   之前出站只能拿到 ByteBuf，blink 静默失效。
2. **不要依赖 SRG 名相近的受击字段**：1.8.9 的 `hurtTime` / `hurtResistantTime`
   只差一个字母，猜错会永远读 0（JumpReset 就永远不触发）。这里把 JumpReset 的
   触发点改成击退事件本身；本地实体 id 也优先走 reobf 表里已验证的
   `Entity#getEntityId`（`func_145782_y`），字段名只作兜底。

## 已知近似

- `S12PacketEntityVelocity` 同时承担爆炸 / 指令给的冲量，因此这些也会被当成击退
  处理（在“防击退”的语义下是期望行为）。
- 无管线时（嗅探模式）靠运动特征判断，理论上存在误判为击退的可能；管线模式不存在
  这个问题。
- blink 期间服务端看到的是憋包前的位置，`FLUSH POSITION` 打开时会在窗口结束时把
  这几 tick 的位移一次性交出去；关闭时表现为瞬移。
- 同一目标连吃 5 条 `C02` 时，1.8 服务端的 `hurtResistantTime`（10 tick 无敌帧）
  只结算第一条伤害，其余仍会被处理（消耗击退、给 AntiKB 观察喂数据）。所以
  `fireBurst` 按 `i % size` 在候选之间轮转：有多个目标才能真的打出 5 段伤害，
  只有一个目标时是"5 条包全给它"，效果是打乱结算时序而不是 5 倍伤害。

## 验证

- 编译：`cmake --build build -j`（含 `mcstubs/io/netty` 的 stub 编译）。
- 包层功能测试：`tools/velocity-test/run.sh`
  （真实 netty 4.0.23.Final，即 1.8.9 自带版本；`tools/velocity-test/` 里带有
  `C03PacketPlayer` / `S12PacketEntityVelocity` 的测试替身）。
  共 34 项断言：插入点选择、出站 Packet 可见性、S12 吞包与向量解析、他人包放行、
  `MODIFY 0/50/100%`、`REVERSAL` 反向、`STRAFE` 侧移、`JUMP RESET` 起跳、
  blink 扣包/补发顺序、100/100 不拦包、嗅探的三种判定（击退 / 冲刺跳 / 下落）、
  模块关闭后放行。
- 游戏内自查（不用猜）：
  - HUD 的 Velocity 一行显示 `模式 + 参数 + PACKET/SNIFF`，憋包时显示缓存包数（如 `3p`）；
  - ClickGUI 里同一行的末尾是 `KB n`：**n 是真正被处理的击退次数**。
    `PACKET` 且 `KB 0` 说明钩子挂着但服务端没发 S12（或 id 没对上）；
    `PACKET` 且 `KB > 0` 说明拦截已经在发生；显示的若是 `SNIFF` 则是兜底路径。
  - `logs/openmaver.log` 里会有：
    `[Velocity] 包管线已挂上: addBefore(packet_handler, openmaver_velocity)；当前管线=[...]`
    `[Velocity] 包管线已摘除: 连接变化`
    `[Velocity] 击退 #1 mode=MODIFY kb=(0.400, 0.361, -0.400)`
    `[Velocity] blink #1 开始：憋 5 tick（受力 100%），然后爆发 5 条攻击包；服务端坐标=(x, y, z)`
    `[Velocity] blink 结束：补发 5 条位置包，累计爆发攻击 5 条（原样补发）`
