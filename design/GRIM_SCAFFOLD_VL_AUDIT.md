# Scaffold / Telly 的 Post、Simulation 与回拉连锁告警

日期：2026-11-25。针对 17:21:24 起的用户日志做源码诊断和本地复现。

已修复两个独立缺陷：Reach 的事务延迟在 Reach 关闭时仍运行，且在搭路动作之后释放 C0F；Scaffold/Telly 共用的 MoveFix 丢失潜行输入倍率。前者可以产生日志中的 Post 包序，也会破坏传送确认的事务边界；后者产生原版潜行物理无法匹配的输入。补测还修复了 Netty 卸载方法的 stub 签名错误。

## 核对范围

- 实例：`~/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/`。
- 修复前实例 mod 与当时的构建 jar 的 SHA-256 一致：`79c178209607d149afa56fbbcc06ba2ad615c13db836d7d4f9d1f434d0c3e79b`。后续重新构建的同名 jar 已包含下述修复。
- Grim 官方仓库 commit：[`286d4be56200cb28c0d7f7193ab987c72c647f4e`](https://github.com/GrimAnticheat/Grim/tree/286d4be56200cb28c0d7f7193ab987c72c647f4e)。本地源码位于 `/tmp/openmaver-grim-audit/`。服务器具体版本和配置尚未确认，不能把该 commit 当成服务器的实际版本。
- Minecraft 1.8.9：核对 [MCP 源码](https://github.com/Marcelektro/MavenMCP-1.8.9/tree/master/src/main/java/net/minecraft) 的 `Minecraft`、`MovementInputFromOptions`、`EntityPlayerSP`、`NetHandlerPlayClient`；另核对 [Forge 1.8.9 的 Minecraft 补丁](https://github.com/MinecraftForge/MinecraftForge/blob/1.8.9/patches/minecraft/net/minecraft/client/Minecraft.java.patch)。
- 下述第 1–4 节记录修复前的源码和复现（行号也是修复前的）。第 6 节记录已应用的修复。实例配置与安装 jar 未改；开始调查时工作区已有 Scaffold/Telly 修改，已保留。

## 1. Post：事务延迟在 C08 之后释放

实例 `config/openmaver.properties:59` 的实际值：

```properties
reach.pingSpoof=60
reach.pingJitter=40
scaffold.mode=1
scaffold.sneak=true
telly.sneak=true
```

`mc/TransactionDelayFeature.java:68` 的 `tick()` 只检查延迟数值，没有检查 Reach 模块开关；`capture()` 同样只检查钩子和延迟数值。`mc/OpenMaverFeatures.java:3631` 的 getter 直接返回配置值。因此日志中的 `Reach enabled=false` 并不意味着事务延迟已经关闭。

`mc/OpenMaverFeatures.java:579` 先运行 Scaffold/Telly 的 `onTickStart()`，其中 `flushPending()` 可以发送 C08；到第 594 行才执行 `TransactionDelayFeature.tick(mc)`，放行到期 C0F。实际可形成：

```text
上一 tick C03 → 本 tick C08 → 到期的 C0F → 本 tick C03
```

[Grim Post.java:76](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/checks/impl/misc/Post.java#L76) 的状态机是：收到正常 flying 后设 `sentFlying=true`；之后动作包进 `post` 队列；在下一条正常 flying 清空队列之前，如果先收到有效事务确认且队列非空，就记录首个动作的告警。首个动作是 C08 时，文案正是 `player block placement v1.8`。

所以仅仅把 C08 放在 ClientTick START、保证它位于本 tick C03 之前，还不足以满足 Post。还要保证事务确认不会插在动作与该 tick 的 C03 中间。

这里也核实了一个容易误判的地方：原版 `Minecraft.runGameLoop()` 先处理 scheduled tasks，再执行 `runTick()`；Forge START 插在 `runTick()` 内。原版 S32 处理会先通过 `PacketThreadUtil` 回到主线程。因此本次已找到的具体插入来源是我方的延迟释放顺序，不能简单归因于“START 天生早于原版回执”。

## 2. Simulation：MoveFix 把潜行的 0.3 改成 ±1

原版 `MovementInputFromOptions.updatePlayerMoveState()` 先读 WASD，再在 `sneak=true` 时把两个输入分量乘以 `0.3D`。

`mc/ScaffoldMovementInput.java:33` 调完 `super` 后进入 `ScaffoldFeature.afterMovementInput()`。`mc/ScaffoldFeature.java:1246` 的八方向 switch 直接把输入覆盖成 `-1/0/1`，没有重新应用潜行倍率；潜行标记仍为 true。此时移动计算得到的是完整输入，C0B 却向服务端报告潜行。

[Grim FloatInputTransformer.java:13](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/predictionengine/predictions/input/impl/FloatInputTransformer.java#L13) 对 1.8 使用浮点输入，在慢速移动状态下应用 `sneakingSpeedMultiplier`，其默认值为 `0.3f`，然后再乘以原版 `0.98F`。

因此八方向本身正确也不能消除误差：输入的长度已经错了。实例两组 sneak 配置都是 true；`ScaffoldFeature.updateSneak()` 的第 18–20 tick 自动潜行会暴露该问题，Normal 的 Eagle 和手动潜行也可能触发同一个缺陷。

## 3. 回拉后为什么出现 BadPacketsN、Timer、AimDuplicateLook

这一链条由源码和传输层复现支持；没有现场抓包，不能逐个认定日志里的每条告警都来自它。

1. [Simulation 的 OffsetHandler](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java#L35) 在累计偏移或即时偏移满足配置条件时调用回拉。
2. 原版 `NetHandlerPlayClient.handlePlayerPosLook()` 处理 S08 后立即发送 C06，确认传送结果。它不会等待我方延迟的 C0F。
3. 我方只延迟 C0F，C06 直接通过，于是确认传送的 C06 可以先于传送前的事务确认到达。
4. [SetbackTeleportUtil.checkTeleportQueue():346](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/manager/SetbackTeleportUtil.java#L346) 要求事务序号匹配且传送坐标/角度合法，才承认该 C06 是传送确认。过早的 C06 无法满足事务条件；后来事务超过目标序号且仍未确认时，第 363 行触发 BadPacketsN 并可能重发回拉。
5. [GrimProcessor.isTickPacket():76](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/checks/GrimProcessor.java#L76) 排除“已识别的传送”。未识别的额外 C06 则可能被 [Timer](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java#L66) 作为额外 tick 记入，每次增加 50ms 时间预算。
6. [AimDuplicateLook](https://github.com/GrimAnticheat/Grim/blob/286d4be56200cb28c0d7f7193ab987c72c647f4e/common/src/main/java/ac/grim/grimac/checks/impl/aim/AimDuplicateLook.java#L20) 也豁免已识别的传送；未识别的 C06 若携带与上一 rotation 相同的 yaw/pitch，就可能命中重复视角检查。

这解释了日志中“先 Post/Simulation，再 BadPacketsN，随后 Timer/AimDuplicateLook”的顺序，也解释了为什么关闭 Scaffold 后还会继续爆：事务延迟仍在运行。

不能从这份日志推断是 Scaffold 修改了游戏 timer，也不能把正常的恒定 aim 直接判为 AimDuplicateLook 根因。原版 `onUpdateWalkingPlayer()` 在角度没有改变时会选择不带 look 的包，恒定 aim 不会天然产生重复 look 包。

## 4. 本地复现与现有测试盲区

使用生产 `ScaffoldFeature` 和 `TransactionDelayFeature/Hook`，复用现有 Telly fixture，并使用 Prism 自带 Netty 4.0.23 的 `EmbeddedChannel`。探针 `/tmp/OpenMaverVlProbe.java`，结果 `/tmp/openmaver-vl-probe.log`：

```text
PROBE Scaffold sneak=true: vanilla forward=0.300, hooked forward=-1.000 strafe=1.000
PROBE Telly sneak=true: vanilla forward=0.300, hooked forward=-1.000 strafe=0.000
PROBE Reach enabled=false: [C03(previous), C08(place), C0F, C03(next)]
PROBE teleport transaction overtaken: [C06(teleport acknowledgement), C0F]
```

最后两项验证实际延迟钩子的输出顺序；它们没有运行完整 Grim 服务端，不构成现场包序抓包证据。Reach 关闭时 `tick()` 仍可能安装钩子的结论来自前述生产调用链审计；探针用 `installForTest()` 挂载同一钩子。

现有 `tools/telly-test/run.sh` 两个测试均通过，运动测试报告 908 checks passed。但 `TellyMotionTest.java:60` 关闭了 sneak，第 92 行直接设 `motionZ=speed`，没有使用 MoveFix 后的输入驱动物理；也没有模拟 C0F/S08。它验证搭桥落点，不能证明潜行物理或事务包序正确。`TransactionDelayTest` 的 FIFO 验证也只覆盖 C0F 之间的顺序。

## 5. 修复应覆盖的边界

- 事务延迟必须尊重所属模块的开关；Reach 关闭时不能继续悄悄延迟所有 C0F。
- 全部到期回执应在 Scaffold/Telly 等动作之前释放，不能只调整 Reach 自身的动作顺序。
- 处理传送时需同时考虑 C0F 与 C06 的相对顺序；仅把每 tick 的释放位置前移，不能修复 scheduled-task 阶段已经被 C06 超越的事务。不要用额外伪造传送确认包补洞。
- MoveFix 在重新选择八方向后必须保持原版潜行缩放和浮点行为。
- 验证需包含潜行输入实际参与的移动、Reach 关闭但保留非零 ping 配置、C08 与事务穿插、S08 回拉与事务穿插。

## 6. 已应用的修复与验证

- `OpenMaverFeatures.prepareTick()` 刷新配置并释放到期事务，Forge START 在 BlockHit、Velocity、背包、箱子、搭路和攻击之前只调用一次；动作阶段使用 `onTickStartPrepared()`，不再二次释放 C0F。独立入口及原有失败兜底也走相同的准备顺序。
- 事务延迟同时检查 Reach 开关和延迟值。模块关闭、延迟归零时，先放行旧 C0F 再放行新回执；tick 清理在移除钩子前完成写出与 flush。
- 收到 S08/S07 时，在原版处理任务前后分别排主线程边界，再按同序排入 Netty event loop。前边界释放旧 C0F，后边界排在原版 C06 的异步 write 后，期间暂停延迟。Netty 收包本身不立即 flush，避免打断正在进行的 C08→C03。
- 事务入站钩子安装在 Velocity 钩子之前，因此 Velocity 吞包并自行处理时同样受边界保护。旧连接的边界任务不能影响新钩子。
- `ChannelPipeline.remove(String)` 的编译 stub 返回类型修正为真实 Netty 4.0 的 `ChannelHandler`。原先错误的 `ChannelPipeline` 返回类型会触发 `NoSuchMethodError`，被事务卸载的 catch 吞掉，留下同名钩子。
- MoveFix 八方向重映射后，潜行输入使用 `(float) ((double) value * 0.3D)`，保持原版的倍率与浮点转换；未潜行输入继续使用整数方向。保留已有 Telly 预测和转向修复。

验证结果：

| 检查 | 结果 |
|---|---|
| 旧构建负向复现 | 新增模块关闭测试与潜行加速度测试均能检出旧缺陷 |
| Reach 与事务队列/包序 | 队列开关、FIFO、C08/C03 中途收到 S08、异步 C06、连续传送、S07、卸载重装及旧连接边界测试通过 |
| Telly 行为 / 跑跳落点 / 潜行物理 | 182 / 908 / 11640 项通过；潜行测试覆盖 Scaffold Normal、两种 Scaffold Telly、独立 Telly 两种模式、八方向、手动/自动/关闭潜行 |
| Scaffold 放置与转向 | 88 项通过 |
| Velocity | 4867 项通过，包含两种钩子安装顺序及实际出站暂存队列与 S08/C06 共存 |
| 窗口钩子 / 容器移动 / Chest | 回归通过 |
| 启动 / 打包字段链接 | 40 项启动检查通过；两个 jar 各 112 个 Minecraft/Forge 字段引用与真实 1.8.9/Forge 名称及描述符匹配 |
| 构建 | `cmake --build build -j2` 成功，产物 `build/openmaver-b2.2.0-2026-11-25.jar` |

潜行物理测试固定支撑几何，使用真实输入 hook 输出驱动水平加速度和位移积分，并与原版按键组合可产生的结果比较；它不代替完整 Minecraft/Grim 服务端联调。包序测试使用真实 Netty 4.0.23 和分离的主线程/网络队列。没有连接原服务器复测，因此这里只确认上述代码缺陷已修复及本地回归通过，不承诺任意服务器或模块组合下零 VL。
