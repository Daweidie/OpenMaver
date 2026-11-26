# Intave 背包适配：当前状态与约束

用户要求：`Grim Bypass` 改名为 `Bypass`，可选 Grim / Intave；先适配 MoveDisabler 和
Inventory Manager（IM）。Intave 必须保留关闭背包时整理和点击时继续移动，不能用仅在
GuiInventory 内运行、强制停步或延迟至停步来替代。

当前完成的是 Bypass 改名、目标选择、配置及 profile 保存/恢复，缺省保持 Grim。
Intave 专用包处理尚未实现；现有 MoveDisabler 和 IM 执行路径保持原状。
不能将选择 INTAVE 或本地回归通过当作 Intave 联机兼容验证。

引用版本：`references/anticheats/Intave` d01b2e051；Grim f319e165e。
服务器部署的 Intave 版本尚未确认。随后从本机实例
`/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/logs/latest.log`
找到 2026-11-25 的现场记录：21:10:55 进入 `legacy(1.8.8)`；21:17:33 起反复报告
`clicked in closed inventory`，21:17:35 有 `regular deviation`（0.49 deviation），
21:18:46 起另有连续 Physics 告警。日志没有记录每条出站包，不能将这些 Physics
全部归因于背包模块，也不能假定目标分支是高版本跨协议路径。

## 直接相关的源码

- `check/other/inventoryclickanalysis/NotOpenCheck.java:61`：未打开窗口时，
  `supportsInventoryAchievementPacket()` 为真则提交 `clicked in closed inventory`，
  加 5 VL、取消点击并关窗；否则对窗口 0 的点击自动标记打开。
- `user/meta/ProtocolMetadata.java:156`：是否支持打开背包包取决于客户端协议和
  `!outdatedClient()`；`outdatedClient()` 在第 453 行比较客户端、服务端版本。
  不能简单按客户端 1.8 就决定始终补 C16。
- `check/other/inventoryclickanalysis/OnMoveCheck.java:54`：结合已推断的输入和实际
  水平运动判断点击时行走。满足条件会取消 Bukkit 点击事件、关窗；这条提交的 VL 是 0，
  仍然会阻止操作。STOP_SPRINTING 没有消除输入或位移。
- `check/movement/physics/branch/KeypressBrancher.java:124`：窗口打开时排除非零行走输入。
  将服务端窗口一直保持打开会影响 Physics，不能只补 C16 后一直执行 IM。
- `check/combat/heuristics/inventory/PacketInventoryHeuristic.java:76`：在显式发送空移动包的
  协议路径上，显式打开后没有经过移动帧就关闭会触发 `closed inventory too quickly`。
  第 107 行还检查窗口打开期间的转头。
- `check/other/inventoryclickanalysis/RegrDelayAnalyzer.java:119`：积累超过 30 条有效样本后，
  在关闭窗口时评估速度、方差、距离与时间关系。SWAP / QUICK_CRAFT 不进入该采样。
- `check/other/inventoryclickanalysis/DelayAnalyzer.java:85` 的槽位切换速度分支只构造了
  Violation，没有调用 processViolation；不能据此宣称已实际提交告警。
  第 105 行的标准差分支会提交，但有 isPartner 和协议/服务端版本条件。
- `PacketDelayAnalyzer.java` 中可疑时序的输出被注释，没有提交 Violation。

## 下一步需要验证的行为

根据目标服务端版本、实际协议转换和告警定位有效分支，验证完整的窗口生命周期、
Bukkit 点击事件时读取到的运动元数据及 Physics。单纯 `C16 → C0E → C0D` 同帧包裹
不能宣称满足上述判据；仅包裹 STOP/START_SPRINTING 也不等于 Intave 适配。

任何后续 IM 跨 tick 点击都应核对光标物品、相关槽位及窗口/玩家/世界身份；
关闭窗口、切服、玩家手动干预和模式切换后不能回放过期点击。仍应保留 Grim 的
START 阶段点击时序，并独立回归默认 Grim 路径。

## 2026-11-26 调用链复核：暂不启用 Intave 包策略

逐段复核 `NotOpenCheck`、`InventoryTracker`、`OnMoveCheck`、
`PacketInventoryHeuristic`、`KeypressBrancher` 后，现有参考分支没有支持“移动输入持续有效、
窗口内点击照常生效、同时不改变 Physics 看到的输入/窗口状态”的客户端包序：

1. 点击到达时若 `inventoryOpen=false` 且客户端没有落入 outdated-client 分支，
   `NotOpenCheck` 取消点击并关窗；所以不能只在 C0E 外面补 C0B 冲刺包。
