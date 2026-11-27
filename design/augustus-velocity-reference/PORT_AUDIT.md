# Augustus Velocity 移植与验证记录

记录日期：2026-11-28。目标 `/home/zty/OpenMaver`；来源 `../OpenAugustus-Maver`，只读。

17 个模式都已有独立实现、设置和事件接线，无占位模式或共用一个算法的套壳。这里的“已实现”指下列来源路径及明确的 Maver 适配；不代表完整移入 Augustus 的外部模块/预测事件体系，也不代表已经通过真实游戏或反作弊服务器验证。

## 来源与复现

- 原始模式、设置与依赖的真实方法来自 `build-inputs/recovered-analysis.jar`；恢复主模块来自 `src/main/java/augustus/recovered/modules/combat/Velocity_47Cc.java` 及 `lib/retained-templates.jar`。移动工具 `net/47uP` 的实现在 `lib/runtime-additions.jar`。
- [source-manifest.json](source-manifest.json) 固定分析过的归档及 35 个相关类的 SHA-256；[mode-names.json](mode-names.json) 固定工厂顺序；[constructor-strings.txt](constructor-strings.txt) 保留构造器解密结果。没有把 compile-headers 的桩实现当算法。
- `VelocityNames.java` 只解密名称；`VelocityDecode.java` 传播事件调用的 long/int 种子，解析动态调用目标及字符串，输出仅供分析的类和 `.targets.txt`。工具生成的 `resolved_net_*` 是分析标记，不能执行或进入游戏 JAR。反编译的复杂分支仍需对照原始 `javap -c -p`，不能直接相信 CFR 的控制流排版。

在 Maver 根目录复现（分析工具需要 JDK 17+，产品代码目标 Java 8）：

```sh
mkdir -p /tmp/maver-augustus-velocity-names
javac -cp '../OpenAugustus-Maver/lib/asm/*' -d /tmp/maver-augustus-velocity-names tools/augustus-velocity/VelocityNames.java tools/augustus-velocity/VelocityDecode.java
java -cp '/tmp/maver-augustus-velocity-names:../OpenAugustus-Maver/lib/asm/*' VelocityNames ../OpenAugustus-Maver/build-inputs/recovered-analysis.jar
java -cp '/tmp/maver-augustus-velocity-names:../OpenAugustus-Maver/lib/asm/*' VelocityDecode ../OpenAugustus-Maver/build-inputs/recovered-analysis.jar /tmp/maver-velocity-decoded 47GN,47Gk,47GT,47GA,47GX,47GO,47G7,47Gz,47Gl,47G_,47GQ,47GC,47GW,47Gm,47Gn,47G6,47GH,47GJ,47Gw,47TT,47Ts,47TU,47gg,47zh
javap -p -c -classpath ../OpenAugustus-Maver/build-inputs/recovered-analysis.jar net.47Gz
javap -p -c -classpath ../OpenAugustus-Maver/lib/runtime-additions.jar net.47uP
```

`VelocityDecode` 输出 `<输出目录>/47GN.class` 等文件，内部映射为 `ref/C_47GN`；可选用 CFR 0.152 反编译单个文件。输出仍包含依赖声明，不是可运行的移植代码。工具不是通用反编译器：全归档事件扫描仍会在 `net/47Cc` 的一处目标解密报告索引越界，部分 lambda/注册调用在 targets 中保留 null。不能将这些输出当成已经自动还原整个来源运行时的证据。本次模式结论以目标解析、原始字节码及依赖消费者交叉核对；上述命令已重跑，17 模式及列出的依赖均能生成分析输出。

## 17 模式对照

表中源方法使用二进制原名；`47(long,47ob)` 为普通收包，`45q(long)` 为更新，`47(472n,long)` 为输入。实现文件位于 `mc/antikb/augustus/`。所有模式还运行公共设置/配置/生命周期测试。

