# Scaffold（自动搭路 / 搭桥）实现说明

ClickGUI 仅显示 `Normal` / `Simple`；原界面模式名 `Telly` 已改为 `Simple`，避免与 Telly 搭桥手法混淆。
`Simple` 仍映射到 `scaffold.mode=1`（内部 `MODE_TELLY_BRIDGE`）；本文保留 OpenZen 原分支名称说明实现。

> **2026-11-17**：本模块已**整体重写**为 `../OpenZen` 的
> `shit.zen.modules.impl.movement.Scaffold`。旧的 Myau 移植（`rotations` /
> `moveFix` / `sprint` / `tower` / `keepY` / `SafeWalk` / `MultiPlace` 那一套）
> 连同 `OpenMaverFeatures` 里的旧放置路径一起删除。
> Grim `RotationPlace` 的判定链见 `design/SCAFFOLD_ROTATIONPLACE_HANDOFF.md`
> —— 结论没变，新实现仍然遵守。

2026-11-27：Normal 完整替换为独立 Augustus 控制器，进入 `tickNormal()`，不会调用下文的
OpenZen 目标搜索、旋转或输入状态机。来源映射、默认设置与适配限制见
[SCAFFOLD_NORMAL_HANDOFF.md](SCAFFOLD_NORMAL_HANDOFF.md)。旧 Normal 的 Snap、Eagle、手动右键
门槛已删除；旧配置键不再读写。下文 OpenZen 算法只适用于 Simple / Old Telly / Keep Y，
包序、快捷栏、输入 hook 和生命周期由所有模式共享。

2026-11-25：`TELLY BRIDGE` / `OLD TELLY` 接入独立 Telly 的放置修复，
共享一步运动预测、未命中后继续转向和放置前重算实际射线交点；配置仍由 `scaffold.*` 独立保存。
共享碰撞箱反射修正为三参数 `func_180640_a`。模式切换与配置重载先释放相机、快捷栏、按键和旧目标。
实现与连续跑跳回归见 [TELLY_HANDOFF.md](TELLY_HANDOFF.md)。

同日修复：MoveFix 保留原版潜行 0.3 倍输入；事务回执在所有 tick 动作前释放，
Reach 关闭时停用延迟，传送/重生时保护 C0F→C06 顺序。
源码依据与回归结果见 [GRIM_SCAFFOLD_VL_AUDIT.md](GRIM_SCAFFOLD_VL_AUDIT.md)。

## 文件

| 文件 | 作用 |
|---|---|
| `java/module/movement/Scaffold.java` | 模块开关（`movement.scaffold`，无默认热键） |
| `mc/AugustusScaffold.java` | Normal 的支撑搜索、旋转、射线目标和点击节奏 |
| `mc/ScaffoldFeature.java` | 模式分派、其他模式的 OpenZen 状态机及共享放置传输 |
| `mc/ScaffoldMovementInput.java` | `movementInput` hook：MoveFix + 把 aim 写进本 tick 的 C03 |
| `mc/ScaffoldHud.java` | OpenZen 的方块计数卡（图标 + 进度条 + `N blocks` / `b/s`） |
| `mc/GuiOpenMaverClickGui.java` | 按当前模式显示对应参数 |
| `mc/OpenMaverFeatures.java` | 每 tick 驱动 `onTickStart` / `onTickEnd`，以及 S12 观察入口 |
| `mc/AttackReducePacketHook.java` + `AttackReduceFeature` | 入站 S12 转发给 `ScaffoldFeature.noteInboundPacket` |
| `tools/scaffold-test/` | 共享放置决策表 / 旋转数学 / 源码结构回归 |

配置写在 `config/openmaver.properties`：

```properties
scaffold.mode=0          # 0 NORMAL / 1 TELLY BRIDGE / 2 OLD TELLY / 3 KEEP Y
scaffold.sneak=true
scaffold.clutch=true
scaffold.rotationTick=3  # 1..6，单 tick 步长 360/tick（下限 180）
scaffold.itemSpoof=true
scaffold.slotMode=1      # 0 SILENT（伪装换手）/ 1 HOTBAR（真实换手，默认）
scaffold.hud=true
```

## OpenZen → OpenMaver 对照