2. 对 `GuiInventory`，`InventoryTracker` 只在收到客户端打开背包命令时置开，
   收到 C0D 时置关；箱子等服务端打开的窗口走服务端 OPEN_WINDOW / CLOSE_WINDOW 路径。
3. `OnMoveCheck` 在 `receivedFlyingPacketIn(2)` 为真时直接豁免点击移动检查；但这不豁免
   Physics。近期移动包因此不能单独作为完整方案。
4. 要让 C0E 被当成打开状态下的点击，窗口打开状态必须覆盖该点击。只在点击前开、点击后
   立刻关，仍会在 `emptyFlyingPacketsAreExplicitlySent()` 路径中命中
   `PacketInventoryHeuristic` 的 `inventoryTicks == 0` 条件。
5. 若为避免过快关窗而让 C16 覆盖一个移动帧，`KeypressBrancher` 在窗口打开时会将所有
   非零输入方向排除；持续按键造成的水平加速不再是 Physics 的输入候选。若把窗口保持打开，
   此矛盾会持续存在。

因此本轮没有把 Intave 标成完成，也没有加入猜测协议版本的 C16/C0D 注入。当前 1.8.9 客户端
无法从现有代码可靠知道服务端 `outdatedClient()` / `emptyFlyingPacketsAreExplicitlySent()`
分支；在现场服务端版本和完整出站包序未知时，自动选择“短开窗”可能直接触发已知时序检查，
选择“跨移动帧开窗”则会把真实移动输入从 Physics 搜索中排除。现有 `InventoryMoveFeature`
仍保持本地容器移动输入；Intave 的点击是否通过及移动物理是否接受均未得到实服验证。

要继续实现而不退化为停步，下一步需要先对目标 Intave 实服抓取一次真实 C16/C0E/C0D 与
C03 的完整顺序，并确认 ViaVersion 后的客户端/服务端协议分支。随后才能评估是否存在该部署
版本特有、可由真实生产 Netty 钩子驱动并同时回归窗口物品结果及 Physics 的可行序列。离线
构建和现有 Grim/输入回归不能替代这项验证。

## 2026-11-26 20:14 实服诊断记录

读取 Prism 实例 `logs/intave-movedisabler-debug.log` 与同一时段 `latest.log`。记录器在线，
Netty hook 已安装在 `openmaver_movedisabler` 之前；包顺序可用于对照。记录中的关键状态是
`bypass=true movedisabler=true target=GRIM`，因此该次运行虽连在发出 Intave 告警的服务器，
本地 Bypass 目标仍保存为 GRIM，不能把它记为 Intave selector 配置下的测试。

- 20:14:29.184 发出 `C16 OPEN_INVENTORY_ACHIEVEMENT`；20:14:29.246 屏幕切为
  `GuiInventory`。20:14:29.636 起记录到 `input.forward=1` 和递增的位置；连续的
  `C03PacketPlayer$C04PacketPlayerPosition` 仍在背包打开期间发送。服务器 20:14:29 至
  20:14:30 连续报告 Physics `moved incorrectly (Δ0.098000)`。这些事件时间吻合，支持
  “背包打开时的非零输入与该参考版本 Physics 分支冲突”的判断；仅凭客户端日志仍不能证明
  部署服务器源码与引用提交完全相同，也不能将 Δ 数值本身映射为特定物理字段。
- 20:14:54.733–20:14:56.065 有真实 `GuiChest` / window 1 的点击，期间没有非零移动输入；
  20:14:56.642 发送对应 C0D 关闭箱子。
- 20:14:59.166 起有一组 window 0 的 C0E，而状态是 `screen=none`、
  `container=ContainerPlayer`；同秒服务器报告 13 次 `clicked in closed inventory`。
  随后 20:15:00.044 才又发 C16 打开背包。20:15:06–07 再次出现一组关闭界面时的 window 0
  C0E 和对应告警。这与 `InventoryManagerFeature` 可在 GUI 关闭时整理、而 Intave 尚未把
  inventory 标为 open 的路径相符；需关闭 Inventory Manager 重测以隔离 MoveDisabler。
- 该次文件没有在 `GuiInventory` 且移动输入非零时发生的手动 C0E，所以不能据此判断移动中
  点击会否被 Bukkit `OnMoveCheck` 取消。前段 Physics 重现与后段 Inventory Manager 的
  closed-inventory 告警是两类独立现象。

