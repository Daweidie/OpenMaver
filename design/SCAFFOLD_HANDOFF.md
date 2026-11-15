# Scaffold（自动搭路 / 神桥）实现说明

> 由原 `GodBridge` 重命名，行为按 `OpenMyau-Fix` 的 `Scaffold` 全量移植。
> OpenMaver 没有 Myau 的 Mixin / EventManager，因此用 ClientTickEvent.START +
> 反射实现；少数事件型行为为近似实现。

## 文件

- `java/module/movement/Scaffold.java`：模块开关（`movement.scaffold`）。
- `mc/ScaffoldFeature.java`：完整行为移植。
- `mc/OpenMaverFeatures.onTickStart()`：每 tick 调用 `ScaffoldFeature.onTickStart()`。
- `mc/ScaffoldHud.java`：BlockCounter HUD —— 与 Target HUD 同风格的毛玻璃卡片，
  左侧显示当前方块图标，右侧显示剩余数量；每次成功放置触发图标抖动。
- `mc/OpenMaverHud.onRenderOverlay()`：在 Target HUD 之后渲染 `ScaffoldHud`，并把它
  加入毛玻璃背景快照的抓取条件。

## 执行阶段

Myau 的 UpdateEvent PRE / StrafeEvent / MoveInputEvent / LivingUpdateEvent 在
OpenMaver 中拆成两个阶段：

**ClientTickEvent.START**（vanilla 发送 C03 之前）：

0. 保存真实相机 `cameraYaw/cameraPitch`，清空上一 tick 的写入标记。
1. 更新 stage / startY / currentFlatTick / rotationTick / snapCooldown。
2. 自动切换快捷栏方块并计算 `blockCount`。
3. **先放置上一 tick 的 pending 目标**（见下），再用 `planPlacement()` 规划本 tick
   的目标（脚下空气格 / 按实际速度预测的下一 tick 空气格 / 静止贴边时的相机前方格）。
4. 对目标空气格的每个相邻支撑面做真实 `world.rayTraceBlocks()` 校验，取第一个
   可见的面；玩家已踏空时立即发 C08，否则写入 `pendingPlace` 并提前一 tick
   把 aim 写进 C03。
5. KeepY / targetFacing 特殊放置也走同一个 pending 队列。
6. Tower 状态机（VANILLA / EXTRA / HYPIXEL）。
7. SafeWalk：复刻原版潜行防边缘的收缩步长（不改 motionY、不清空全部速度）。

**玩家 updatePlayerMoveState() 之后**（`ScaffoldMovementInput` 回调）：

8. 按 Myau 的 `fixStrafe` 把 `movementInput` 吸附到相对 aim 的 8 个整数方向
   （真正的 MoveFix），再把输入向量等比放大到 `forward >= 0.8`，避免原版随后
   关闭冲刺。
9. 应用 `GROUND/AIR/SPEED MOTION` 与自动跳跃（在 START 阶段改会被原版覆盖）。
10. 把 aim 写进 `rotationYaw/rotationPitch`；本 tick 的 C03 会携带它。

**ClientTickEvent.END**（渲染之前）：

11. `rotationYaw/Pitch` 与 `prevRotation*` 恢复成相机角度，第一人称视角不变。
12. `renderYawOffset/rotationYawHead` 保留服务端最后收到的 aim，F5 身体因此
    显示为“在搭路”。

## 目标选择与放置节奏

旧 `getBlockData()` 只按“离目标格最近的实心方块”选支撑，存在两个问题：

1. 选出的支撑面可能被支撑方块自己遮住。典型场景：平桥上想放前方一格时，
   最近的支撑是脚下这块方块，点击它的侧面会被自己的顶面挡住 → `rayTrace`
   永远命不中，表现为“走到边缘直接掉下去”。
2. 世界在动，目标面和玩家位置每 tick 都在变；固定取一个面再延迟一 tick 放，
   很容易在 flush 时已经打不到。

现在的实现（`planPlacement()`）：

- 目标格只允许“脚下空气格”、按实际速度/按键预测的下一 tick 空气格，以及
  静止贴边时相机前方一格；预测格确实可替换时才启用。
- 支撑方块必须是目标空气格的六个邻居之一（跳过目标上方的天花板），点击面
  固定为支撑方块朝向目标的那一面。
