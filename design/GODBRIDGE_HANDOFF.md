# GodBridge（神桥 / 蹲起搭路）实现说明

> 按“手动搭桥教材”重写的独立模块。教材里的三种手法 = 这里的三个 MODE：
>
> | 教材 | 模块 | 目标速度 |
> |---|---|---|
> | Shift + S，一直按住 Shift | `SPEED BRIDGE` | 1.67 方块/秒（12 tick/方块） |
> | Shift + S + D，视角与桥成 45° | `DIAGONAL 45` | 2.00 方块/秒（10 tick/方块） |
> | 蹲起搭路：放块 → 松 Shift → 贴边按回 | `SNEAK TOGGLE` | ≈3 方块/秒（7 tick/方块） |

## 文件

| 文件 | 作用 |
|---|---|
| `java/module/movement/GodBridge.java` | 模块开关（配置路径 `movement.godbridge`，默认键 `N`） |
| `mc/GodBridgeFeature.java` | 全部行为：方向换算 / 贴边判定 / Shift 节拍 / 迟一 tick 的 C08 |
| `mc/GodBridgeHud.java` | 实测 `b/s` 卡片（阶段、速度、方块数） |
| `mc/ScaffoldMovementInput.java` | movementInput hook，同时喂 `ScaffoldFeature` 与 `GodBridgeFeature` |
| `mc/ScaffoldFeature.java` | 旧的 OpenZen 移植；神桥开启时它主动让位 |
| `mc/OpenMaverFeatures.java` | 每 tick 调 `onTickStart` / `onTickEnd`，以及 `godbridge.*` 读写 |
| `mc/GuiOpenMaverClickGui.java` | MODE / SNEAK WINDOW / EDGE MARGIN / SAFETY / ITEM SPOOF / SPEED HUD |
| `tools/godbridge-test/` | 73 项回归（速度 / 方向 / 循环 / 闸门 / aim / 结构 / 接线） |

配置写在 `config/openmaver.properties`：

```properties
godbridge.mode=2          # 0 SPEED BRIDGE / 1 DIAGONAL 45 / 2 SNEAK TOGGLE
godbridge.sneakWindow=3   # 松 Shift 的 tick 预算（1..4）
godbridge.edgeMargin=0.3  # 贴边阈值：余量 <= 该值才按右键
godbridge.safety=true     # 踩空时往下补一块
godbridge.itemSpoof=true  # 手部渲染保留原物品
godbridge.hud=true        # 速度卡
```

## 教材 → 代码对照

### 1. 普通搭路 1.67 方块/秒

教材：“按住 shift 的同时按住 S 键和鼠标右键，只有 1.67 方块每秒。”

- 玩家按 S，行进方向 = 相机 + 180°（`travelYaw(cameraYaw, -1, 0)` 返回 180）。
- `SPEED BRIDGE` 模式全程按住 Shift（原版潜行锁边），所以移动速度被压到
  潜行档；12 tick 一方块即 1.67 方块/秒，正好是 `ticksPerBlock(SPEED) = 12`。
- 模块只做两件事：`aim` 指向目标格支撑面的上沿、`shouldPlace` 成立时发一次 C08。

### 2. 45° 斜桥 2 方块/秒

教材：“同时按 S 和 D 键搭路，保持视角与桥呈 45 度。”

- S + D 的移动方向相对相机是 225°（`travelYaw(...)`：`atan2(-dx, dz)` 换算），
  而 225° 与桥线本身正好差 45° —— 这就是“视角与桥呈 45 度”的几何含义。
- `suggestedCameraYaw(bridgeYaw, DIAGONAL) = bridgeYaw + 135°` 是它的反解；
  测试里做了往返校验：`travelYaw(suggestedCameraYaw(θ, 1), -1, +1) == θ`。
- 目标格沿这条对角线取前 0.5~0.6 格（`TARGET_ADVANCES = {0.60, 0.45, 0.75, ...}`），
  10 tick 一方块即 2.00 方块/秒。

### 3. 蹲起搭路 ≈3 方块/秒

教材原文拆成四步，对应四个阶段：