下一轮隔离测试：先在 Bypass 中选 INTAVE，关闭 Inventory Manager，仅保留 MoveDisabler 和
Intave Debug；先复测“开 GuiInventory、持续移动但不点击”，再单独进行“持续移动时手动点击
几次”。保留客户端 debug 文件和同一时间窗口的服务器 Physics / InventoryClickAnalysis
日志。当前引用实现会排除打开背包时所有非零 Physics 行走候选，因此实测 Physics 告警仍是
阻塞 MoveDisabler Intave 适配的证据，不能靠屏蔽窗口点击告警来宣称适配完成。

## 2026-11-26 20:21 Intave 目标实测

读取同一 Prism 实例新生成的 `logs/intave-movedisabler-debug.log`、`latest.log` 与
`config/profiles/TestForInvate.cfg`。本次配置为 `bypass.anticheat=intave`，Inventory
Manager 为 off；运行时诊断状态为 `target=INTAVE bypass=true movedisabler=true`，所以这是
一次有效的 Intave 目标测试。服务器版本提示为 `legacy(1.8.8)`，客户端为 1.8.9；参考实现中
`supportsInventoryAchievementPacket()` 对 1.8.8 服务端/1.8.9 客户端为真，需留意真实服务端
版本和协议转换可能不同。

- 20:21:34.622 发出 `C16 OPEN_INVENTORY_ACHIEVEMENT`；20:21:34.862 诊断进入
  `GuiInventory`。20:21:35.400 起出现 `input.forward=1`，随后持续发出带位置的 C03，客户端
  位置从 z=180.497 递增移动。服务器从 20:21:35 起报告 Physics `Δ0.098000`，到 20:21:37
  VL 增至 260。告警开始于持续移动时，早于第一次点击，说明点击不是这轮 Physics 告警的触发条件。
- 20:21:36.123 和 20:21:36.873 各发出一次 window 0 的 C0E，均处于 `GuiInventory`、
  `forward=1` 状态。随后分别收到 action 1 / 2 的 `S32 ConfirmTransaction` 且 `accepted=true`；
  第一次点击后诊断光标变为 White Wool，之后再次变空。这说明该测试中点击事务被服务端确认，
  但没有服务端 InventoryClickAnalysis 日志，不能单凭事务确认断言 Bukkit 点击处理最终结果。
- 20:21:58.136 才发出 window 0 的 C0D。背包窗口保持打开期间，连续移动的 Physics 告警仍然存在。

这次实测确认了两个行为可以分别发生：Intave 接受背包窗口内的移动点击；但打开状态下持续移动
仍触发 Physics。配合引用源码的 `KeypressBrancher`：打开窗口后 Physics 会排除非零输入候选，
当前参考分支无法同时满足“窗口保持打开、持续水平移动、Physics 接受移动”。由于该服务端
支持 C16，省略 C16 后的窗口 0 点击会进入 `NotOpenCheck` 的关闭库存惩罚分支；而只在点击时
短暂 C16→C0E→C0D 又需要继续核验 `PacketInventoryHeuristic` 的过快关闭条件。因此本次证据
还不能证明另一种包序可行，也不应据此把 Intave 标记为已适配。

## 2026-11-26 Intave MoveDisabler 候选包序实现

源码复核后实现了一个仅在 `isIntaveBypass()` 为真时使用的玩家背包路径。其目标是让服务端
在真实移动 C03 到达期间保持 `inventoryOpen=false`，只为 window 0 点击临时打开状态：

1. 本地 `GuiInventory` 打开时抑制 `OPEN_INVENTORY_ACHIEVEMENT`，本地关闭时抑制 window 0 的
   C0D；本地 GUI 和实际按键移动仍照常运行。
2. 对 window 0 C0E（手动 GUI 或其他模块发出的事务包都会命中），发送
   `C16 OPEN_INVENTORY_ACHIEVEMENT → C03(onGround，
   不含位置/朝向) → 原 C0E → C0D(window 0)`。空 C03 放在临时 open 区间，是因为引用的
   `PacketInventoryHeuristic` 在收到任意 flying 包时递增 `inventoryTicks`，而关闭检查只在
   `inventoryTicks == 0` 时触发；`OnMoveCheck` 也会因近期 flying 包而走其显式豁免分支。
3. 普通位置/朝向 C03、点击包及其原 promise 不缓存、不重排，原点击只发一次。Intave 策略不
   依赖本地 sprint 状态；目标不是 Intave 时保留原 Grim 的 STOP / 原包 / START 行为。

