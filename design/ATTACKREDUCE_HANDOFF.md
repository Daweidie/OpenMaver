# Velocity：Myau / Zen 参考移植

显示名 `Velocity`，实现类 `module.movement.AttackReduce`，开关及热键保持 `movement.attackreduce`。本轮修改前已备份工作区相关文件（包括此前未提交修改）：`/tmp/openmaver-velocity-before-reference-port.tar.gz`。

## 日志与来源

用户的 2026-11-21 00:47 日志显示处理器已安装，随后发生 AntiKB、Simulation、GroundSpoof。这证明旧版直接缩放 S12 与服务端预测有差异；不是模块没有加载。

本次实际读取的参考仓库：

| 来源 | 采用部分 | 本工程对应 |
| --- | --- | --- |
| `../OpenMyau-Fix/src/main/java/myau/module/modules/Velocity.java:279` | `Slap_Attack` 的多 tick 攻击、C0A→C02、XZ×0.6、结束冲刺 | `AttackReduceFeature.updateSlapAttack` |
| 同文件 `:302`、`:419` | SEND 动作标记、C03 清空 `badPackets` | `noteOutbound`、`hasBadPackets` |
| 同文件 `:397` | S12 水平幅度表及回归公式 | `calculateSlapTicks` |
| `../OpenZen/src/main/java/shit/zen/modules/impl/combat/antikb/NoXZMode.java:267` | Aura 目标优先、准星目标回落 | `velocityAuraTarget`、`updateSlapAttack` |
| 同文件 `:274`、`:425` | 生存/目标验证和单次攻击减速的职责分离 | 目标/玩家检查；原始 C02 后只减速一次 |

OpenZen 的 `gradle.properties` 明确为 Minecraft 1.20.1，原版 NoXZ 还会挂包、等待落地、改变计时。本工程移植的是上述目标选择和攻击减速结构；不是完整 NoXZ 移植，也没有宣称实现原版 Jump Reset/Mix。

## 默认 SLAP ATTACK（mode=2）

1. Netty 保留本地 S12 的所有字段；vanilla 将处理任务排入主线程后，模块再排入开窗任务。开窗核对模块、模式、世代、玩家/世界及处理器实例。
2. 输入 `hypot(motionX, motionZ)` 使用 S12 原始整数，单位为 1/8000；不是先除以 8000。水平为零的包不触发额外攻击。
3. tick START（Aura 选目标之后、原版 C03 之前）消耗一 tick 窗口并尝试一次攻击。窗口可跨多个 tick，每次需要重新满足冲刺及动作条件。
4. 攻击顺序为 `C0APacketAnimation → C02PacketUseEntity(ATTACK) → motionXZ *= 0.6 → setSprinting(false)`。不调用 `PlayerControllerMP.attackEntity`，以免它额外执行原版减速；不修改 motionY、onGround 或 serverSprintState。

默认幅度表：

| 水平幅度上界 | tick |
| --- | --- |
| 500 | 3 |
| 1000 / 2000 | 4 |
| 3000 | 5 |
| 4000 / 5000 | 6 |
| 6000 / 7000 | 7 |
| 8000 / 9000 | 8 |
| 超过 9000 | 9 |

关闭 `SLAP EXACT` 时使用 Myau 公式：`clamp(round(6.43153527E-4 * magnitude + 2.9419087136), 1, 10)`。表采用参考项目默认值，未移植它的 11 个独立 tick 滑块。

攻击条件及本地适配：

- 本地 `isSprinting/func_70051_ag` 和 `serverSprintState/field_175171_bO` 均为真；movementInput 前进或横移非零。冲刺由玩家/AutoSprint 正常提供。
- 优先使用 Aura 已选且旧视角瞄准条件已满足的目标，否则按设置回落到准星实体。必须存活、可见、当前 3 格射线命中实际 AABB。相比 Zen 原值 3.7，本工程按 1.8 的 3 格限制；不使用其扩大距离。
- 不要求目标 `hurtTime=0`，这与 Myau Slap_Attack 一致；原 SPRINT HIT 仍保留该限制。
- 无 GUI、未使用物品、未骑乘、未在蛛网/水/熔岩、无手动左右键操作。搭路、箱子及 Criticals 模块启用时让位。
- Aura 和 AutoClicker 可启用；仅本 tick 已实际发生攻击/右键/放置时让位。`velocityActionUsedThisTick` 补上 Netty 尚未观察到的主线程动作。Aura 的新转向没有就绪时不拿它的目标提前发攻击。

