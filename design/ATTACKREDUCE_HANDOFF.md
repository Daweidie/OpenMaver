# Velocity：输入式 Sprint / Jump Reset 与 OpenZen AntiKB 适配

## 当前状态（2026-11-21，路线 A）

NoXZ 已放弃地面与空中的击退挂起。收到 S12 的主线程任务立即应用原包，不取消入站、不暂停出站；仅保留受击跳跃与最多等待 12 tick 的落地补刀意图。落地使用当前运动速度，绝不重新施加收到时的 S12。新击退（包括非正 Y）、位置校正、忽略环境、禁用、切模式及换世界都会清理旧攻击意图。`Instant Attack` 只增加最多 3 次正常 tick 补刀，不改变 Timer。跳过无目标的落地机会也不再强制关闭疾跑。

Sprint Reset 为新配置默认，Jump Reset 已移除 10–20 tick 入站挂起；两个模式只接管输入，Mix 仍保留选择性入站队列。下方实测章节保留旧挂起实现的数据，供解释此次取舍；目前未取得新版本的服务器端到端测试回执。NoXZ 的正常调试行应为 `KB in PASS AIR / LANDING WAIT`、`PASS READY`、`PASS NOT SPRINTING`、`PASS NO TARGET`，不应再产生 `SUSPEND` / `OUT HOLD` / `FLUSH` / `KB release`。

## 来源与范围

来源是 `../OpenZen` 当前提交 `9577d2f` 中的以下文件（这些来源文件工作树干净）：

- `src/main/java/shit/zen/modules/impl/combat/AntiKB.java`
- `src/main/java/shit/zen/modules/impl/combat/antikb/{AntiKBMode,NoXZMode,JumpResetMode,MixMode}.java`
- `src/main/java/shit/zen/modules/impl/combat/KillAura.java` 的目标与攻击联动；攻击顺序及物理由本地 1.8.9 原版接口决定

按用户要求，移除了 Maver 的 SPRINT HIT / SIMPLE / SLAP ATTACK 状态机、百分比缩放、动作闸门、冲刺同步窗口及对应测试。保留模块类 `module.movement.AttackReduce`、显示名 `Velocity` 和配置键 `movement.attackreduce`，避免破坏开关与热键。

`mc/AttackReduceFeature.java` 是 Minecraft 1.8.9 适配层；`mc/antikb/` 中保留 NoXZ / Mix 状态机，并用 `JumpResetMode(boolean sprintReset)` 实现两个输入模式。项目使用默认 package，目录仅组织源码。Java 8 字节码由现有 CMake 构建流程生成，再经过 MCP → SRG 重映射。

## 四模式

| 模式 | 触发与队列 | 释放与动作 |
| --- | --- | --- |
| Sprint Reset（新配置默认） | 自己的地面正 Y、非零水平 S12；所有包立即直通 | 下一次有效输入向前疾跑 2 tick，单次跳跃；按 Chance/Accuracy 决定是否触发及 0–2 tick 输入延迟 |
| NoXZ | 所有包立即直通；自己的正 Y S12 触发地面攻击或空中落地补刀意图 | 落地用当前速度攻击，最多等 12 tick 后放弃意图；每 tick 最多一次实体攻击；位置校正后冷却 2 tick |
| Jump Reset | 同 Sprint Reset；所有包立即直通 | 仅单次跳跃，不接管前进或疾跑 |
| Mix | 正 Y S12 及实体移动、事务、实体传送包 | 落地或连续空中 24 tick 释放；受伤跳跃、前 3 tick 方向接管、可选 Aura 双击 |

NoXZ 的目标顺序为 Aura 已选目标 → 准星存活生物，眼睛到可见目标 AABB 距离 ≤ 3.0。攻击前同步客户端当前疾跑状态与 serverSprintState，然后先挥手、再调用原版 controller。仅由原版攻击结果决定减速，适配层不手动写 XZ，也不提前退出疾跑。

Mix 的可选双击保留两个攻击意图，分配到两个空闲 tick，复用 Aura 目标。执行时查询真实疾跑状态并走同一个 1.8 攻击入口；与 AutoClicker/Aura 共享每 tick 名额，避免额外攻击或无伤害结果时仍按旧疾跑快照减速。

## 依赖映射和接线