| OpenZen | 这里 | 说明 |
|---|---|---|
| `mc.player` / `mc.level` | `thePlayer` / `theWorld`（反射） | mcstubs 只提供最小 API，其余走 `OpenMaverFeatures` 的反射层 |
| `BlockPos.containing` | `floor()` + `BlockPos(III)` | 1.8.9 没有 `containing` |
| `state.entityCanStandOn(...)` | `canStandOn()` | 碰撞箱 `maxY - y >= 0.999` 才算能站 |
| `state.entityCanStandOnFace(...)` | `isSolidSupport()` | 整块方块六个面都算可点击面 |
| `level.getBlockState` | `World.getBlockState` = `func_180495_p` | |
| `RayTraceUtil.canRayTrace` | `rayTraceFrom()` + `mopMatches()` | `rayTraceBlocks` 两参数重载 = `func_72933_a` |
| `mc.gameMode.useItemOn` | `playerController.onPlayerRightClick` = `func_178890_a` | 六个参数：player/world/stack/pos/face/hitVec |
| `mc.player.getInventory().selected` | `inventory.currentItem` = `field_70461_c` | C09 由原版 `syncCurrentPlayItem` 补，不要手发（见下） |
| `InputConstants.isKeyDown` / `setDown` | `KeyBinding.pressed` = `field_74513_e` | 每 tick 重写；关闭时按物理键盘状态恢复 |
| `ReflectionUtil.setJumpDelay(2)` | `jumpTicks` = `field_70767_bF` | 1.8.9 的起跳冷却 |
| `ClientboundSetEntityMotionPacket` | `S12PacketEntityVelocity` | 由 Netty 入站 handler 转发 |
| `RotationHandler` | `ScaffoldMovementInput` + `onTickEnd` | 见“静默转向” |
| `MovementUtil.handleStrafe` | `afterMovementInput()` 的 8 方向吸附 | 见“MoveFix” |
| `ClientBase.delayPackets` | `rotationDelay` 计数 + 重试 | 1.8.9 没有 `ClientLevel#tickNonPassenger` 注入点 |

## 其他模式主 tick（`onTickStart` → `tick`）

顺序与 OpenZen `onTick` 一致：

1. **airTicks / groundTicks**（对应它的 `onMotion` post）。
2. 读按键：`jumpHeld`、`onGround`、`moving`、`posY`。
3. **快捷栏**：扫描 0..8，取第一组可放置方块；`currentItem != slot` 时只改客户端槽位
   （`applyHeldSlot`）。C09 由原版发：`updateController()` 每 tick 和每次右键的第一步
   都是 `syncCurrentPlayItem()`，它按 `currentPlayerItem` 缓存补包。自己手发会多出
   一条相同 slot 的 C09 -> Grim `BadPacketsA`（`Sent duplicate slot id`）。
4. **targetYLevel**：
   ```java
   if (targetYLevel == -1 || targetYLevel > floor(posY) - 1
           || onGround || !moving || jumpHeld) {
       targetYLevel = floor(posY) - 1;
   }
   ```
5. **flushPending**：上一 tick 已经写进 C03 的目标，在这一步校验并放置（见“放置”）。
6. **applyRotations**：目标格搜索（见下）。
7. **clutch**：`motionY < -0.1` 时模拟两 tick 垂直运动，目标格比模拟落点高就
   `canBuildNow = false`；落地则强制 `true`。
8. **correctRotation**：`placement == null` → 相机角；`TELLY BRIDGE && canBuildNow`
   → `tellyRotation()`；否则 → `getPlayerYawRotation()`。
9. **rots 更新**：
   - `clutchHold(...)` 成立：把 aim 锁在 `rotationToBlock(placement, 1.0)`，
     `rotationDelay++`，本 tick 不放置。
   - 否则：`rotateTowards(getBlockDistance(), ...)`；
     然后 `updateJumpKey`；`updateSneak` 在 clutch 分支之前每 tick 独立执行；
     `TELLY/OLD TELLY && airTicks < 1 && moving`
     时提前返回（保留 lastRots）。
10. **规划下一 tick**：`placement != null && shouldBuild()`（脚下这一格是空气，Telly 还预测下一步脚下）时
    生成 `Pending(placement, rotsYaw, rotsPitch, hit)`，`hit` 是 C08 要发的点击点
    （见下节）。Telly 未命中时每拍更新目标角度，同目标保留重试次数。
11. **aimForThisTick**：`holdPlaceAim` → 冻结角；`pending != null` → pending 的角；
    否则 → `rots`。

## 点击点与 aim（`randomFacePoint` / `rayFacePoint`）