| 模式 | 来源类 / 主要方法 | Maver 实现及已还原行为 | 专项测试（AugustusVelocityTest） |
| --- | --- | --- | --- |
| Basic | `47GN.47(long,47ob)`；构造器 | `AugustusBasic`，PacketModes：自身 S12 的地空 XZ/Y 百分比、整数截断、零轴保留标记；爆炸 float 缩放与零轴重置标记；独立计数区间 | `packetModes`：保留原速度、负比例、截断、地空、Interval、爆炸；`lifecycle`：爆炸重置 |
| Legit | `47Gk.47(472n,long)`；`49S`、`47Ts` | `AugustusLegit`，InputModes：疾跑/焦点/地面/hurtTime=9、药水与跌落历史门限、概率或区间+tick 延迟；非重复/高斯/离群值采样 | `inputAndAttack`、`predictionAndRanges`、`keepSprintCoexistence` |
| PushGround | `47GT.45q(long)` | `AugustusPushGround`，MovementModes：hurtTime 区间内设置 onGround，端点按 TU 语义归一 | `movementModes`：上下边界；`flagAndQueues`：公共 Lag |
| Push | `47GA.45q(long)`；构造器 | `AugustusPush`，MovementModes：Simple 每次增加方向速度（数值÷100），Expert 按 hurtTime 1–10 选独立运动量和地面开关；float 正弦表 | `movementModes`：Simple、Expert、onGround；ClickGUI Expert 滚动 |
| Intave/PolarFlag | `47GX.47/41(long,47ob)`、`45p/45q` | `AugustusFlag`：受伤状态、事务释放阈值、包类型豁免、Y 取整/epsilon、移动包位置同步、S08 解旗；独立入站 FIFO | `flagAndQueues`、`lifecycle`：普通与 Polar 第三事务释放、POST epsilon、校正 |
| Intave | `47GO.46(47zh,...)`、`47(long,47ob)`、`47(472n,long)`、`44/43(47jB,long)`；`47TT.41` | `AugustusIntave`，InputModes：Water/Cobweb/Ladder、Boost 延迟与输入过渡、可选跳跃、FIRST/SECOND 攻击减速、Smart 双路径预测；见下方预测适配限制 | `inputAndAttack`、`predictionAndRanges`、`flagAndQueues`、`keepSprintCoexistence`；真实攻击分支测试 |
| Intave13 | `47G7.45q(long)` | `AugustusIntave13`，MovementModes：受伤、fallDistance<3、水平碰撞时按 float `.195` strafe | `movementModes`：触发与严格 fallDistance 边界 |
| Reverse | `47Gz.45q(long)`、`44(...,47K7,long)`、`43(long)` 注册 | `AugustusReverse`，MovementModes：指定 hurtTime 水平反向及可选区间 strafe；原类未注册接收事件，因此 Strength 控件不产生收包缩放 | `movementModes`：反向、strafe、未接线的 Strength 保持无作用 |
| Spoof | `47Gl.47(long,47ob)` | `AugustusSpoof`，PacketModes：取消自身 S12，发当前位置加速度分量÷8000 的 C04，不本地应用速度 | `packetModes`：C04 坐标及本地不动 |
| SafeWalk | `47G_.46(47tj,long)`；实体移动中的 safeWalk 消费者 | `AugustusSafeWalk` 与 Hooks：受伤且脚下 0.2 为空气时开启原版式 0.05 步进防坠边裁剪 | `movementModes`：空气、支撑方块和边缘裁剪；真实 Entity 钩子 |
| OldMinemen | `47GQ.47(472n,long)` | `AugustusOldMinemen`，MovementModes：hurtTime=9 每四次 `.2f`，hurtTime=5 为 `.1f`，不添加来源没有的公共过滤 | `movementModes`：计数、两个速度及 IgnoreFire 例外 |
| Karhu | `47GC.45q(long)`；`474i.48(BlockPos,long)` | `AugustusKarhu`，MovementModes：下方 1/2 格存在方块碰撞箱且受伤下落时 Y=-10；hurtTime=5 隔次 `.15f` | `movementModes`：碰撞箱、下落、交替计数 |
| CustomMotion | `47GW.47(47Rw,long)`、`47(long,47ob)`、启用方法 | `AugustusCustomMotion`，MovementModes：启用及自身 S12 武装一次实际移动事件，将 XZ 设为地空设置速度；已武装脉冲不重复套接收过滤 | `movementModes`：启用脉冲、单次消费、地空和收包武装 |
| BoundingBox | `47Gm.40(long,46j)` | `AugustusBoundingBox` 与 Hooks：hurtTime > low 且 ≤ high，对空气、`(int)posY+1` 层添加指定高度且与查询相交的碰撞箱 | `movementModes`：高度、整数/分数/负 Y、范围、空块与查询边界；真实 World 钩子 |
| Grim-1.17 | `47Gn.47(long,47ob)`、`44(...,47K7,long)` | `AugustusGrim`，PacketModes：自身 S12 取消、同 tick 校正例外、PRE C06→C07 单帧脉冲；上一上报位置/角度使用真实 SRG 字段 | `packetModes`：C06/C07 顺序、XYZ 各轴、单帧、S08；运行字段语义检查 |
| HypixelTest | `47G6.47(long,47ob)` | `AugustusHypixel`，PacketModes：取消自身 S12；来源取消后的 `Y<.2 && Y>.41995` 不可满足，故不添加猜测跳跃 | `packetModes`：取消与不修改 Y |
| PolarBlock | `47GH.47(long,47ob)`；`46s` 标记消费者 | `AugustusPolarBlock`，PacketModes：地面头顶碰撞箱相交时关闭 S12 水平应用，包括其他实体的 S12；不添加自身 ID/公共过滤 | `packetModes`：其他实体 XZ 保留、Y 应用 |