- 每个候选面的中心 + 四个角附近都做真实 `rayTrace`，只要有一个角度能命中
  支撑面就采用；不再依赖“离目标中心最近的方块”这一单一候选。
- 玩家还站在实心方块上时，用**预测位置**（`pos + dir * step`）计算 aim 并写进
  本 tick 的 C03；下一 tick 开头玩家正好到达该格，再用同一角度 flush。
  这样既保留“服务端 sees 上一 tick 的 rotation”的顺序，又不会慢一 tick。
- 玩家脚下已经是空气时 `immediate=true`：非 bypass 模式下直接在本 tick 放置；
  **开启 bypass 时一律改为排队**，因为服务端此时还没有收到这一 tick 的 aim，
  直接发 C08 会触发 `PositionPlace`。
- `flushPendingPlace()` 的角度复核失败时：
  - 非 bypass：从当前眼睛重新计算角度，仍失败就用保存的 hitVec 兜底；
  - bypass（`grimStrict()`）：不换角度立即发 C08，而是为同一目标格重新计算一个
    会先写进 C03 的中心 aim 并重排一次；玩家已经踏空时才允许旧 hitVec 紧急兜底。
    直接换角度发 C08 会让服务端 ray trace 对不上，触发
    `RotationPlace`/`PositionPlace`。

### 边缘转头不放置 / 手感慢（已修复）

症状：贴着方块边缘快速转头时，预测目标算出来了，但下一 tick flush 总是
`hit=false`，bypass 模式下被丢弃，表现成“转头就不放方块”；同时 aim 会多保留
一拍，转头后服务端视角滞后。

原因与修复：

1. **预测用按键方向，而不是实际速度。** 快速转头时输入方向已经变了，但这一
   tick 的位移仍主要由惯性决定；按键方向会把下一格算到错误的一侧。现在优先用
   `motionX/motionZ` 预测落点，惯性很小时才退回按键方向；惯性方向和按键方向
   不同时，两者都作为候选目标。
2. **瞄准点贴着面边缘。** 预测位置和下一 tick 的真实眼睛位置有偏差，擦边角度
   很容易在 flush 时先撞到支撑方块顶面。`aimAtFace()` 现在优先瞄准面的正中心，
   中心命中不了才试当前 aim 和四角采样，角度容错更大。
3. **flush 失败直接丢弃。** bypass 模式下失败时会用预测位置给同一目标格重新
   算一个中心 aim 并重新排队，让它在下一 tick 先随 C03 发给服务端再放置；
   最多慢 1 tick，不会连续丢弃。玩家已经踏空时允许用旧 hitVec 紧急兜底。
4. **静止时按相机方向补块。** 没有 WASD 输入时，如果玩家距离相机方向的方块
   边缘 ≤0.55 格且前方是空气，就把相机前方一格作为目标；在边缘原地转头也能
   继续铺路，而不会因为“没有移动预测”完全不产生目标。
5. **aim 只保留 1 tick。** `aimHoldTicks` 从 2 改为 1，flush 用完就让真实相机
   重新接管，转头后服务端视角不再滞后两拍。

### Grim `Simulation` 大量 flag（Log: `.002 ~ .03`）

日志特征：开启 Scaffold 后每个 tick 都有 `Simulation`，偏移只有千分之几，
偶尔伴随 `RotationPlace` / `PositionPlace`，但 SafeWalk 已被 bypass 关闭。

根因是 **MoveFix 连续旋转 movementInput**：

- 连续旋转后，客户端移动方向相对服务端收到的 aim 是一个任意角度。
- Grim 的 `Simulation` 对 1.8 没有输入包，只能暴力尝试 8 个整数输入方向
  （`-1/0/1` 的 forward/strafe 组合，每 45° 一个）。
- 客户端实际方向落在两个 45° 方向之间时，服务端只能取最近的整数方向，
  位移差就是 `速度 × 角度误差`，正好是日志里的 `.002 ~ .03`。
- 吸附到整数方向后，客户端移动可以直接被服务端精确复现。

同时 `flushPendingPlace()` 增加了 `pending.sent` 校验：只有 aim 真的写进了
上一 tick 的 C03 才允许发 C08，避免服务端没收到角度就收到放置包。
严格模式下不再用旧 `hitVec` 兜底（那是 `PositionPlace` 的来源）。

