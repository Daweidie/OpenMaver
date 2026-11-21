# Velocity：OpenZen AntiKB 完整模式移植

## 来源与范围

来源是 `../OpenZen` 当前提交 `9577d2f` 中的以下文件（这些来源文件工作树干净）：

- `src/main/java/shit/zen/modules/impl/combat/AntiKB.java`
- `src/main/java/shit/zen/modules/impl/combat/antikb/{AntiKBMode,NoXZMode,JumpResetMode,MixMode}.java`
- `src/main/java/shit/zen/modules/impl/combat/KillAura.java` 的攻击顺序和 Keep Sprint 默认行为

按用户要求，移除了 Maver 的 SPRINT HIT / SIMPLE / SLAP ATTACK 状态机、百分比缩放、动作闸门、冲刺同步窗口及对应测试。保留模块类 `module.movement.AttackReduce`、显示名 `Velocity` 和配置键 `movement.attackreduce`，避免破坏开关与热键。

`mc/AttackReduceFeature.java` 是 Minecraft 1.8.9 适配层；`mc/antikb/` 中三个模式实现 Zen 状态机。项目使用默认 package，目录仅组织源码。Java 8 字节码由现有 CMake 构建流程生成，再经过 MCP → SRG 重映射。

## 三模式

| 模式 | 触发与队列 | 释放与动作 |
| --- | --- | --- |
| NoXZ（默认） | 自己的正 Y S12；地面、有效目标、疾跑时直接施加原包并安排攻击，否则挂起原包、部分入站包及 C03 | 落地可攻击时先回放击退再攻击，其余包在输入后的 Motion 阶段回放；12 tick 超时释放；位置校正后冷却 2 tick |
| Jump Reset | 自己的 S12；除聊天/时间包外的入站暂存 | 地面 10 tick / 空中 20 tick 倒计时结束后的下一 tick 释放；空中落地提前释放；地面分支跳一次，可转向/跟随方向 |
| Mix | 正 Y S12 及实体移动、事务、实体传送包 | 落地或连续空中 24 tick 释放；受伤跳跃、前 3 tick 方向接管、可选 Aura 双击 |

NoXZ 的目标顺序为 Aura 已选目标 → 准星存活生物，眼睛到目标 AABB 距离 ≤ 3.7。每次攻击先退出疾跑，再调用原版 controller，随后挥手；原先疾跑则只手动执行一次 XZ×0.6，Y 保持。1.8 控制器的疾跑减速因此不重复执行。

Mix 记录受击时的 `wasSprinting`，默认按 Zen `keepSprint=true` 处理：双击的每一次都先退出疾跑，再按快照执行 XZ×0.6，最后攻击目标。Maver Aura 没有 Keep Sprint 设置，本次固定采用 Zen 默认值；复用现有目标，不移植整个现代 Aura 的多目标、反机器人和攻击特效子系统。

## 依赖映射和接线

| OpenZen 1.20.1 | OpenMaver 1.8.9 |
| --- | --- |
| ClientboundSetEntityMotionPacket | S12PacketEntityVelocity，仍为 1/8000 单位 |
| ServerboundMovePlayerPacket | C03PacketPlayer 及 C04/C05/C06 子类 |
| ClientboundMoveEntityPacket | S14PacketEntity 及其子类 |
| ClientboundPingPacket | S32PacketConfirmTransaction（旧协议的同步事务；非同构消息） |
| ClientboundTeleportEntityPacket | S18PacketEntityTeleport |
| ClientboundPlayerPositionPacket | S08PacketPlayerPosLook |
| Chat / Time / HurtAnimation / Animate | S02 / S03 / S19 / S0B；NoXZ 仅放行其他实体的 S0B |
| ReceivePacketEvent | Netty 入站 → Minecraft 主线程 → 模式 → processPacket/func_148833_a |
| Tick / Sprint | ClientTickEvent.START，位于 Aura/AutoSprint 更新之后 |
| Strafe / GameTick 输入 | ScaffoldMovementInput，原版输入更新后、移动计算前 |
| Motion PRE | 输入回调末尾；tick END 另作回放兜底 |
| RotationHandler | 输入回调写入连续 yaw/pitch 和 MoveFix；tick END 恢复相机，保留鼠标增量 |
| ZenClient.serverTickRate | Minecraft.timer.timerSpeed / field_74278_d；恢复接管前的倍率 |
| StuckInBlock 的蛛网观测 | 1.8 Entity.isInWeb / field_70134_J |

NoXZ 原源码把 ServerboundMovePlayerPacket 分支写在 ReceivePacketEvent 中；本次将其接到真正的 Netty `write` 路径。缓存原始 C03 和 ChannelPromise，eventLoop 按 FIFO 回放；断线失败结束 promises。计时直接接到 1.8 Timer，避免只修改无消费者的静态值。

