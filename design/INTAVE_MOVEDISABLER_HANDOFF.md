# 给下一位 AI 的接力提示词

## 当前交付（2026-11-26；21:31 断线修复与惯性帧候选）

- VERIFIED：21:31 用户异常栈从 `IntaveInventoryClickSession.fail()` 的
  `promise.tryFailure(new IllegalStateException("inventory resync"))` 开始。挂上真实 Netty
  `FIRE_EXCEPTION_ON_FAILURE` 的回归也复现同一异常，见
  `build/intave-network-reset-fix-backup/regression-before.log`（exit 1）。
- 修复：主动消费的点击及过期运动包用 `trySuccess()` 完成并释放；真实下游写失败不改写。
  同步窗口判定读取实际 MCP/SRG 字段，仅 S30 window 0、S2F window 0/-1/-2 重置玩家预测，
  非零容器的同步包穿透。相关同步仍可撤销本批未提交点击，避免重放旧库存状态。
- 修复：Chest 在断线 GUI 回调遇到空 player 时重置会话，私有容器查找也加空值保护。
  Velocity 共存测试等待真实 hook 安装，并断言真实出站持有，修掉旧测试假阳性。
- CANDIDATE：新 `mc/IntaveInventoryClickSession.java` 替换已失败的空 C03 策略。
  本地预测拾取/放下/拖动成组，光标清空才提交，避免 C0D 清空/丢出光标物品。
  每批一帧真实惯性移动：最终 input 回调清 forward/strafe、实际关闭冲刺，但保留 motion、
  jump、sneak；FIFO marker 选择随后原生移动帧，END 获取真实 post-physics 坐标。
  发送 `C16 → 真实位置帧 → 原始 C0E 组`；匹配所有本批 S32 后才 `C0D → 后续包`。
  原生无位置帧只升级成实际坐标帧，C05 升级为保留原 yaw/pitch 的 C06；
  不增加 tick、不改事务号，不强制等停稳。
  本地移动继续，后续出站短暂缓存；队列各限 128、确认期限 400ms，C0F/keepalive 穿透。
- VERIFIED：当前本地回归 MCP/SRG MoveDisabler 各 76、点击 gate 各 442、InventoryMove 32、
  Debug 6、引用原始 OnMoveCheck 谓词 6、Chest 102 项通过。引用谓词测试用 API doubles，
  不是服务端 Physics/Bukkit 集成测试。库存逻辑 1684、物品适配 16、Velocity 4921、
  ClickGUI 65 项也通过；真实产物的 28 个字段引用匹配本机 MC/Forge。完整回执见上述备份目录。
- 日志已保存到 `build/intave-network-reset-fix-backup/`；debug 序号 5047 的 action=29、
  accepted=true 后 5048 发 C0D 并释放真实位置帧，5118 出现 GuiDisconnected。
  `screen=none` 也出现点击，故当前自动模块配置应重新按日志确认；不要沿用“IM 已关”的旧结论。
- UNRESOLVED：新产物的目标 Physics、Timer、Bukkit 物品/光标结果、统计点击检查，以及
  实际部署版本。S32 accepted=true 仅证明原版事务响应，不证明 Bukkit 点击检查通过。
  IM 自动规划未完整适配，箱子等非零窗口无 Intave 专用策略；保持 INTAVE (WIP)。
- 产物：`build/openmaver-b2.2.0-2026-11-26.jar`。完整本轮回滚入口：
  `python3 build/intave-coast-click-backup/rollback.py`，先用 `--check` 检查哈希，
  会恢复原 `.bak` 并删除本轮新增源码；之后需要重新构建。

以下为历史记录；以本节为当前状态。

## 历史阻塞（2026-11-26 21:10:04 移动点击实测）

- VERIFIED：用户反馈玩家背包内持续移动已正常；新的 `InventoryClickAnalysis`
  `performed inventory-click whilst walking` 是独立的 Bukkit 点击检查。
- VERIFIED：21:10:04.467–.468 完整发出了 `C16 → 空 C03 → C0E → C0D`，当时
  forward=1、sprint=true；.572 收到窗口/光标重同步与 S2E 关闭背包。完整包字段切片与
  调用链写在审计文档的“21:10:04”节。此后的 debug 文件已被新记录覆盖。