| OpenZen 1.20.1 | OpenMaver 1.8.9 |
| --- | --- |
| ClientboundSetEntityMotionPacket | S12PacketEntityVelocity，仍为 1/8000 单位 |
| ServerboundMovePlayerPacket | C03PacketPlayer 及 C04/C05/C06 子类 |
| ClientboundMoveEntityPacket | S14PacketEntity 及其子类 |
| ClientboundPingPacket | S32PacketConfirmTransaction（旧协议的同步事务；非同构消息） |
| ClientboundTeleportEntityPacket | S18PacketEntityTeleport |
| ClientboundPlayerPositionPacket | S08PacketPlayerPosLook |
| Chat / Time / HurtAnimation / Animate | S02 / S03 / S19 / S0B；NoXZ 全部立即放行 |
| ReceivePacketEvent | Netty 入站 → Minecraft 主线程 → 模式 → processPacket/func_148833_a |
| Tick / Sprint | START：beforeTick 先回放到期队列；Aura/AutoSprint 更新后 onTickStart 执行攻击 |
| Strafe / GameTick 输入 | ScaffoldMovementInput，原版输入更新后、移动计算前 |
| Motion PRE | 输入回调末尾；tick END 另作回放兜底 |
| RotationHandler | 输入回调写入连续 yaw/pitch 和 MoveFix；tick END 恢复相机，保留鼠标增量 |
| ZenClient.serverTickRate | 不再接管 Timer；保留原倍率，恢复接口用于兼容清理 |
| StuckInBlock 的蛛网观测 | 1.8 Entity.isInWeb / field_70134_J |

NoXZ 原源码把 ServerboundMovePlayerPacket 分支写在 ReceivePacketEvent 中；早期适配曾接到 Netty `write` 路径并缓存完整出站流。当前 NoXZ 已不调用 hold/flush；适配层仍保留 FIFO 与 ChannelPromise 清理接口，独立测试显式启用队列以覆盖断线、取消和上限行为。hold/flush 均在同一 eventLoop 上执行。

两个 Reset 模式的 NoFall/Backtrack 联动检查对应活动状态（NoFall boost 字段，Backtrack isActive/isBacktracking），缺失模块时自然为 false。FireballBlink/HighJump/Stuck 等依赖通过模块名查询，不引入现代 Minecraft 类。

## 生命周期适配

- 模式判断、世界和目标访问仅发生在 Minecraft 主线程。Netty 负责转交入站包、保存/释放出站包。
- Packet 保存所属 player/world/handler。旧世界的挂起队列丢弃，同连接模式切换则回放一次。S07 重生显式识别同连接世界切换，保留紧随重生、已经排入主线程的新世界包。
- 禁用、切模式、位置校正、重生会释放当前连接队列并恢复计时/相机。断线终止未完成的出站 promises。
- 入站/出站队列各设 4096 包上限，达到时强制释放。安装失败每 20 tick 重试；外部移除 hook 时清队列并重装。
- 死亡、观察者、飞行、水/熔岩、燃烧、梯子、睡眠和蛛网时释放并跳过模式处理。Reset 模式的 Water Check=false 可单独放开水中检查，其余环境保护保持。这一公共保护把 Zen NoXZ 的环境检查用于所有模式，替换 Mix 的蛛网事件依赖。
- 转向让位于 Scaffold/GodBridge/Aura；输入直接改 MovementInput，不持有 KeyBinding。潜行 MoveFix 保留原版 0.3 输入幅度。
- Mix 的 Motion/GUI 禁用/切模式等较晚路径将入站回放转交到下一次准备阶段；新的入站任务也先排空旧回放。此处使用显式队列，因为 Minecraft.addScheduledTask 在主线程会立即执行。旧连接/世界校验同时覆盖该队列。NoXZ 超时只丢弃攻击意图，不涉及包回放。
- 新的本地 S12 到达时，先释放旧挂起队列，再重置该次模式状态并处理新 S12，避免旧击退最后覆盖新击退。

这些是协议、线程和事件系统适配；游戏物理与服务器行为仍需端到端验证。

## 2026-11-21 日志修复（早期实现记录）

日志中的 `PacketOrderB: pre-attack` 与旧实现的 C02 → C0A 顺序相符；修复统一了 AutoClicker 三档、Aura、Velocity 和延迟 Criticals 的攻击入口。实体攻击前同步必要的 C0B，再发送 C0A → C02；共享每 tick 的攻击名额并设置原版左键冷却，避免多个模块及原版输入重复攻击。Velocity 不再按过期疾跑快照手动减速。

`Post: animation v1.8` 的候选原因是晚阶段队列回放使 S32 的 C0F 回执插入动作和移动之间。修复将到期回放放在动作前，晚阶段回放显式延迟至下一次准备/入站任务；NoXZ 同时保留完整出站 FIFO。新增回归验证这些顺序及连续 S12 的新旧速度边界。此归因是 CANDIDATE，客户端日志没有完整抓包，尚未与真实服务端逐包对照。