## 公共设置、界面与旧配置

`AugustusVelocity` 集中定义 114 个实际标量配置项（包含范围的两端、11 个公共项），完整默认值、上下限、小数位和枚举见 [settings.tsv](settings.tsv)。标签对应来源字符串；范围以 Min/Max 两个控件表示，界面沿用 Maver 的滚动列，不移植 Augustus 设置分组外壳。模式/子选项控制显示，旧参数仅在旧模式显示。标签由现有 GUI 统一转为句首大写，持久化模式名称保持来源大小写。

可重新导出实际元数据：

```sh
javac --release 8 -Xlint:-options -cp build/mc-classes:build/java-classes:build/mcstubs-classes -d /tmp/maver-augustus-velocity-names tools/augustus-velocity/VelocitySettings.java
java -cp /tmp/maver-augustus-velocity-names:build/mc-classes:build/java-classes:build/mcstubs-classes VelocitySettings
```

- `NotWhileSPressed` 读取已经更新的移动输入 forward<0；`IgnoreFire` 使用玩家着火状态。旧 OpenZen 的水/梯子/蛛网等过滤仅用于旧三个模式。来源 OldMinemen/PolarBlock 不经过模式公共过滤，此例外保留。
- `NotWhileLongJump` 查询 Maver 的 `ModuleManager` 名称 `LongJump`；当前 Maver 没有此模块，所以这个开关目前没有可触发的 LongJump 集成。没有凭空实现一个 LongJump。
- `Lag` 默认 0，范围 0–400 **毫秒**，自身 S12 重设计时；ticksExisted>20 时延迟入站和出站。Intave Boost 也参加同一延迟事件。两方向分别保序；回放同步生成的 ACK 放在已有出站之后；promise 在实际写出/丢弃时完成。
- PacketCanceling 只有 S00/S32/S48，条件 None/OnlyOnGround/OnlyInAir/Always；默认 None，次数 6，范围 1–10。只有自身 S12 的 X、Z **同时非零**才装入次数，匹配取消递减。Teams 不在枚举中。
- `CancelDamageSoundsFromOtherPlayers` 按来源匹配 `game.player.hurt`/`game.player.die`，不额外增加声音位置或实体归属识别。
- NoXZ=0、JumpReset=1、Mix=2，后接 17 模式；默认 JumpReset 不变。模式仍为 `velocity.zen.mode` 字符串，新参数为 `velocity.augustus.<模式>.<字段>`。settings.version 沿用 23，无需清空或重新迁移用户文件。旧模块开关、热键及 `velocity.zen.*` 继续往返；缺失新键取来源默认值，越界/NaN/Infinity 值归一。
- 保留既有 `TeamPacketGuard`：只跳过不存在队伍的失效操作，创建/有效操作放行；独立兼容 hook 在 Velocity 关闭时也有效，回放入口再次检查当前主线程 scoreboard。