新增 Netty 4.0.23 生产钩子回归覆盖上述包序、无位置帧、点击 promise、开关窗口包抑制、真实
移动包透传以及非零容器点击透传。2026-11-26 本地回归 85 项通过，构建产物为
`build/openmaver-b2.2.0-2026-11-26.jar`。此离线测试没有 Intave 服务端或 Bukkit 事件，不能
验证点击是否最终改变槽位/光标、是否收到关闭窗口处理、Physics 是否仍告警或空 C03 是否影响
部署版本的计时与包序检查。

当前支持边界是玩家背包 window 0。箱子等由服务端打开的非零窗口继续走原始 MoveDisabler
路径；`KeypressBrancher` 的窗口 Physics 约束仍可能适用，尚未实现 Intave 容器方案。Inventory
Manager 的自动多包事务会命中 window 0 策略，但模块整组行为尚未回归，不能视为已适配。实服复测前
README 保留 WIP：在目标服务器确认连续移动无 Physics 告警，逐个
验证普通点击、拖动、shift-click、快速连点的 Bukkit 结果、事务确认、槽位与光标状态，并确认
空 C03 未造成额外 Timer / Physics / packet-order 检查。发生异常时关闭 Intave Bypass 即回到原
路径；不能把本地 Netty 包序测试称为实服通过。

## 2026-11-26 20:57 Physics：运行期反射映射导致抑制失效

VERIFIED：读取同一 Prism 实例的新 `intave-movedisabler-debug.log`。日志使用 epoch 毫秒，
转换为 Asia/Shanghai 后，第 2682 行为 20:57:23.553，状态已切至 INTAVE、Bypass 与
MoveDisabler 均启用；第 2822 行为 20:57:25.457，出站仍含：

```text
OUT net.minecraft.network.play.client.C16PacketClientStatus {field_149437_a=OPEN_INVENTORY_ACHIEVEMENT}
```

20:57:26 的 Physics 对应背包内连续移动，该告警区间没有 C0E。20:57:27.495 的第 3293 行
还有 window 0 的 C0D，实际字段为 `field_149556_a=0`。20:56:55 的早一组告警发生在 GRIM
目标下，与 20:57:26 的 INTAVE 运行应分开分析。

VERIFIED：`MoveDisablerFeature` 原状态读取使用 `field_149440_a`，在 Forge 的 SRG
运行期返回 null，未进入抑制 OPEN 的分支；C0D 原窗口号别名 `field_149549_a` 也错误，
返回 -1 导致抑制 window 0 关闭包的分支失效。安装的 Forge universal jar 内部映射明确为：

```text
FD: ig/a net/minecraft/network/play/client/C16PacketClientStatus/field_149437_a
FD: il/a net/minecraft/network/play/client/C0DPacketCloseWindow/field_149556_a
FD: ik/a net/minecraft/network/play/client/C0EPacketClickWindow/field_149554_a
```

生产代码已修正这两处别名。原离线替身只有 MCP `status` / `windowId` 字段，错误的第二
候选从未被执行；现在 `runtime_packets.py` 从实际 Forge 映射生成不含 MCP 字段的三种
包替身，再用真实 Netty 对同一生产代码重跑完整 MoveDisabler 用例。修改生产代码之前，
MCP 版本 95 项通过，而 SRG 版本在 `Intave suppresses long-lived player inventory open`
失败，复现了用户运行环境的差异。

CANDIDATE：未被抑制的 C16 让服务端保持 inventoryOpen=true，引用 `InventoryTracker` 的
打开处理及 `KeypressBrancher.isValidPress()` 的过滤解释了紧随其后的移动告警。日志
证明候选包序没有正确落地，并不证明修改后的候选策略已经通过部署版本所有检查。

UNRESOLVED：修复后尚无目标服务器回执，持续移动、手动点击、空 C03 的 Timer / Physics
效果以及 Bukkit 最终槽位与光标结果继续保留待测。非零窗口与 IM 整体适配边界不变。
本轮源文件修改前备份在 `build/movedisabler-srg-fix-backup/`，保持了此前未提交修改。

VERIFIED：修复后构建成功；`MoveDisablerTest[MCP]` 与 `MoveDisablerTest[SRG]` 均为
95 passed, 0 failed；InventoryMove 32、IntaveMoveDebug 6、InventoryPlanner 1684、
物品适配 16、ClickGUI 65 项通过。验证完整输出保存在备份目录的
`movedisabler-verification.log`、`inventory-verification.log`、`clickgui-verification.log`。
产物仍使用标准路径 `build/openmaver-b2.2.0-2026-11-26.jar`；重启客户端才能加载新类。