`LegacySoundFeature` 独立于 Velocity 安装声音 hook，将 1.8 资源中缺失的 `damage.thorns` / `minecraft:damage.thorns` 映射到 `game.player.hurt`，保留位置、音量、音高以及其他声音。

当时的 `Simulation` / `AntiKB` 不只受包序影响：三个模式仍保留击退延迟，`Instant Attack` 仍按配置改变 Timer。之后已移除 Timer 接管，并在当前修复中移除 NoXZ 延迟。客户端修复不构成真实 Grim 零告警的验证结果，跨版本伤害冷却也由服务端协议转换决定。

## 2026-11-21 聊天框调试输出（velocity.zen.debug）

ClickGUI 的 Velocity 展开项新增 `DEBUG CHAT`（配置键 `velocity.zen.debug`，默认 false）。打开后由 `mc/VelocityDebug.java` 把三类事件写进聊天框（同时写 `logs/openmaver.log` 的 DEBUG 行），每行前缀 `[KB]`：

| 行 | 含义 |
| --- | --- |
| `OUT HOLD <包> (x, y, z) ground=? q=N` | 出站包被 `Session.moves` 挂起，尚未发出；`q` 为队列长度 |
| `OUT SEND <包> (x, y, z) ground=?` | 出站包正常通过（未挂起） |
| `FLUSH n 包一次性写出` | 挂起结束，积压的 C03/C0F 等在同一事件循环任务里一起 flush |
| `KB in PASS AIR / LANDING WAIT / PASS READY / PASS NOT SPRINTING / PASS NO TARGET … timer=…` | NoXZ 收到自己的 S12 并立即放行；轨迹仅为无输入简化估算，LANDING WAIT 只等待攻击机会 |
| `SUSPEND t=N 实际=(…) 预计=(…) 偏移=D格` | 挂起期间实际位置与服务端（已吃下击退）预计位置的偏差 |
| `KB release 挂起=Nt 队列=M包 实际=(…) 服务端预计=(…) 偏移=(dx, dy, dz)=D格` | 释放瞬间的最终偏差；这一项就是 Grim 的 Simulation / AntiKB 会量到的量 |

聊天框过滤（2026-11-21 实测后调整）：移动包完整写日志，聊天框输出 `KB in` 与间隔异常（<30ms 或 >90ms，每秒最多 2 行，前缀 `TIMER?`）；每秒另给一行 `1s 移动包 N，间隔 a~b ms`。保留的挂起/回放调试接口仅供兼容，当前 NoXZ 不会触发，已从根源消除逐包 `OUT HOLD` 噪音。C03 基类与 C05 不含坐标，只有 `moving=true` 的 C04/C06 才打印 `(x, y, z)`。`KB` 行附带当前 `timer=`。

限流：聊天框每秒最多 10 行，`KB in` / `KB release` 不受限流；超出部分记入日志。轨迹推演使用 0.08 重力、0.98 垂直与 0.91 水平阻尼、无输入，仅作调试参考，不是 Vanilla 的逐 tick 全量物理（跳跃、潜行、速度药水、蛛网等分支未包含）。

### Instant Attack 不再改 Timer

实测日志（2026-11-21 20:05）显示：旧 NoXZ 用 `timer(0.5F)`/`timer(4.0F)` 压缩补刀，4 倍速那一拍会连续写出 4~6 个 C04（间隔 4~6ms），服务端立刻给出 `Timer (vl:0.0)` 与 `Simulation (.216995)`。现在 Instant Attack 仅按等待落地时长累积补刀（每 2 tick +1，最多 +3），分摊到正常 tick，`timerSpeed` 全程保持玩家原值，NoXZ 不调用 `AttackReduceFeature.timer(...)`。

### 2026-11-21 20:20 空中挂起实测

同一服务器（Grim 2.x）空中命中：`SUSPEND` 分别挂起 3/5/8 tick，`KB release` 的服务端预计位置与实际位置偏移 **1.82 / 2.01 / 2.99 格**，随后 Grim 连续给出 `Simulation (vl:0-12, .0038~.136)`、`AntiKB (vl:0.0): o:.162922`、`Hitboxes type=player`（挂起期坐标错位导致的攻击距离判定）。同一秒内 `FLUSH 12 包` 产生 3~5ms 的 C04 连发。