## 事件与依赖适配

`VelocityTransformer` 钩住真实 1.8.9 `Entity.moveEntity`、`World.getCollidingBoundingBoxes`、`EntityPlayerSP.onUpdate` 中父更新前后、`onUpdateWalkingPlayer` 前，以及 `EntityPlayer.attackTargetEntityWithCurrentItem` 的成功伤害分支。普通 Forge 路径用 coremod manifest；动态注入必须通过共享的 `core/jni/velocity_hooks.h` JVMTI 重转换四个已加载类。重复安装保持幂等；四个目标缺一则安装失败，注入失败明确暂停 Augustus 模式，旧模式可继续使用。

输入继续走 `ScaffoldMovementInput`：Scaffold/GodBridge → Velocity → KeepSprint → MoveDisabler。新 Legit/Intave 只在实际写输入时认领本 tick 的输入，KeepSprint 自动让位并在后续恢复。新模式按来源操作 onGround/运动量，未加入会改变来源算法的 Scaffold 全局暂停条件。

FIRST 在原版击退分支替换两个 `.6`，同一次攻击缓存系数；SECOND 在成功伤害且没有 FIRST 的合流点调整本地 XZ/疾跑，早于随后附魔副作用。不对拒绝伤害的攻击减速。攻击帧使用 ThreadLocal 栈，正常返回、递归和异常路径均有清理。新模式不额外调用 Aura/AutoClicker/控制器攻击，不消耗或制造第二个攻击名额，旧模式的攻击名额协调保持不变。

来源改造过 S12/S27，Maver 不改原版包类 schema：包包装对象保存轴标记；在原版处理前快照运动量，处理后按标记保留/归零。PolarBlock 也针对包实际实体恢复轴。效果接点覆盖原版处理完成后的可观测速度；其他外部 mod 若在原版 handler 中间读取该速度，无法观察到 Augustus 原消费者内部完全相同的时序。

Netty 只转交任务和写出；玩家、世界、计时/计数、模式队列均在 Minecraft 调度线程处理。每队列上限 4096；禁用/切模式在同一有效连接释放待处理包；断线/旧世界丢弃并完成 promise，S01/S07 先释放旧状态，再让仍属于同一 handler/channel 的已捕获后续包进入新世界。S08 对旧模式沿用原清理逻辑，对 Augustus 交给来源模式处理（Flag/Grim 需要观察该包）。

## 保留的来源异常与明确限制