- VERIFIED：前轮“空 C03 足以触发 OnMoveCheck 豁免”的推断有误。1.8 路径要求
  `FLYING_PACKET_CLIENT` 和 `FLYING_PACKET_ACCURATE` 均近期有效；前者来自收包，
  后者需要模拟低位移判定。无位置帧的 Physics 提前返回也不保证更新已提交的推断按键。
- VERIFIED：该检查虽然提交 0 VL，仍 `event.setCancelled(true)` 并关闭背包，
  不能将 `vl=0` 或离线包序正确当作物品点击通过。
- CANDIDATE：空包未建立模拟低位移状态，点击时服务端仍读取到行走输入与较大水平位移。
  UNRESOLVED：服务端事件时刻的计数器与运动状态；当前尚未实现新的移动点击策略。
  保留持续移动需求，不用停步、等停下点击来覆盖这个负面结果。

## 最新修复（2026-11-26；20:57 告警定位）

- VERIFIED：实例 `logs/intave-movedisabler-debug.log` 第 2682 行在 20:57:23.553
  已切至 `target=INTAVE bypass=true movedisabler=true`；第 2822 行在 20:57:25.457
  仍向网络发出 C16，实际字段是 `field_149437_a=OPEN_INVENTORY_ACHIEVEMENT`。
  同期 `latest.log` 在 20:57:26 报告用户贴出的 Physics。该区间没有点击，首先是
  “开背包并持续移动”的路径失效，不应从点击延时开始调参。
- VERIFIED：生产代码误读 C16 `field_149440_a`、C0D `field_149549_a`；Forge 内置
  `deobfuscation_data-1.8.9.lzma` 确认正确字段为 `field_149437_a`、`field_149556_a`。
  现已修正 `mc/MoveDisablerFeature.java`。C0E 的 `field_149554_a` 原本正确。
- VERIFIED：旧 MCP-only 测试通过；新增从真实 Forge 映射生成的 SRG-only 包替身后，
  旧生产类在 `Intave suppresses long-lived player inventory open` 处失败。修复针对这个
  运行期差异，不是停步、限速或屏蔽告警；既有临时窗口点击包序保留。
- 测试入口仍是 `bash tools/movedisabler-test/run.sh`；新增 `runtime_packets.py` 从
  本机 Forge 映射独立生成三个包的 SRG 字段，不再只使用 MCP 别名测试反射路径。
  除包序和 promise 外补充非背包 C16、非零窗口 C0D、继承私有字段、连续点击、空中
  onGround 及关闭 Bypass 后恢复原包的检查。
- VERIFIED：`cmake --build build -j 4` 成功；MoveDisabler MCP / SRG 各 95 项、
  InventoryMove 32 项、IntaveMoveDebug 6 项、InventoryPlanner 1684 项、物品适配
  16 项、ClickGUI 65 项通过。完整回执在 `build/movedisabler-srg-fix-backup/` 的
  `*-verification.log`；新产物为 `build/openmaver-b2.2.0-2026-11-26.jar`。
- CANDIDATE：上述打开包抑制失效使服务端保持 inventoryOpen=true，进而命中引用源码
  `KeypressBrancher.isValidPress()` 的非零输入排除，是本次 Physics 的原因。
  客户端记录和引用源码吻合；部署 Intave 的具体版本仍未取得。
- UNRESOLVED：修复后目标服务器的 Physics、Timer、Bukkit 槽位/光标结果仍需新运行日志。
  保留 WIP；以下旧记录中的实服通过状态没有因本地修复而改变。

## 最新状态（2026-11-26 20:43 本地实现与回归后）

- 用户已经在目标服务器上运行正确配置：`bypass.anticheat=intave`，运行时诊断为
  `target=INTAVE bypass=true movedisabler=true`；`Inventory Manager` 关闭。
- 新诊断日志：`/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/logs/intave-movedisabler-debug.log`；
  同期服务器聊天记录在该实例 `logs/latest.log`。完整分析写在
  `design/INTAVE_INVENTORY_AUDIT.md` 的“2026-11-26 20:21 Intave 目标实测”。
- 背包打开时持续移动确实触发 Physics（`Δ0.098000`，VL 到 260），告警先于点击。
  同时两次 window 0 点击均在移动中发出，服务端事务确认 `accepted=true`。未取得 Bukkit
  InventoryClickAnalysis/事件结果的服务端日志，故点击最终物品结果还不能视为完全验证。