### 为什么常规放置仍保留 1 tick 的 aim 提前量

1.8 客户端在 tick 内先发动作包（C08），后发飞行包（C03）。Grim 处理 C08 时
`player.yaw/pitch` 还是**上一 tick** C03 的值（RotationPlace 源码注释：
`xRot and yRot are a tick behind`）。因此：

- tick N：算出预测格 T_N 与 aim_N，写进 C03 发给服务端；
- tick N+1：服务端当前视角正好是 aim_N，再用 aim_N 对 T_N 做 ray trace 并
  发 C08；校验通过，而且没有额外 C05。

旧实现的问题不是“延迟 1 tick”本身，而是 tick N 的目标格用的是**当前**位置，
算出的侧面被遮挡，导致根本没有 aim_N 可发。现在预测位置让这个窗口始终有解。

“脚下已经踏空”属于紧急情况：非 bypass 模式下直接下发 C08 保证不掉下去；
开启 bypass 时仍排队一 tick，优先保证不触发 `PositionPlace`。

## Grim strict 模式（`bypass.enabled=true`）

`grimStrict()` 打开后会禁用所有“服务端无法复现”的动作：

- `SafeWalk` 直接返回，不再修改 `motionX/Z`。这是 `Simulation` 反复出现
  `vl` 累积的主要原因（服务器按原版摩擦预测，客户端却在边缘手动刹车）。
- 放置不做即时 C08，也不在 flush 失败时换角度/换 hitVec；C08 永远使用上一
  tick 已经随 C03 发给服务端的 aim 和当前眼睛位置重新 rayTrace。
- 不再因为 `moveFix` 之后的 `moveForward < 0.8` 主动 `setSprinting(false)`，
  冲刺完全交给原版判定，避免 C0B start/stop 抖动。

代价是“第一次无预测踏空”会晚一 tick 放，但正常行走/跑动/转向都在预测覆盖内。

## 旋转模式

- `NONE`：不旋转。
- `DEFAULT`：朝向对角线/桥面方向，pitch 85°。
- `BACKWARDS`：朝身后放置。
- `SIDEWAYS`：侧向放置。
- `OFFSET`：yaw 取 45° 的倍数，pitch 79.3°。
- `SNAP`：踏空瞬间快速转向，放置后冷却。
- `HYPIXEL`：Myau 的 Telly 旋转容差版本（起步/常规速度可调）。

## Tower / KeepY

- `TOWER NONE`：不自动起塔。
- `TOWER VANILLA`：原版跳跃节奏，`motionY=0.42` 后补齐到整格。
- `TOWER EXTRA`：带随机微调与边缘前移的 EXTRA 起塔。
- `TOWER TELLY`：由 `keepY/rotation` 的 Telly 逻辑驱动。
- `TOWER HYPIXEL`：静止起塔 + 下落钳制。
- `KEEP Y VANILLA/EXTRA/TELLY/EXTRATELLY`：在跳跃或起塔时保持桥面 Y。
- `KEEP Y ON PRESS`：只在按住右键时保持 Y。

## 输入接管 / 近似说明

- Myau 通过 Mixin 取消 `clickMouse` / `rightClickMouse` / `sendClickBlockToController`。
  OpenMaver 没有 Mixin，改为每 tick 设置：
  - `leftClickCounter=2`：阻止原版左键攻击与挖方块；
  - `rightClickDelayTimer=4`：阻止原版右键，但本类直接调用
    `onPlayerRightClick`，不受该冷却影响。
- `SafeWalk`：Myau 注入 `Entity.moveEntity` 的 flag；这里复刻原版潜行防边缘的
  收缩逻辑（`while (会踏空) step = ±0.05`），只把会踏空的那部分位移收掉。
  旧版在离边缘 0.45 格时就清空 `motionX/Z`，速度会掉到 1/3 左右，这是
  “开 Scaffold 走不动”的主要原因。注意它仍会改变服务端预测的 motion，
  对 Grim 对测时应先关闭（否则可能保留 `Simulation` / `GroundSpoof`）。