| 教材 | 阶段 | 代码 |
|---|---|---|
| “准星对准方块边缘，按一下右键” | （放置） | `flushPending()` → `place()` |
| “与此同时松开 shift” | `RELEASE` | tick 末尾 `setKeyPressed(mc, "keyBindSneak", false)` |
| “然后尽快按下 shift，防止掉落” | `RESNEAK` | `stepPhase(...) == PHASE_RESNEAK` |
| “等待潜行走到下一个方块边缘……再按一下右键” | `WAIT_EDGE` | `atEdge(edgeClearance, edgeMargin) && mayPlace(...)` |

```text
        ┌──────────── placed ────────────┐
        v                                │
   [RELEASE] ──预算用完/余量<=0.12/离地──> [RESNEAK] ──1 tick──> [EDGE] ──贴边+冷却──> C08
        ^                                                        │
        └──────────────────── 放置成功 ──────────────────────────┘
```

关键常量（`GodBridgeFeature`）：

```java
RELEASE_MIN_CLEARANCE = 0.45   // 贴着边缘时不松手，先潜行走过去
EMERGENCY_CLEARANCE   = 0.12   // 余量掉到这个值，不管预算剩多少立刻按回 Shift
edgeMargin            = 0.30   // “站在方块边缘”的判定余量（GUI 可调）
sneakWindow           = 3      // 松手预算，教材的“尽快按下 shift”
minPlaceInterval(S)   = 6      // 6 tick 一次 C08 的硬下限（防 MultiPlace / Timer）
```

按 7 tick/方块：松手 3 tick（全速 0.216 格/tick ≈ 0.65 格）→ 潜行 2~3 tick 走到
下一个边缘 → 放置。HUD 上的 `2.86b/s` 就是这条循环的实测值。

### 4. 贴边判定（`edgeClearance`）

教材：“在方块边缘又按下了 shift，防止掉落。”

```text
box = player.boundingBox
probe = 脚下一层薄片（minY-0.15 .. minY-0.02），沿行进方向以 0.05 格步进平移
第一次 getCollidingBoundingBoxes 为空的位置 = 落差
余量 = 那个位置之前一格
```

- 余量 `<= edgeMargin`：站到边缘，可以按右键；
- 余量 `<= 0.12`：快掉下去了，强制潜行；
- 余量 `< 0`：已经踩空，`safety` 打开时走 `findFallbackPlacement()` 往下补。

### 5. “准星对准方块边缘”

`probeCell()` 枚举目标格六个面，按“支撑面越靠身体这块越优先”打分：

```java
align = -(delta.x * dirX + delta.z * dirZ);        // 支撑方块在身后 -> 正分
score = align * 2.0 - distance * 0.05 + (face == UP ? 0.35 : 0);
```

命中后 `hitVec()` 把点击点放在面的**上沿**（`y += 0.25`，水平 ±0.28 抖动），
再用 `rotationTo()` 换成 yaw/pitch —— 这就是教材里“准星对准方块边缘”的手感，
俯角一般在 65°~77°（站桥上点侧面时约 76°）。

## 静默转向与 MoveFix

- `afterMovementInput()`（vanilla `updatePlayerMoveState()` 之后、`moveFlying()` 之前）：
  1. 把输入向量从相机坐标系换算到 aim 坐标系并吸附到 8 个整数方向
     （否则服务端预测的位移和客户端对不上）；
  2. `forward` 在 `(0, 0.8)` 时等比放大到 0.8，斜着搭桥也保得住冲刺；
  3. 把 aim 写进 `rotationYaw/Pitch`（`continuousYaw` 不取模，避免 360° 跳变），
     本 tick 的 C03 自然带上它，并记下 `sentYaw/sentPitch`。
- `onTickEnd()`（渲染前）：恢复相机角度；F5 身体保留服务端最后收到的 aim；
  `itemSpoof` 把客户端槽位切回开启时的槽（不发 C09）。
- `ensureMovementHook()` 与 Scaffold 共用 `ScaffoldMovementInput`，没有第二个 hook。

## C08 时序（Grim RotationPlace）

和 `Scaffold` 一样：**放置永远晚一 tick**。