- `Placement` 在选定“支撑方块 + 点击面”时取一次 `randomFacePoint(x, y, z, face)`：
  法线方向精确落在该面所在的平面上，面内偏移 `±0.3` 格（离边缘还剩 0.2 格）。
- `rotationToBlock()` **指向这个点**，不再用 OpenZen 的方块中心
  `x+0.5, y+0.5, z+0.5` —— 每格都瞄准几何中心是最明显的 scaffold 特征。
- 同一个目标在命中期间复用同一个 `Placement`（`sameTarget`），点击点不重掷；
  换目标才重新取点。
- 生成 `Pending` 时先做 `rayFacePoint(eye, rotsYaw, rotsPitch, ...)`：把本 tick 的
  aim 射线投到面平面上，交点落在面内就用它，否则回退到 `Placement` 的点。
  Telly 在下一拍放置前，按上一条 C03 的角度与移动后的实际眼睛重新求交，更新 C08 的 `hitVec`。
  Telly 的面内随机点已经提供变化，不再对瞄准向量额外叠加噪声；刚跨过侧面时会缩小横向随机偏移。

## 目标格搜索（`applyRotations` / `isAbovePlaceable`）

```text
eye = 眼睛位置
if (!canBuildNow) eye += motion * 2
else if (Telly 模式) eye += motion
if (clutch && motionY < 0.01) eye.y = max(simulateFallY(2) + eyeHeight, eye.y)
feetX/feetZ = floor(eye.x/z)

if (canStandOn(feetX, targetYLevel, feetZ)) return;        // 脚下已经能站
if (isAbovePlaceable(feetX, targetYLevel, feetZ)) return;

for radius in 1..6:
    if (isAbovePlaceable(feetX, targetYLevel - radius, feetZ)) return;
    for x in 1..radius:
        for z in 0..(radius - x):
            yOff = radius - x - z
            for signX, signZ in {+,-}:
                if (isAbovePlaceable(feetX ± x, targetYLevel - yOff, feetZ ± z)) return;
```

`isAbovePlaceable(pos)`：

- `pos` 必须是空气；
- 六个邻居里找第一个实心支撑面；
- 面中心 = `pos + 0.5 + dir * 0.5`，要求 `distanceSq <= 20.25`（4.5 格）且
  `dot(toFace, dir) >= 0`（面朝着眼睛）；
- 命中后记 `placement = (支撑方块, oppositeFace(dir))`。

注意 `placement` **不会**每 tick 清空（OpenZen 也不清）：走上实心方块后它保留上一块，
这正是“走在桥上时视角仍对着桥面”的表现。

## 旋转

### Keep Y

- `rotsYaw` 使用 `rotateTowards(step, rotsYaw, correctYaw)`。
- `rotsPitch = correctPitch`，其余旋转复用下述 OpenZen 辅助函数。

### Telly Bridge（`tellyRotation`）

```text
if (!isInputActive)            -> getPlayerYawRotation()
if (placement == null)         -> (相机 yaw, 相机 pitch)
aim = rotationToHitVec(placement)            // 预测一步运动，指向固定的随机面点
yawDelta = angleDiffDouble(aim.yaw, prevAimYaw)

if (groundTicks > 0):
    if (!jumpHeld)             -> (相机 yaw, 75.5)
    groundTicks == 1:  !firstGround -> yaw = prev + clamp(yawDelta, yawDelta/2), pitch = 75.5
                       firstGround  -> aim = rotationToHitVec(...)
                       两者都 setJumpDelay(2)
    groundTicks == 2           -> (相机 yaw, 75.5)
else (空中):
    clampLimit = airTicks == 1 ? 90 : 50;  clampLimit -= rand(0.001, 0.006)
    aim.yaw = prev + clampAngle(yawDelta, clampLimit)

aim = findValidRotation(aim, firstGround)     // ±90° 目标 + smoothRotation
aim = getSnappedRotation(aim)                 // 卡住时抖动
```

`findValidRotation` 的单 tick 步长：`max(45, 180 / rotationTick)`，
`TELLY BRIDGE` 再取 `max(…, 75)`；`smoothRotation` 按角差比例分配 yaw/pitch 步长，
并用当前鼠标灵敏度（`mouseSensitivity` = `field_74341_c`）量化，偶尔叠极小抖动。