结论：超过约 1 tick 的击退延迟必然被 Simulation/AntiKB 量到，且回放簇会逼近 Timer 与 Hitboxes 阈值；NoXZ 的浮空挂起只有"缩短到 0~1 tick"或"放弃"两条路。地面路径已在同轮改为直通并保持 0 flag。

## 参数与配置

配置版本为 20。旧 `velocity.*` / `attackreduce.*` 参数在升级时清除；模块开关/热键 `movement.attackreduce` 不变。新模式使用字符串，避免历史数值编号误映射。

| velocity.zen. 后缀 | 默认值 | 范围 / 生效模式 |
| --- | --- | --- |
| mode | Sprint Reset | Sprint Reset / Jump Reset / NoXZ / Mix；保留显式保存模式 |
| chance | 100 | 0–100%，两个 Reset 模式，每次有效受击的触发概率 |
| accuracy | 100 | 0–100%，两个 Reset 模式，准时响应概率；失准只延后输入 1–2 tick |
| onlyWhenTargeting | false | 两个 Reset 模式；执行时仍须有存活、可见、3.0 格内的 Aura/准星目标 |
| waterCheck | true | 两个 Reset 模式；关闭只放开水中检查 |
| attackAmount | 5 | 1–20，NoXZ |
| instantAttack | false | NoXZ：等待落地每 2 tick 累积 +1 次，最多 +3；正常 tick 执行，不改 Timer |
| sprintStateCheck | true | NoXZ |
| rotate | false | Mix；旧 followDirection / rotateTicks 不再加载或保存 |
| tryAttack | false | Mix |
| movementOverride | false | Mix |
| debug | false | 所有模式的出站观测与 NoXZ / Reset 的入站事件 |

## 验证和产物

构建与验证命令：

```sh
cmake --build build -j2
tools/attackreduce-test/run.sh
tools/chest-test/run.sh
tools/godbridge-test/run.sh
tools/scaffold-test/run.sh
git diff --check
```

行为测试使用真实 Netty 4.0.23.Final EmbeddedChannel 与 Minecraft 主线程队列替身。此前 NoXZ 修复覆盖 NoXZ 地面/空中 S12 立即应用、全部入站直通、逐 tick 移动即时发送、落地不重放旧速度、攻击意图过期/覆盖/生命周期清理、Instant Attack 正常 tick 上限与原 Timer 保留；当时保留 Jump Reset / Mix 回放以及适配层 FIFO/promises、攻击入口、事务边界、声音、自愈与队列上限回归。VERIFIED：`ZenAntiKBTest: 4385 passed, 0 failed`，命令 exit 0；两份 SRG jar 的 11 处 MovementInput 字段引用均通过真实 Minecraft 1.8.9 + Forge 对照。回执：`/tmp/openmaver-noxz-immediate-tests.log`。这些替身测试证明模块没有额外挂起/回放，不证明实际服务器零告警或实际移动间隔稳定在 40–60ms。

历史相邻回归（本次仅改 NoXZ，未重跑）：`tools/chest-test/run.sh` 输出 `[chest-test] OK (100 项断言)`，exit 0；`tools/godbridge-test/run.sh` 输出 `checks: 73  passed: 73  failed: 0`，exit 0。回执分别为 `/tmp/openmaver-protocol18-chest.log`、`/tmp/openmaver-protocol18-godbridge.log`。

Scaffold 回归有两项已有的 HUD 文本常量断言失败：要求紫色 BAR_FILL 和 196 宽，HEAD 实际为白色和 160 宽。`mc/ScaffoldHud.java` 与该测试文件均未改动，其他 86 项通过；从 HEAD 提取所读源码后重跑，也复现同样 86/88 和两项 HUD 失败。基线回执在 `/tmp/openmaver-zen-scaffold-baseline.log`；本次复跑 `/tmp/openmaver-protocol18-scaffold.log` 仍为 `checks: 88  passed: 86  failed: 2`，exit 1。

VERIFIED：`cmake --build build -j2` 输出 `[100%] Built target openmaver_mod`，exit 0；`git diff --check` exit 0。构建回执：`/tmp/openmaver-noxz-immediate-build.log`。两个 jar 均包含三模式及声音兼容的 Java 8 / SRG 类，未包含测试或 Minecraft stub 类。

产物：`build/openmaver-mod.jar`（Forge mods）和 `build/openmaver-mc.jar`（注入集成）。UNRESOLVED：游戏内视觉、真实物理及服务器反击退效果，尚无本次实测回执。