- 已实现 Intave 专用 window 0 候选策略：正常移动期间抑制背包 C16/C0D；window 0 点击按
  `C16 → 无坐标 C03 → 原 C0E → C0D` 发出。源码依据、范围和实服待核验项见审计文档。
- 本地构建成功；MoveDisabler Netty 回归 85 项、InventoryMove 32 项、InventoryPlanner 1684 项、
  物品适配 16 项、ClickGUI 65 项通过。该离线回归未验证 Bukkit 物品结果或 Physics，故 UI/README
  继续保留 WIP；不得称为 Intave 实服适配成功。
- 目标服务器仍需复测：持续移动时 Physics 是否消失、普通/拖动/shift-click 是否改变正确物品，
  事务与光标状态是否一致，以及空 C03 是否触发部署版本的 Timer / Physics / 包序检查。
  不得改成停步或禁用容器移动。

下一步优先完成 **目标 Intave 实服验证**；若出现拦截或状态错乱，再修正包序并重新回归。
用户明确需要背包/容器内继续移动，并希望点击物品时仍能移动。不要用禁用容器移动、
强制停步、等停下再点来替代需求。Inventory Manager 仍放到后续。

实服结果出来前准确说明未验证范围，不要将本地包序测试当作服务器物品结果或 Physics 通过。

## 当前工作区

有未提交修改，继续保留：

- `mc/OpenMaverFeatures.java`：新增 `BYPASS_GRIM` / `BYPASS_INTAVE`、
  `get/setBypassAnticheat()`、`getBypassAnticheatName()`、`isIntaveBypass()`；
  保存 `bypass.anticheat=grim|intave`，旧配置默认 Grim。
- `mc/GuiOpenMaverClickGui.java`：`Grim Bypass` 改为 `Bypass`，增加 `ANTICHEAT` 选择，
  Config 偏好页也可切换；Intave 显示 `INTAVE (WIP)` / `IN DEVELOPMENT`。
- `mc/OpenMaverHud.java`、`config/default.jsonc`、`README.md` 同步名称和配置说明。
- `tools/clickgui-test/ClickGuiInteractionTest.java` 增加选择与配置界面的回归。
- `design/INTAVE_INVENTORY_AUDIT.md` 有前轮源码审计及现场日志信息。

**本轮已实现 Intave 的 MoveDisabler 玩家背包候选包序；Inventory Manager 仍未适配。**
MoveDisabler 的策略与实服验证范围见 `design/INTAVE_INVENTORY_AUDIT.md`。选择 Intave 后只有
GuiInventory 的 window 0 点击使用临时窗口包序，箱子等非零窗口尚无 Intave 专用策略。
README 保留 WIP，因为没有本轮实服结果；不能把离线 Netty 回归写成 Intave 适配成功。
既有共享 profile 对其他模块仍然生效；不要顺带宣称所有模块都适配了 Intave。

## 必须先读的客户端代码

- `mc/MoveDisablerFeature.java`、`mc/MoveDisablerPacketHook.java`：现有方案在疾跑时把
  C0E / C0D 包裹为 `STOP_SPRINTING → 原包 → START_SPRINTING`，保留原包和 promise。
  钩子在 Velocity 出站重放之后处理窗口包，不能破坏现有管线顺序。
- `mc/InventoryMoveFeature.java`：安装共享输入钩子，容器内读取实际绑定键，覆盖移动、
  跳跃和潜行；失焦及文本输入有保护。
- `mc/ScaffoldMovementInput.java`：与搭路等模块共用的输入回调。
- `mc/OpenMaverClientHandler.java`：START 阶段先维护事务/包队列，再调用背包和箱子功能。
- `mc/InventoryManagerFeature.java` / `mc/ChestFeature.java`：了解自动点击与手动点击如何
  经过窗口包钩子。IM 当前关闭背包也能执行，整组 2～3 次点击在同一循环连续发出。
- `java/module/misc/MoveDisabler.java`：核心模块开关，不直接引用 MC 类型。

保持 MCP / SRG 运行期映射兼容，参考现有反射方式。

## Intave 源码与已经确认的矛盾

引用根目录 `references/anticheats/Intave/src/main/java/de/jpx3/intave/`，提交 `d01b2e051`。
Grim 引用为 `references/anticheats/Grim`，提交 `f319e165e`；不能假设服务端部署版本完全一致。