## SEND 动作闸门与线程

`AttackReducePacketHook.write` 观察原包并直接转发，标记：C09 换槽、C0A 挥手、C02 ATTACK、C08 放置、C07 挖掘、C0D/C0E 背包动作、C16 OPEN_INVENTORY。C02 INTERACT 与 C16 REQUEST_STATS 不标记。C03 及 C04/C05/C06 子类清空已观察动作。

主线程与 Netty 共用锁保护的动作状态。模块发送前预约自己的 C02 对象，只有观察到该 C02 及随后的 C03 才释放；此前延迟的 C03 不会放掉尚未写出的预约。部分发包失败时停止窗口、保留失败状态和预约，避免反复补攻击。重新启用、切模式、世界/连接变化会重置。

处理器按实例隔离，旧连接或旧处理器的迟到 SEND/开窗回调不影响新实例。管线外部移除后每 20 tick 重试安装。此观察点位于 Netty SEND，不等同于 Myau 的主线程 `NetworkManager.sendPacket` Mixin；本工程用自身模块的主线程动作标记补偿异步窗口，未对其它第三方模组注入 SEND 入口。

## 其它模式与配置

| 键 | 默认值 | 含义 |
| --- | --- | --- |
| `velocity.mode` | 2 | 0=SPRINT HIT；1=SIMPLE；2=SLAP ATTACK |
| `velocity.slapExact` | true | 使用幅度表；false 使用回归公式 |
| `velocity.slapCrosshair` | true | Aura 无目标时回落准星；false 仅 Aura |
| `velocity.horizontal` | 0 | SIMPLE 水平百分比 |
| `velocity.vertical` | 100 | SIMPLE 垂直百分比 |

配置版本升至 19，升级旧版本时选择 SLAP ATTACK；保留开关、键位和 SIMPLE 百分比。v19 保存后的模式选择正常往返。

SPRINT HIT（0）保留旧实现：应用 S12 后 6 tick 内最多一次 `swingItem → controller.attackEntity`，由原版减速，保留目标 hurtTime 和其它动作模块限制。SIMPLE（1）保留直接缩放本地 S12；用户现有 Grim 报错来自这条路径。S27、他人 S12、移动和事务包正常转发。

GUI 显示模式、PACKET/WAITING、KB/HIT 和原因；常见状态为 `WAIT SPRINT SYNC`、`NOT MOVING`、`NO TARGET`、`OTHER ACTION`、`BAD PACKETS`、`HIT`、`EXPIRED`、`SEND FAILED`。HIT 表示客户端减速执行完成，不代表服务器接受该次行为。

## 验证与回滚

运行 `cmake --build build -j2` 和 `tools/attackreduce-test/run.sh`。测试使用 Minecraft 替身及真实 Netty 4.0.23.Final，验证包字段、主线程顺序、幅度阈值、重复减速、动作分类、异步预约、Aura 协作、部分失败、实例隔离、配置和重连。具体本轮回执见下一节。

UNRESOLVED：尚未运行目标服务器/Grim 的端到端验证，当前产物不等同于已证实的 Grim 绕过。

修改文件：`mc/AttackReduceFeature.java`、`mc/AttackReducePacketHook.java`、`mc/OpenMaverFeatures.java`、`mc/GuiOpenMaverClickGui.java`、测试目录、README 与本文件。新增测试：`tools/attackreduce-test/AttackReduceSlapTest.java`。

在仓库根目录回滚本轮（还原到本轮开始前的工作区状态）：

```sh
tar -xzf /tmp/openmaver-velocity-before-reference-port.tar.gz && rm -- tools/attackreduce-test/AttackReduceSlapTest.java && cmake --build build -j2
```

## 本轮执行回执

VERIFIED：`cmake --build build -j2 > /tmp/openmaver-velocity-reference-build.log 2>&1 && tools/attackreduce-test/run.sh > /tmp/openmaver-velocity-reference-tests.log 2>&1` 返回 exit 0。

以下是本次实际查询日志的字面输出：

```text
rg 'Test:|Exception|Assertion' /tmp/openmaver-velocity-reference-tests.log
AttackReduceHookTest: 122 passed, 0 failed
AttackReduceSprintHitTest: 64 passed, 0 failed
AttackReduceSlapTest: 134 passed, 0 failed
AttackReduceInstallTest: 5 passed, 0 failed
```

共 325 项通过。包含处理器外部移除后的即时状态变化、旧回调隔离和孤立预约清理。