`getSnappedRotation`：如果连续两 tick 的 `|Δyaw|`（或 `|Δpitch|`）差 < 1e-4 且都
> 2°，就加 `yaw 0.095~0.19°`（正负交替）+ `pitch 0.016~0.055°`，pitch 夹在 ±89.5。

### 旋转速度

```java
getBlockDistance(): OLD_TELLY -> 180
                    否则 max(max(60, 360 / rotationTick), 180)
```

## 静默转向（OpenZen `RotationHandler` 的等价物）

- `afterMovementInput()`（vanilla `updatePlayerMoveState()` 之后、`moveFlying()` 之前）：
  1. **MoveFix**：用相机 yaw 算期望世界方向，再在 aim 坐标系里吸附到 8 个整数方向
     之一；潜行时按原版 double 乘法再转 float 的规则缩放到 0.3 倍。
     不放大输入来维持冲刺，冲刺状态由原版决定。
  2. 把 `tickAimYaw/Pitch` 用 `continuousYaw()` 写进 `rotationYaw/Pitch`
     （不取模，避免包间 360° 跳变），并记录 `prevAimYaw/Pitch`；
     如果存在 `pending`，同时写 `pending.sentYaw/sentPitch` + `sent = true`。
- `onTickEnd()`（渲染前）：`restoreCameraView()` 把 rotation / renderArm 恢复成相机，
  再把 `renderYawOffset` / `rotationYawHead` 设成 `prevAimYaw`（F5 身体朝向服务端 aim）。
- `SLOT = SILENT` 时 `itemSpoof` 也在这里把客户端槽位切回 `oldSlot`（不发 C09）；
  `HOTBAR` 模式下客户端槽位保持不动，服务端与客户端都停在方块槽上。

## 放置（`pending → flush`）

OpenZen 的 `onPreMotion` 在 `handleKeybinds` 里，也就是本 tick 的 C03 之后；1.8.9 的
等价点是**下一 tick 开头**，所以放置固定晚一 tick：

```text
tick N   : 算 aim -> pending = Pending(placement, rots) ；aim 随 C03 发出
           afterMovementInput 写 pending.sentYaw/sentPitch, sent = true
tick N+1 : flushPending()
             !sent / 槽位没同步        -> DROP
             屏幕/死亡/无方块/脚下不空 -> DROP
             rayTrace(sentYaw, sentPitch) 命中支撑面 -> PLACE
             没命中 -> RETRY（retries++，>= 2 之后 DROP）
           PLACE 之后 holdPlaceAim = true，本 tick 的 aim 冻结成 sentYaw/sentPitch
```

- `pendingDecision(sent, rayHit, retries)` 是决策表本体，测试直接调它。
- `place()` 只由 `flushPending()` 调用；内部 `OpenMaverFeatures.claimPlacement()`
  保证**一 tick 一条 C08**（与 AttackReduce 的 farm 共用名额，避免 `MultiPlace`）。
- 命中判定用 `mopMatches()`：`typeOfHit == BLOCK`、`blockPos` 等于支撑方块、
  `sideHit` 等于要点击的面。
- `shouldBuild()`：脚下方块（`floor(y - 0.5)`）必须是空气 —— OpenZen 的
  `BlockUtil.isPlaceable` + `isEmptyBlock(below)`。
- `targetBlockReachableByFace()`：向上搭时，空中平走且没按空格
  不点顶面；Normal 独立校验支撑和相邻空气格（OpenZen `doSnap` 里的过滤）。

## 其他模式 Clutch / 击退

- `clutchHold(clutch, canBuildNow, velocityDelay, rotationDelay)`：
  `clutch && (!canBuildNow || velocityDelay > 0) && rotationDelay <= 8`。
- `velocityDelay`：入站 `S12PacketEntityVelocity` 属于自己、水平速度 ≥ 1.5 时设 60，
  每 tick 递减；落地且 ≤ 30 时清零（OpenZen 的 `onPacket`）。
- OpenZen 在这条分支里往 `ClientLevel#tickNonPassenger` 塞 runnable 拆 tick、额外发
  `C05PacketPlayerLook`；1.8.9 没有对应注入点，这里只保留“按住 aim + 计数 + 到期重试”，
  不额外发视角包（每 tick 本来就只有一条 C03）。

## 输入接管

- `sneak`：`sneakTimer++`；第 18 tick 停冲刺（`keyBindSprint` + `setSprinting(false)`）
  并按 `keyBindSneak`，第 21 tick 松开并归零。