- `MoveFix`：通过 `mc/ScaffoldMovementInput.java` 继承 `MovementInputFromOptions`，
  在原版 `updatePlayerMoveState()` 之后回调，按 Myau 的 `fixStrafe` **吸附**输入：
  先取相机方向的移动 yaw，把它相对 `aimYaw` 换算成最近的 45° 整数方向，
  再把 `moveForward/moveStrafe` 设为 `-1/0/1` 的组合。
  `moveFix=0` 时不做旋转（WASD 跟随 aim，仅用于对比）。
  - 不能连续旋转输入向量。连续旋转会得到任意角度的移动向量，而 Grim 的
    `Simulation` 只用 8 个整数输入方向做预测，几度的偏差就会变成
    `.002 ~ .03` 的位移误差并持续累积（日志中大量 `failed Simulation`）。
  - 吸附后 `moveForward` 可能是斜向的 `0.707`，原版会据此关闭冲刺。这里会把
    输入向量等比放大到 `moveForward >= 0.8`；`moveFlying()` 会归一化到移动速度，
    世界方向不变，但原版冲刺判定能通过。
  - `GROUND/AIR/SPEED MOTION` 也移到了这个回调里。放在 START 阶段会被原版
    `updatePlayerMoveState()` 覆盖，之前实际是不生效的。
- 自动切方块：扫描快捷栏，选择可放置方块并锁定槽位；关闭模块时恢复原槽位。
- `ItemSpoof` 仅保留设置项（Myau Scaffold 源码中同样未实际使用）。
- `MultiPlace`：新旋转模式下固定为每 tick 一次；同一 tick 多个 C08 仍会触发
  Grim `MultiPlace` / `Post`，所以设置项保留但不再产生额外放置。

## 反射 / SRG 名称

- `movementInput / field_71158_b`
- `moveForward / field_78900_b`、`moveStrafe / field_78901_c`、`jump / field_78903_e`、
  `sneak / field_78899_d`
- `updatePlayerMoveState / func_78898_a`（`ScaffoldMovementInput` 的 override；
  `tools/McpReobf.java` 必须同时改声明的 override 和 `super` 调用）
- `motionX / field_70159_w`、`motionY / field_70181_x`、`motionZ / field_70179_y`
- `rotationYaw / field_70177_z`、`rotationPitch / field_70125_A`
- `prevRotationYaw / field_70126_B`、`prevRotationPitch / field_70127_C`
- `renderYawOffset / field_70761_aq`、`rotationYawHead / field_70759_as`
- `renderArmYaw / field_71154_f`、`prevRenderArmYaw / field_71163_h`、
  `renderArmPitch / field_71155_g`、`prevRenderArmPitch / field_71164_i`
- `onGround / field_70122_E`、`isCollidedHorizontally / field_70123_F`
- `hurtTime / field_70737_aN`
- `inventory.currentItem / field_70461_c`
- `getBlockState / func_180495_p`、`getBlock / func_177230_c`
- `getMaterial / func_149688_o`
- `Material.isReplaceable / func_76222_j`（`func_76220_a` 是 `Material.isSolid`，两者用错会导致空气不可替换、实心方块可替换的完全反向逻辑）
- `rayTraceBlocks`：重载的 SRG 名不同！
  - `(Vec3,Vec3)` → `func_72933_a`
  - `(Vec3,Vec3,boolean)` → `func_72901_a`
  - `(Vec3,Vec3,boolean,boolean,boolean)` → `func_147447_a`
- `getCollidingBoundingBoxes / func_72945_a`
- `onPlayerRightClick / func_178890_a`（1.7.10 的 `func_78760_a` 在 1.8.9 不存在）
- `getBlockReachDistance / func_78757_d`
- `getCurrentGameType / func_178889_l`
- `swingItem / func_71038_i`
- `C0APacketAnimation`
- `Potion.moveSpeed / field_76424_c`、`Potion.jump / field_76430_j`（失败时静默跳过）
- `KeyBinding.pressed / field_74513_e`

## 性能（“一开就卡死”的根因与修复）

- 症状：开启 Scaffold 后客户端 tick 从 20 TPS 掉到 4~10 TPS，看起来像游戏卡死；
  `logs/openmaver-debug.log` 中每 100 tick 的间隔会从 5 s 涨到 12~25 s。