```text
tail -4 /tmp/openmaver-velocity-reference-build.log
[100%] 打包 openmaver-mc.jar（运行期注入用）
[100%] Built target openmaver_mc_jar
[100%] 打包 openmaver-mod.jar（可直接放入 mods/）
[100%] Built target openmaver_mod
```

产物 `build/openmaver-mod.jar`，SHA-256：

```text
sha256sum build/openmaver-mod.jar
1b247fa9fde6923bb165cbb4c582868dcc8bb944372737da3b89f6d3ba4df2de  build/openmaver-mod.jar
```

VERIFIED：`javap -verbose build/mc-classes-reobf/AttackReduceFeature.class` 的字节码版本切片为 `major version: 52`（Java 8）。完整日志保存在上述 `/tmp` 路径。

## 2026-11-21 实战失效定位与聊天调试

本轮发现的直接阻断点：`updateSlapAttack` 将 `Mouse.isButtonDown(0)` 和右键一并视为 `PLAYER BUSY`，在减速前返回。3–9 tick 窗口仍逐 tick 消耗，持续按住左键可以使整份 S12 窗口从未减速就结束。旧 `SPRINT HIT` 也有这个条件；原回归用例将 `manual click` 下速度不变视为预期行为，所以原 325 项全通过并不覆盖“战斗按住左键时应产生额外减速”的需求。

本次读取当前 Prism 实例 `1.8.9(1)/minecraft/config/openmaver.properties`：`velocity.mode=2`、`autoclicker.requireHold=true`、`killaura.requirePress=true`。两个攻击模块都可能要求按住左键，而 Velocity 同时因左键按下退出。13:25:32 的实例日志确认 Velocity 已启用且包处理器安装成功；13:26:16 又启用了 AutoClicker。旧实例日志没有逐次受击的阻断状态，具体那次实战需由新调试日志核对。

本轮只增加观测，保留原减速和动作条件用于对照。新增 ClickGUI `CHAT DEBUG`，默认开启，持久化键 `velocity.chatDebug`。输出两行：

```text
[Maver] Velocity before(x:0.4000 y:0.3600 z:-0.2000) after(x:0.4000 y:0.3600 z:-0.2000)
[Maver] Velocity mode=SLAP ATTACK reason=PLAYER BUSY window=5 left=true right=false sprint=true sentSprint=true actions=0 localAction=false modules=none
```

SLAP / SPRINT HIT 的 before/after 是同一次尝试前后的本地速度，避免把后续摩擦/重力误认为模块减速。SIMPLE 则报告同一份 S12 原始值和实际缩放后的整数值 /8000。每份本地 S12 重新开始诊断；首次阻断、原因/上下文变化、每次 HIT 和窗口结束会显示，同状态连续阻断不逐 tick 刷屏。最后一条保留阻断原因并标 `expired=true`，不被 HUD 的 `EXPIRED` 覆盖。纯垂直 S12 报告 `NO HORIZONTAL KB`。

全部聊天在主线程通过 `ChatComponentText` + `addChatMessage/func_145747_a` 本地显示，并同步写 `Logger`。SIMPLE 的聊天回调同样校验世代、模式、玩家、世界和 hook 实例；其它实体不输出，聊天显示失败不影响减速。

修改路径：`mc/AttackReduceFeature.java`、`mc/GuiOpenMaverClickGui.java`、`tools/attackreduce-test/AttackReduceSprintHitTest.java`、`tools/attackreduce-test/AttackReduceHookTest.java`、`tools/attackreduce-test/run.sh`、`README.md`、本文件。新增 `tools/attackreduce-test/AttackReduceChatDebugTest.java` 和 `tools/attackreduce-test/net/minecraft/util/ChatComponentText.java`。

回滚已有文件：`tar -xzf /tmp/openmaver-before-velocity-chat-debug.tar.gz`；两个新增文件只被测试 runner 使用，回滚 runner 后不参与构建。

VERIFIED：`cmake --build build -j2 > /tmp/openmaver-velocity-chat-build.log 2>&1 && tools/attackreduce-test/run.sh > /tmp/openmaver-velocity-chat-tests.log 2>&1` 返回 exit 0。实际输出切片：

```text
AttackReduceHookTest: 122 passed, 0 failed
AttackReduceSprintHitTest: 64 passed, 0 failed
AttackReduceSlapTest: 134 passed, 0 failed
AttackReduceInstallTest: 5 passed, 0 failed
AttackReduceChatDebugTest: 25 passed, 0 failed
```