- 其他模式的周期潜行合并物理 Shift；关闭设置、GUI、切世界、切模式和 reset 时归还按键。
  Normal 默认不接管潜行和跳跃，独立 SafeWalk 可照常运行。
- `TELLY / OLD TELLY / KEEP Y`：`keyBindJump = moving || jumpHeld`。
- 关闭模块：按 `KeyBinding.getKeyCode()` 的**物理**状态恢复 jump/sneak/sprint。
- `Normal` 的 MoveFix 会把后退/侧移映射为 aim 坐标系的前进；原版在输入 hook
  之后检查疾跑，因此只暂停 KeepSprint 不足以阻止意外疾跑。hook 按修正前的
  `forward < 0.8` 取消疾跑、清空 `sprintToggleTimer / field_71156_d`，并临时屏蔽
  疾跑键；ClientTick END / 下一 START / reset / disable 按物理状态归还。
  与 KeepSprint 开关无关，正常前进仍由原版决定是否疾跑，Simple/Telly 保持原有行为。

## 快捷栏 / 物品

- 切槽：只改 `inventory.currentItem`（客户端渲染立刻变）。C09 **交给原版**：
  1.8.9 的 `PlayerControllerMP.updateController()` 每 tick 跑一次
  `syncCurrentPlayItem()`，右键 / 挖掘路径的第一步也是它；它拿
  `currentPlayerItem` 缓存和自己 `inventory.currentItem` 比较，只在真的变了的时候
  发 C09。绕开缓存手发一条，原版紧接着会再补一条相同 slot 的 —— 服务端连收两条
  相同的 `C09PacketHeldItemChange`，正是 Grim `BadPacketsA`
  （"Sent duplicate slot id"）判的东西（实测日志 `BadPacketsA: slot=1` / `slot=0`）。
- `slotMode`（`SLOT`）：
  - `HOTBAR`（默认）：停在方块槽上，`placedStack()` 取 `getHeldItem()`，所以 C08 的
    物品 = 服务端刚被 C09 切过去的那个槽里的方块。`slotSwitchTick` 记录刚换手的
    tick，`flushPending` 在同 tick 里只重试不放置 —— 我们注入的 C08 在 tick 开头，
    原版那条 C09 在 tick 中段的 `updateController()`，隔一 tick 才能保证 C09 先到。方块用完时 `updateHotbar` 会 `restoreSlot()` 正常换回开启时的槽位。
    手里不是方块时 `placedStack()` 返回 `null`，本 tick 直接放弃，不伪造 C08。
  - `SILENT`：旧行为。tick 末尾 `itemSpoof` 把 `currentItem` 切回 `oldSlot` 但
    **不发** C09；手部渲染看到旧物品，服务端仍然认为手持方块。下一 tick 开头再切回
    方块槽时原版缓存已经是方块槽，所以不会再补 C09。关闭模块 / `reset()` 时切回原槽，
    由原版补包。
    这一模式下客户端的选中槽和服务端不一致，本身就是一个可判定特征。

## 与 OpenZen 的差异（全部记录在这里）

1. **C08 时序**：晚一 tick 放置 + `holdPlaceAim`（原因见上，也见
   `SCAFFOLD_ROTATIONPLACE_HANDOFF.md`）。
2. **空闲 aim**：`getPlayerYawRotation()` 在 `placement == null` 时返回相机角，
   而不是 OpenZen 的 `(0,0)`；同理 `tick()` 里没有目标时 `correctRotation` 也是相机角。
3. **`delayPackets`**：只保留语义（hold + 计数 + 重试），不拆 tick、不发额外 C05。
4. **`renderItemSpoof` 的实现方式**：OpenZen 改的是 `ItemInHandRenderer` 的渲染事件。
   这里默认不做伪装，直接用 `SLOT = HOTBAR` 真实换手（C09 + `getHeldItem()`），
   服务端视角与客户端一致；只有显式选 `SILENT` 时才用客户端槽位翻转复刻旧观感。
5. **HUD 位置**：OpenZen 是居中 HUD 元素；这里挂在准星右侧（与 Target HUD 同一套
   卡片语言），尺寸 196×32。
6. **`rotationDelay`**：OpenZen 里它同时驱动拆 tick；这里只作为 clutch/击退的抑制窗口。
## 反射 / SRG 名称