Jump Reset 的 NoFall/Backtrack 联动检查对应活动状态（NoFall boost 字段，Backtrack isActive/isBacktracking），缺失模块时自然为 false。FireballBlink/HighJump/Stuck 等依赖通过模块名查询，不引入现代 Minecraft 类。

## 生命周期适配

- 模式判断、世界和目标访问仅发生在 Minecraft 主线程。Netty 负责转交入站包、保存/释放出站包。
- Packet 保存所属 player/world/handler。旧世界的挂起队列丢弃，同连接模式切换则回放一次。S07 重生显式识别同连接世界切换，保留紧随重生、已经排入主线程的新世界包。
- 禁用、切模式、位置校正、重生会释放当前连接队列并恢复计时/相机。断线终止未完成的出站 promises。
- 入站/出站队列各设 4096 包上限，达到时强制释放。安装失败每 20 tick 重试；外部移除 hook 时清队列并重装。
- 死亡、观察者、飞行、水/熔岩、燃烧、梯子、睡眠和蛛网时释放并跳过模式处理。这一公共保护把 Zen NoXZ 的环境检查用于三模式，替换 Mix 的蛛网事件依赖。
- 转向让位于 Scaffold/GodBridge/Aura；输入直接改 MovementInput，不持有 KeyBinding。潜行 MoveFix 保留原版 0.3 输入幅度。
- NoXZ 正常超时先释放原始速度，余包留到 Motion；关闭/校正等终止路径立即完成剩余回放，避免队列在 reset 时丢失。

这些是协议、线程和事件系统适配；游戏物理与服务器行为仍需端到端验证。

## 参数与配置

配置版本为 20。旧 `velocity.*` / `attackreduce.*` 参数在升级时清除；模块开关/热键 `movement.attackreduce` 不变。新模式使用字符串，避免历史数值编号误映射。

| velocity.zen. 后缀 | 默认值 | 范围 / 生效模式 |
| --- | --- | --- |
| mode | NoXZ | NoXZ / Jump Reset / Mix |
| attackAmount | 5 | 1–20，NoXZ |
| instantAttack | false | NoXZ：等待 0.5×，累积最多 3，执行 4× |
| sprintStateCheck | true | NoXZ |
| rotate | false | Jump Reset / Mix |
| followDirection | false | Jump Reset |
| rotateTicks | 12 | 3–20，Jump Reset |
| tryAttack | false | Mix |
| movementOverride | false | Mix |

## 验证和产物

构建与验证命令：

```sh
cmake --build build -j2
tools/attackreduce-test/run.sh
tools/godbridge-test/run.sh
tools/scaffold-test/run.sh
git diff --check
```

行为测试使用真实 Netty 4.0.23.Final EmbeddedChannel 与 Minecraft 主线程队列替身，覆盖三模式、原包回放、C03 FIFO/promises、攻击顺序和减速次数、超时/落地、静默相机、潜行输入、Timer 恢复、配置往返、模式切换、断线、重生、旧世界隔离与队列上限。VERIFIED：`ZenAntiKBTest: 4224 passed, 0 failed`，命令 exit 0。覆盖出站 promise 取消和出站队列上限。该结果来自替身环境。

Scaffold 回归有两项已有的 HUD 文本常量断言失败：要求紫色 BAR_FILL 和 196 宽，HEAD 实际为白色和 160 宽。`mc/ScaffoldHud.java` 与该测试文件均未改动，其他 86 项通过；从 HEAD 提取所读源码后重跑，也复现同样 86/88 和两项 HUD 失败。基线回执在 `/tmp/openmaver-zen-scaffold-baseline.log`；GodBridge 73 项通过。此处保留原断言，避免把 Velocity 任务扩展成 HUD 外观改动。

VERIFIED：`cmake --build build -j2` 输出 `[100%] Built target openmaver_mod`，exit 0；`git diff --check` exit 0。两个 jar 均包含三模式的 Java 8 / SRG 类，未包含测试或 Minecraft stub 类。

产物：`build/openmaver-mod.jar`（Forge mods）和 `build/openmaver-mc.jar`（注入集成）。UNRESOLVED：游戏内视觉、真实物理及服务器反击退效果，尚无本次实测回执。

## 回滚

所有修改前的已有文件保存在 `/tmp/openmaver-zen-port-backup/`，相对路径加 `.bak`。例：

```sh
cp /tmp/openmaver-zen-port-backup/mc/AttackReduceFeature.java.bak mc/AttackReduceFeature.java
```

完整源码回滚：`python3 /tmp/openmaver-zen-port-backup/rollback.py`；它恢复备份并删除本次新增模式/测试文件。随后重新构建。备份位于临时目录，跨重启保留情况由本机管理。