- 根因：`getBlockData()` 每 tick 扫描 9×5×9 区域，每个位置还要查 6 个相邻面，
  再叠加 `findBestHit()` 的 16×16 射线采点与 MultiPlace 的重复扫描。旧版
  `OpenMaverFeatures.read/call` 对**每一次**字段/方法访问都执行
  `getDeclaredField(s)`：名字不匹配时还会创建带完整栈的 `NoSuchFieldException`，
  方法则每次复制整个类的 `Method[]`。单 tick 上万次反射 + 大量临时对象，
  在弱 CPU 上直接把 tick 拖垮。
- 修复：
  - `OpenMaverFeatures` 增加 `FIELD_CACHE` / `METHOD_CACHE`，按 目标类 + 名称
    （方法再加参数运行时类型）缓存 `Field` / `Method[]`，未命中同样缓存。
  - `ScaffoldFeature` 缓存 `Block` 子类判定（`isSolid` / `isInteractableBlock`），
    并把 `findBestHit()` 中循环不变的 BlockPos 坐标与 `baseYaw` 提到循环外。
- 结果：热路径从“每 tick 上万次类扫描 + 异常构造”降为“一次查表 + 一次 invoke”。

## 踩坑：EnumFacing.toString() 是小写

- 1.8.9 的 `EnumFacing.toString()` 返回 `"north" / "east" / "south" / "west" / "up" / "down"`，
  不是枚举常量名。`findBestHit()` 原来用 `String.valueOf(facing)` 和 `"NORTH"` 比较，
  6 个分支全部失配，采样退化成 16×16×16 的整块体积。
- 神桥的视线几乎贴着支撑方块侧面，体积采样会让射线先命中**顶面**，
  `mopMatches()` 的面校验因此失败，表现为“走到边缘直接掉下去、一个方块都不放”。
- 现在统一用 `facingIndex(facing)` 的枚举序号分派；`facingIndex` 的字符串兜底、
  `MovingObjectType` 与 `GameType` 的名称比较也改成忽略大小写。

## 踩坑：重载方法的 SRG 名不共用

- 同一个 Java 方法名的不同重载在 SRG 里是**不同的** `func_xxx_x`，不能用其中一个代表全部。
  之前 `rayTraceBlocks(Vec3,Vec3)` 误用了 5 参数重载的 `func_147447_a`，
  `call()` 按参数个数匹配不到任何方法，`rayTrace()` 每 tick 恒返回 null
  → 调试日志里一直是 `hit=false`，所以走到边缘必然掉下去。
- `onPlayerRightClick` 在 1.8.9 是 `func_178890_a`（1.7.10 才是 `func_78760_a`）；
  `getCurrentGameType` 是 `func_178889_l`；`Potion.moveSpeed` 是 `field_76424_c`。
- **`Material.isSolid()` 是 `func_76220_a`，`Material.isReplaceable()` 是 `func_76222_j`。**
  之前把 `isSolid` 当成 `isReplaceable`，逻辑完全反向：空气被判成“不可替换”
  （`targetPos` 直接 null），脚下实心方块被判成“可替换”（射线先撞到脚下的方块，
  面校验失败）。这是 `hit=false` 一直修不好的直接原因。
- 复测时看 `logs/openmaver-debug.log` 的 `[SCAFFOLD]` 行：`hit=true` 才说明射线
  命中了支撑面，随后应出现 `[C08] place ...`。

## 已知限制

- 没有 Mixin，但通过 `ScaffoldMovementInput` 在 `updatePlayerMoveState()` 之后
  写 rotation，已经能做到“C03 带 aim、相机不动”。常规放置靠上一 tick 的预测
  aim 提前量；玩家已经踏空时会立即放置，不延迟。
- 立即放置的 C08 仍然先于本 tick 的 C03，可能被 Grim 记成 post-flying；
  只在“脚下已经空气”的紧急格触发，日志里会出现 `$ immediate`。
- `SafeWalk` / Tower 仍直接改 `motionX/Y/Z`，会保留 `Simulation` /
  `GroundSpoof` / `NoFall` 检测面；对 Grim 对测时建议先关闭。
- `MultiPlace` 在新旋转模式下不产生额外放置（每 tick 最多 1 个 C08）。
- `itemSpoof` 未实现实际发包逻辑（Myau 原版 Scaffold 也未使用）。
- 自动切方块会锁定快捷栏；关闭模块后恢复原槽位。
- 与 KillAura/Aim Assist 同时开启时，后者的 rotation 可能与 Scaffold 的
  tick 内换视角互相覆盖；对测时一次只开一个旋转类模块。