| 用途 | MCP | SRG |
|---|---|---|
| 玩家位置 | `posX/posY/posZ` | `field_70165_t` / `field_70163_u` / `field_70161_v` |
| 玩家速度 | `motionX/motionY/motionZ` | `field_70159_w` / `field_70181_x` / `field_70179_y` |
| 视角 | `rotationYaw/rotationPitch` | `field_70177_z` / `field_70125_A` |
| 上一帧视角 | `prevRotationYaw/Pitch` | `field_70126_B` / `field_70127_C` |
| 落地 | `onGround` | `field_70122_E` |
| 快捷栏 | `inventory.currentItem` | `field_71071_by` / `field_70461_c` |
| 背包数组 | `inventory.mainInventory` | `field_70462_a` |
| 堆叠数 | `stackSize` | `field_77994_a` |
| 手持物品 | `getHeldItem` | `func_70694_bm` |
| 眼睛高度 | `getEyeHeight` | `func_70047_e` |
| 起跳冷却 | `jumpTicks` | `field_70767_bF` |
| 按键按下 | `KeyBinding.pressed` | `field_74513_e` |
| 鼠标灵敏度 | `GameSettings.mouseSensitivity` | `field_74341_c` |
| 方块状态 | `World.getBlockState` | `func_180495_p` |
| 是否空气 | `World.isAirBlock` | `func_175623_d` |
| 射线 | `World.rayTraceBlocks(Vec3,Vec3)` | `func_72933_a` |
| 碰撞箱列表 | `World.getCollidingBoundingBoxes` | `func_72945_a` |
| 方块碰撞箱 | `Block.getCollisionBoundingBox` | `func_180640_a` |
| 右键 | `PlayerControllerMP.onPlayerRightClick` | `func_178890_a` |
| 切换槽包 | `C09PacketHeldItemChange` | （类名不变） |
| 击退包 | `S12PacketEntityVelocity` | （类名不变） |

## 调试 / HUD / 渲染

- `debug.enabled=true` 时：`OpenMaverDebug.place(...)` 记录每次 C08，
  `trace("scaffold", ...)` 记录 enable/disable 与丢弃原因。
- HUD 显示条件：模块开启 + `scaffold.hud` + 有方块；进度条 `count / 64`。
- 世界渲染：`RenderWorldLastEvent` 里把目标空气格画成 `#4A90E2` 半透明方块
  （`OpenMaverClientHandler.onRenderWorldLast` → `ScaffoldFeature.onRenderWorld`）。

## 回归测试

```bash
cmake --build build -j        # 先构建一次（需要 mc-classes / mcstubs-classes）
tools/scaffold-test/run.sh    # 共享传输和其他模式数学
tools/telly-test/run.sh       # 含 ScaffoldNormalTest / ScaffoldNormalBridgeTest
```

覆盖：

- `pendingDecision` 决策表（未发过 aim 不放置、命中才放置、重试有上限）；
- `rotateTowards` / `clampAngle` / `angleDiffDouble` / `wrapDegrees` / `floor`；
- `oppositeFace` 六面双向一致；
- `getBlockDistance` 的速度档位（Old Telly 180、rotationTick 1..6）；
- `clutchHold` 窗口（掉落、击退、预算上限、关闭）；
- Normal 八方向连续桥面、double 远坐标、默认无潜行/跳跃、无需右键、真实射线交点；
- Normal 设置范围/持久化、CPS 与强制放置、挥手、GUI/模式切换/无方块时的状态释放；
- 源码结构：唯一 `onPlayerRightClick`、各控制器独立生成 `Pending`、aim 只在 movementInput
  hook 写、C09 同步、S12 观察、旧 Myau 设置与旧放置路径不得残留；
- HUD / ClickGUI 的模式参数可见性和绑定。

## 已知边界

- 没有 `mixin`，所以 `Render Item Spoof` 是“槽位翻转”而不是渲染事件替换；
  某些整合包若也在 `movementInput` 上做文章，hook 会主动放弃（有 `noticeOnce` 提示）。
- `S12` 观察依赖 Netty handler 已安装（与 AttackReduce 共用，`AttackReducePacketHook` 无条件转发）。
- 1.8.9 服务端对 C08 的校验只看距离/槽位，不看 rotation；`RotationPlace` 是 Grim 的
  客户端行为检查，所以 `holdPlaceAim` 只在 Grim 上有意义，在 vanilla 服务器上无副作用。