1. `module/tracker/player/InventoryTracker.java` 和 `user/meta/InventoryMetadata.java`：
   跟踪 C16 打开、C0D 关闭及服务端窗口状态。容器号 0 不代表背包界面打开。
2. `check/other/inventoryclickanalysis/NotOpenCheck.java:61`：支持背包成就包的协议路径中，
   未打开就点会报 `clicked in closed inventory`，加 5 VL、取消点击并关窗。
3. `user/meta/ProtocolMetadata.java:156`：`supportsInventoryAchievementPacket()` 同时依赖
   客户端协议和 `!outdatedClient()`；第 453 行比较客户端、服务端版本。
4. `check/other/inventoryclickanalysis/OnMoveCheck.java:54`：推断到移动输入且实际水平位移
   满足条件时，取消点击并关窗，提交的 VL 为 0；不能只看是否加 VL。检查另有蛛网和
   `receivedFlyingPacketIn(2)` 等豁免，应追踪真实更新语义，不能仅凭方法名猜测。
5. `check/movement/physics/branch/KeypressBrancher.java:124`：背包打开时排除非零行走输入；
   所以补一次 C16 并一直保持服务端背包打开，会影响 Physics。
6. `check/combat/heuristics/inventory/PacketInventoryHeuristic.java:76`：在显式发送空移动包的
   协议路径上，打开后还没有经过移动帧就关闭会报 `closed inventory too quickly`；
   第 107 行还检查开着背包时转头。因此不能直接将 `C16 → C0E → C0D` 同帧发送当作解法。
7. `check/other/inventoryclickanalysis/RegrDelayAnalyzer.java`：有效样本超过 30 条后在关窗时
   检查速度、方差和槽位距离与耗时的关系，SWAP / QUICK_CRAFT 不进入其采样。
8. `DelayAnalyzer.java` 的槽位切换速度分支只构造 Violation，没有 processViolation；
   标准差分支会提交，有 isPartner 和版本条件。`PacketDelayAnalyzer.java` 的相关输出被注释。

以上说明现有 Grim 包裹不够，尚未证明所有时序或策略都不可行。请追踪包线程、Bukkit
点击事件、运动元数据和 Physics 的完整调用链，再选择实现；不要只调整速度或堆随机数。

## 早期现场证据（2026-11-25；由 20:21 新实测补充）

实际客户端日志：
`/home/zty/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft/logs/latest.log`
（可能被后续启动覆盖，读取时先确认日期）。2026-11-25 的记录包含：

- 21:10:55 进入 `legacy(1.8.8)`，地址 `mc.loyisa.cn:25565`。
- 21:17:33 起连续 `InventoryClickAnalysis: clicked in closed inventory`。
- 21:17:35 有 `regular deviation`，0.49 deviation。
- 21:18:46 起另有连续 Physics 告警。

动作日志在同目录 `openmaver.log`。仓库 `logs/openmaver.log` 混有离线测试，不能当实机证据。
现场未抓取完整出站包，不能把全部 Physics 告警都归因于 MoveDisabler。

## 本轮实现及剩余验证

- 已根据 `isIntaveBypass()` 分派专用策略；保留默认 Grim 的现有行为及原有配置兼容性。
- 已实现 GuiInventory window 0 的移动和点击候选包序；箱子等其他容器的支持边界已写入审计。
- 若涉及缓存/重排，确保窗口身份、玩家/世界身份、点击事务、光标物品和 promise 一致；
  关窗、禁用、切目标反作弊、换服、死亡以及用户干预时不重放过期操作。
- 已通过真实生产 Netty 钩子的离线包序/promise 回归；该测试不覆盖服务器物品结果。
- 仍需目标 Intave 实服验证物品结果、Physics 与空 C03 的计时/包序影响；未联机复测，明确未验证。
- README 和审计已更新；WIP 状态保留到实服验证完成。

构建与已有回归：

```sh
cmake --build build -j 4
bash tools/movedisabler-test/run.sh
bash tools/inventory-test/run.sh --feature
bash tools/clickgui-test/run.sh
```

本轮验证：`cmake --build build -j 4` 成功；MoveDisabler 85 项、InventoryMove 32 项、
InventoryPlanner 1684 项、物品适配 16 项、ClickGUI 65 项通过。无 Intave 联机通过结果；
2026-11-26 20:21 的实测仍有 Physics 告警，点击事务 accepted 但服务端物品结果未验证。
产物：`build/openmaver-b2.2.0-2026-11-26.jar`。