```text
tick N   : 贴边 + 冷却 -> 选支撑面 -> aim 写进 C03（sentYaw/sentPitch）
tick N+1 : flushPending()
             !pendingSent / 槽位没同步      -> 丢弃
             打开 GUI / 死亡 / 没方块        -> 丢弃
             目标格已经不是空气               -> 丢弃
             rayTrace(sentYaw, sentPitch) 命中支撑面+面 -> PLACE
             没命中 -> 重试 1 次（下一 tick 重新瞄），再失败就丢弃
```

- `place()` 只从 `flushPending()` 调用，内部 `OpenMaverFeatures.claimPlacement()`
  保证一 tick 一条 C08（与 Velocity 的 farm 共用名额）。
- 切槽：先改 `inventory.currentItem`（客户端立即渲染），`serverSlot != blockSlot`
  时补 `C09PacketHeldItemChange`；1.8.9 不会自动同步槽位。
- `minPlaceInterval`（12/10/6 tick 的上限减去 1）挡住“贴边判定抖动导致连发”。

## 和 Scaffold 的关系

- 两套状态机都会抢 Shift 键和同一个 C08 名额，所以：
  - `GodBridge.onEnable()` 直接把 `Scaffold` 模块关掉；
  - `ScaffoldFeature.onTickStart()` 里再挡一次 `GodBridgeFeature.isActive()`；
  - `Scaffold` 若重新打开，神桥这一侧不主动让位（先开的赢），但两边不会同时 tick
    出两块 —— `claimPlacement()` 是公共闸门。
- 神桥**不跳**：没有 telly 的 75.5° 俯角表、没有 `jumpTicks`、没有 `targetYLevel`。
  教材里的手法本来就不跳，跳起来反而破坏“贴边按 Shift”的循环。

## 调参指南

| 想要 | 调什么 | 副作用 |
|---|---|---|
| 更快 | `SNEAK WINDOW` 3→4，`EDGE MARGIN` 0.30→0.38 | 松手更久，掉下去的概率上升 |
| 更稳 | `SNEAK WINDOW` 3→2，`EDGE MARGIN` 0.30→0.22 | 速度掉到 ~2.5 方块/秒 |
| 纯 45° 斜桥 | `MODE = DIAGONAL 45`，游戏里按 S+D | 视角要大致与桥线成 45°，否则会斜着走 |
| 完全不冒险 | `MODE = SPEED BRIDGE` | 1.67 方块/秒，和教材第一段一致 |

## 回归测试

```bash
cmake --build build -j          # 先构建一次
tools/godbridge-test/run.sh     # 73 项
tools/scaffold-test/run.sh      # 70 项（旧模块仍然绿）
```

覆盖：

- 12/10/7 tick → 1.67/2.00/2.86 方块每秒；
- S = 相机 +180°、S+D = 相机 +225°、`suggestedCameraYaw` 往返一致；
- `stepPhase` 全分支（放置、贴边不松手、预算用完、紧急余量、离地、回归 EDGE）；
- `mayPlace` 闸门（贴边 / 冷却 / 目标 / RELEASE 不放）；
- `rotationTo`（正前、右下 45°、正后、俯角夹紧）、`wrapDegrees`、`atEdge` 边界；
- 结构：唯一 `onPlayerRightClick`、`claimPlacement`、C09、Shift 通过
  `KeyBinding.pressed`、aim 只在 movementInput hook 里写、无 `jumpTicks`/75.5°、
  `Scaffold` 让位、注册/配置/ClickGUI/HUD 全部接上。

## 已知边界

- 没有 Mixin：itemSpoof 是“客户端槽位翻转”，不是渲染事件替换。
- 依赖 Netty/S12 的部分没有：神桥不处理击退，被击退时靠 `safety` 兜底补块。
- 目标格搜索只沿行进方向（±0.9 格）取，不做 Scaffold 那种 1..6 半径菱形扩散 ——
  教材的循环是“一格一格往前铺”，不需要向下找远格。
- 半砖 / 台阶会被 `TARGET_ADVANCES` 的第二层（`baseY - 1`）覆盖，但斜桥贴半砖时
  仍可能点不到面，此时 `flushPending` 的 ray 校验会丢弃，不会发出无效 C08。