本轮已将新 mod jar 安装至 Prism `1.8.9(1)/minecraft/mods/openmaver-mod.jar`，并核对 SHA256 与构建产物一致（`d893b1573fc140439c75188ca9f63057f48d68fa7c3fd66b11e143434903c45b`）。按原交接建议将实例 `bypass.profile=aggressive` 改为 `balanced`，其他配置保留；旧实例 jar 与配置备份在 `/tmp/openmaver-noxz-immediate-backup/prism/`。安装时没有运行中的游戏进程，后续启动后再做服务器实测。

## 回滚

本次 NoXZ 直通修复前的源码、测试、文档及两份 jar 备份在 `/tmp/openmaver-noxz-immediate-backup/`，目录保留仓库相对路径，包含会话开始时已有的未提交修改。恢复时只复制需要回滚的文件并重新构建，不要 reset 工作树。

本次日志修复前的已有文件和旧 jar 保存在 `/tmp/openmaver-protocol18-backup/`，相对路径加 `.bak`，其中保留修复前已有的工作树修改。例：

```sh
cp /tmp/openmaver-protocol18-backup/mc/AttackReduceFeature.java.bak mc/AttackReduceFeature.java
```

ROLLBACK：`python3 /tmp/openmaver-protocol18-backup/rollback.py`，将备份的 `.bak` 恢复到对应源码、测试、文档和 jar 路径，并删除本次新增文件；回滚后需重新构建以更新编译目录。更早的三模式移植备份仍在 `/tmp/openmaver-zen-port-backup/`。备份位于临时目录，跨重启保留情况由本机管理。


## 2026-11-21 Sprint / Jump Reset 输入式重写

参考 `/home/zty/Downloads/script.lua` 的短按跳跃、GUI 暂停和随机反应思路；受击入口使用本地 S12，不移植脚本的左键循环、Sleep、右键格挡或系统按键注入。

`JumpResetMode(false)` 只跳一次，`JumpResetMode(true)` 同时向前疾跑 2 tick。包、速度、相机、Timer 均不因重置而延迟或改写。Chance 每次有效 S12 只抽取一次；Accuracy 是零额外输入延迟的概率，失准从 1 / 2 tick 等概率选择。地面和有效目标等条件在执行时复查，若已离地则取消，不在落地后补跳；过期时也只丢弃输入意图。100% Accuracy 在下一次输入回调尝试，0% Accuracy 总有 1–2 tick 意图延迟；客户端物理实际已离地时该意图仍会取消。

输入在原版 MovementInput 更新后写入，每次受击至多一个 jump 脉冲；重复输入回调不消耗额外冲刺 tick。自动开启的疾跑在脉冲结束后归还；原本已疾跑则保留。潜行、使用物品、GUI、Scaffold/GodBridge 和 NoFall/Backtrack 活动时跳过，禁用、切模式、断线、重生、换世界和位置校正清理输入及模块开启的疾跑。新 S12 替换旧意图，不保存旧击退。参数仍使用 `velocity.zen.*`，无需改变版本 20；缺省/无效模式回到 Sprint Reset，已有显式 NoXZ / Jump Reset / Mix 配置保留。

VERIFIED：`cmake --build build -j2` → `[100%] Built target openmaver_mod`（exit 0）；`tools/attackreduce-test/run.sh` → `ZenAntiKBTest: 4492 passed, 0 failed`（exit 0），并输出两份 jar 的 `12 field references match Minecraft 1.8.9 + Forge`。回执分别为 `/tmp/openmaver-sprint-reset-build.log` 和 `/tmp/openmaver-sprint-reset-tests.log`。新增覆盖单次跳跃、2 tick 冲刺、输入/疾跑归还、连续 S12、Chance/Accuracy 边界、目标/水中开关、GUI/潜行/物品保护、生命周期、空中意图取消及全部四模式的协议边界。适配层挂起队列测试仍保留，旧延迟协议场景转由 Mix 驱动。

UNRESOLVED：游戏内真实移动、视觉和服务端效果尚无本轮实测回执。

本轮修改前的源码、测试、文档和两份 jar 位于 `/tmp/openmaver-sprint-reset-wbu30fbv/`，保留相对路径并追加 `.bak`。ROLLBACK：`python3 /tmp/openmaver-sprint-reset-wbu30fbv/rollback.py` 恢复全部本轮修改前文件，再重新构建。单文件恢复例：

```sh
cp /tmp/openmaver-sprint-reset-wbu30fbv/mc/antikb/JumpResetMode.java.bak mc/antikb/JumpResetMode.java
cmake --build build -j2
```