- Reverse 的收包缩放方法存在，但实际仅注册事件 43 和 18，未注册 26。保留 Strength 设置而不使它生效。
- HypixelTest 的垂直区间条件不可能满足；没有按名称补造算法。
- Intave WaterInterval 计数只在模运算匹配后增加：例如 interval=2 首次处理后停在计数 1，不改成常见的每包自增。interval=0 的浮点模结果使该路径不匹配。
- CustomMotion 启用时即有一次待消费脉冲；BoundingBox 使用 Java 向零截断 `(int)posY+1`，并非精确 posY+1；Legit/Intave 的 eligible 计数使用严格 `>`。
- Flag 原队列排除 `47vS` 私有 no-op 标记（两个 codec 方法为空）。原生 Maver 没有这种包，不把它错误映射为 C03；捕获的原生入站包全部按状态回放。
- IntaveSmart 的位置预测已还原单步正常移动、.005 速度阈值、物品减速、跳跃冷却/药水、地空加速、潜行边缘、碰撞和两种台阶候选，并比较眼睛位置到准星生物扩张 AABB 的距离。**来源预测还会再次调用活体目标的伤害方法并派发 Augustus 攻击/MoveFlying 预测事件；这里用真实攻击已经接受的结果加纯快照计算替代，不复现预测造成的额外伤害或外部 listener 副作用。** 外部事件监听器对预测输入/速度的改写未移植。
- 来源方向/预测使用 Augustus 旋转管理器；Maver 使用当前有效玩家 yaw，包含本项目已经施加的静默转向。没有移植 Augustus 的独立旋转队列，因此其他模块并发转向时不能宣称逐位相同。
- 所有产物使用 Java 8 编译目标且检查 major≤52，未携带 Minecraft/Forge/ASM 桩或分析类。当前实际测试 VM 是本机 OpenJDK 25，**尚未用 Java 8 VM 或真实游戏/服务器运行**；没有证据保证任何反作弊版本的效果。

## 验证记录

以下是本次本地结果。测试脚本若会重建 `build/mc-classes`，必须串行运行，避免与真实类变换测试争用构建目录。

| 命令 / 范围 | 结果 |
| --- | --- |
| `cmake --build build -j2` | Java/本地库/注入 JAR/mods JAR 构建成功 |
| `bash tools/attackreduce-test/run.sh` | Zen/Teams 5,125；Augustus 4,581；均 0 失败；两 JAR 各 37 处 MovementInput 字段引用通过 |
| `python3 tools/augustus-velocity/test_transform.py` | 44 通过：真实 obf/SRG 方法覆盖、ASM BasicVerifier、`-Xverify:all`、幂等、四类 JVMTI 重转换、攻击 FIRST/SECOND/伤害拒绝/异常清理 |
| `python3 tools/augustus-velocity/check_runtime.py` | 两 JAR 各 64 个反射符号、43 处直接引用通过；Java 8 字节码及洁净包检查通过 |
| `bash tools/clickgui-test/run.sh` | 150 通过，含 20 模式绑定、相关参数显示、Expert 最后运动参数滚动可达 |
| `bash tools/startup-test/run.sh` | 38 通过；两 JAR 各 143 处 Minecraft/Forge 字段引用通过；旧测试的模块数和隐藏 Scaffold 变体断言已更新 |
| `bash tools/keepsprint-test/run.sh` | 74+21+13+135+13,594 通过 |
| `bash tools/scaffold-test/run.sh` / `bash tools/godbridge-test/run.sh` / `bash tools/safewalk-test/run.sh` | 94 / 73 / 61 通过 |
| `bash tools/movedisabler-test/run.sh` | MCP/SRG 各 76+480；InventoryMove 32；Debug 6；来源谓词 6，通过 |
| `git diff --check` | 通过 |

测试覆盖入站/出站 FIFO、回放生成 ACK、取消次数、延迟边界、最大队列、禁用/切模式、断开连接、同连接换世界、输入认领、预测无副作用、设置保存加载和旧配置。大量计数来自队列边界与随机采样迭代，不代表在真实游戏测试了相同数量场景。

交付物：`build/openmaver-b2.2.0-2026-11-28.jar`（放入 Forge 1.8.9 mods）；`build/openmaver-mc.jar`（动态注入部分，需匹配本次构建的 native/core JAR）。mods JAR SHA-256：`d626c2014db3f96e3847369b6cdaf94e42b62d1dae8a5589f7f01d14bee506de`，内置 native 与 `build/lib/libopenmaver_core.so` 一致。测试日志副本在 `build/velocity-verification/`。未提交、推送、发布，也未连接真实服务器。