共 350 项通过。新增用例实际复现：同样的 `.4/.36/-.2` 击退，按住左键时整窗口保持不变，松开后在同一窗口变为 `.24/.36/-.12`。另覆盖 SIMPLE 原包/实际截断值、区域小数格式、显示失败、旧模式/旧世界回调、调试开关和纯垂直包。

VERIFIED：读取本机 Forge 1.8.9 的真实 `deobfuscation_data-1.8.9.lzma` 映射包含 `EntityPlayerSP/func_145747_a (Lnet/minecraft/util/IChatComponent;)V` 和 `ChatComponentText` 类。构建 jar 中为 Java 8 字节码，包含 `velocity.chatDebug` 与 `func_145747_a`，未打入测试 ChatComponentText 替身。

本轮 `build/openmaver-mod.jar` SHA-256：`d0433bdbe03b4e7ac99adf6b4ff81cc348cd9ecc4881e6afa86d0c10ccbc6f0a`。

## 2026-11-21 用户实战回执与修复

用户提供的 13:46:22 与 13:46:25 回执两次均是 `reason=WAIT SPRINT SYNC`、`sprint=false sentSprint=false`，窗口从 5 tick 到期；`left/right=false`、`actions=0`、`modules=none`。因此首轮假设的鼠标阻断与该次实战不符。实例配置 `sprint.onlyForward=true`；AutoSprint 的主线程逻辑在 `moveForward <= 0` 时直接返回。旧诊断没有记录方向输入，不能仅从回执确认是否后退/横移，还是 sneak/item 条件；本轮新增 `forward/strafe/sneak/usingItem/autoSprint/onlyForward` 字段以精确区分。

修复：`SLAP ATTACK` 窗口内，完成现有 GUI、生存、物品、骑乘、环境和其它动作检查后，先确认有前进或横移输入且未潜行；若本地 `isSprinting=false`，调用本地 `setSprinting(true)` 并显示 `REQUEST SPRINT SYNC`，本 tick 不攻击。由 vanilla `EntityPlayerSP` 移动更新将本地状态变化编码成正常的 `C0BPacketEntityAction START_SPRINTING`，并更新 `serverSprintState`。后续 tick 仍等待本地及同步标志都为真才攻击；不手动发 C0B、不改同步字段、不修改 S12。这样在 Velocity 的短击退窗口中，即使 AutoSprint 关闭或 `ONLY FORWARD=true` 时玩家后退/横移，也能请求 vanilla 冲刺同步。静止、潜行、使用物品仍不会请求；后退/横移行为需要目标服务器端到端观察确认。

回归新增覆盖：AutoSprint 关闭、`ONLY FORWARD=true`、后退+横移、首 tick 只请求 sprint 且速度未变、观测同步后下一 tick 减速攻击，以及潜行时不请求。原 350 项加本次 6 项，共 356 项通过。

VERIFIED：本轮重新构建与回归命令：

```text
cmake --build build -j2
[100%] Built target openmaver_mc_jar
[100%] Built target openmaver_mod

tools/attackreduce-test/run.sh
AttackReduceHookTest: 122 passed, 0 failed
AttackReduceSprintHitTest: 64 passed, 0 failed
AttackReduceSlapTest: 134 passed, 0 failed
AttackReduceInstallTest: 5 passed, 0 failed
AttackReduceChatDebugTest: 31 passed, 0 failed
```

共 356 项通过；`git diff --check` 通过。构建 jar 字节码为 Java 8（major version 52），测试替身未打入 jar。

修复版已安装到 `/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/mods/OpenMaver.jar`。VERIFIED：`sha256sum build/openmaver-mod.jar '/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/mods/OpenMaver.jar'` 两者均为 `2a44648b1f65bb464cbbb3120c1d2a3e4340598e7860355142c1dd34c54ea9ac`。覆盖前安装包备份在 `/tmp/openmaver-mod-before-velocity-sprint-fix.jar`，SHA-256 为 `e5acd1fd39a792e966d1544a482e7203947eac78834d23403ef18fa065ab3dad`；单文件回滚命令：

```sh
cp /tmp/openmaver-mod-before-velocity-sprint-fix.jar '/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/mods/OpenMaver.jar'
```

UNRESOLVED：尚无安装此修复版后的服务器实战日志。下一次受击时重点检查是否先出现 `REQUEST SPRINT SYNC`，随后 `sentSprint=true` 并出现 `HIT`；若窗口仍以 `WAIT SPRINT SYNC` 到期，新诊断中的 `forward`、`strafe`、`sneak`、`usingItem` 可确定阻断条件。
