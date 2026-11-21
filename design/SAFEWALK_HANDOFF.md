# SafeWalk（方块边缘潜行）实现说明 —— 从 Scaffold 的 eagle 拆出的独立模块

> **2026-11-22**：Scaffold 重写成 OpenZen 移植版后，“贴边才按 Shift”只剩
> `ScaffoldFeature.updateSneak()` 里的一行 `scaffold.eagle`，而且只在
> `MODE_NORMAL` 下生效。本模块把它做成独立开关：**任何手持物品、任何搭路方式、
> 不搭路也能用**。Scaffold 的 `eagle` / `sneak` 保持原样（OpenZen 面板逐项对应），
> 两者同时开启时 SafeWalk 主动让位。

## 文件

| 文件 | 作用 |
|---|---|
| `java/module/movement/SafeWalk.java` | 模块开关（`movement.safewalk`，默认关闭、默认不绑定热键） |
| `mc/SafeWalkFeature.java` | 全部行为：探针、决策表、`keyBindSneak` 接管与还原 |
| `mc/OpenMaverFeatures.java` | `onTickStart` 每 tick 驱动一次；`load` / `save` / `reset` / `disableAll` |
| `mc/ScaffoldFeature.java` | `ownsSneakKey()`：Scaffold 是否正在接管 Shift（让位判定） |
| `mc/GodBridgeFeature.java` | `isActive()`：神桥活跃时同样让位 |
| `mc/GuiOpenMaverClickGui.java` | 展开 SafeWalk 的 5 个设置 |
| `mc/OpenMaverHud.java` | 模块列表右侧参数摘要（`EDGE` / `0.3b`） |
| `tools/safewalk-test/` | 61 项回归（决策表 / 数学 / 探针几何 / 配置 / 源码结构） |

配置写在 `config/openmaver.properties`（`settings.version=22` 起）：

```properties
safewalk.distance=0.3        # 探针沿输入方向前伸的距离（0.05..0.60 格）
safewalk.onlyGround=true     # 只在地面生效
safewalk.requireMoving=true  # 只有按着 WASD 才生效
safewalk.respectSneak=true   # 玩家自己按着 Shift 时不接管
safewalk.holdTicks=2         # 松开边缘后继续保持潜行的 tick 数
```

## 为什么只写按键，不写 motion

老 Myau 版 Scaffold 的 `SafeWalk` 是“把会踏空的那部分 motionX/Z 收缩到 0.05 步长”。
那一套在 1.8.9 上没有正确的注入点：

- `motionX/Z` 在 `EntityLivingBase.moveEntityWithHeading()` → `moveFlying()` 里
  **累加**得到，而 `moveFlying()` 之后没有任何 Forge 事件（`LivingUpdateEvent`
  只有 onLivingUpdate 的首尾）；
- 在 tick 开头改 motion，原版紧接着还会把 `f5 * input` 再加一次，等于每 tick 都
  把“刹车”松开一部分，贴边时会以 delta（约 0.02~0.03 格/tick）的速度继续往边缘
  外面挪，最终仍然会掉下去；
- 直接在 tick 末尾改 motion 则只影响下一 tick 的摩擦残余，对“这一步会不会踏空”
  毫无作用；
- 更现实的问题是：手改 motion 等于跳过原版物理，Grim 的 `Simulation` 会把它当成
  位移预测不一致（旧版 `SCAFFOLD_HANDOFF.md` 里 `grimStrict()` 直接把 SafeWalk
  关掉，就是同一个原因）。

所以这里用原版自己的机制：**按住 Shift**。潜行本来就是原版的速度上限 + 防边缘，
服务端看到的是 `C0BPacketEntityAction START/STOP_SNEAKING`。Scaffold 的 `eagle`
用的也是这条路（`setKeyPressed(mc, "keyBindSneak", ...)`），在服上验证过。

## 主 tick

`OpenMaverFeatures.onTickStart()` 里的顺序（ClientTickEvent.START）：

```text
ScaffoldFeature.onTickStart(mc)
GodBridgeFeature.onTickStart(mc)
Scaffold2Feature.onTickStart(mc)
SafeWalkFeature.onTickStart(mc)     // ← 必须排在后面
updateKillAura / updateAutoClicker ...
```

排在最后是因为 SafeWalk 要在 `yieldsToOther()` 里看到 **本 tick** Scaffold /
GodBridge 有没有接管 Shift：

```java
static boolean yieldsToOther() {
    return ScaffoldFeature.ownsSneakKey() || GodBridgeFeature.isActive();
}
```

`ownsSneakKey()` = `active && !failed && ((mode == NORMAL && eagle) || sneak)`。
Scaffold 默认 `eagle=true`、`sneak=true`，所以“Scaffold 开着”时 SafeWalk 一律
`YIELD`：同一个 `keyBindSneak` 两边都写，服务端会收到一串 STOP/START 动作包。
要让 SafeWalk 接管全局潜行，把 Scaffold 的 Eagle/Sneak 关掉（或关掉 Scaffold）。

## 决策表（`decide`）

```text
!enabled / yielded / selfSneak / !onGround  -> false
onEdge                                      -> true   （并重置 holdLeft = holdTicks）
moving && holdLeft > 0                      -> true   （holdLeft 每 tick 递减）
否则                                        -> false
```

- `onGround`：`onlyGround=false` 时恒为 true；
- `moving`：`WASD` 任一按键按下，`requireMoving=false` 时恒为 true；
- `selfSneak`：`respectSneak` 打开且 `Keyboard.isKeyDown(keyBindSneak.keyCode)`；
  这里必须读物理键盘，不能读 `KeyBinding.pressed` —— 那个值本模块自己写过，
  读它会得到“玩家在潜行 → 不接管 → 松开 → 又不潜行”的自激振荡。

滞回（`holdTicks`，默认 2）解决的是“贴着边缘横着走”：探针逐个 tick 在触发/不触发
之间跳变时，Shift 会跟着抖，服务端看到一串 `STOP_SNEAKING / START_SNEAKING`。
滞回让它在最后一次触发后继续按住 2 tick。

## 探针

```java
AxisAlignedBB box = boundingBox(player);
double[] dir = travelVector(rotationYaw, input.moveForward, input.moveStrafe); // 单位向量
AxisAlignedBB probe = probeBox(box, dir[0] * distance, dir[1] * distance);
List<?> hit = world.getCollidingBoundingBoxes(player, probe);   // func_72945_a
boolean edge = hit.isEmpty();
```

- `travelVector` 与 1.8.9 `Entity.moveFlying` 同一个换算式：

  ```text
  sin = sin(yaw), cos = cos(yaw)
  x = strafe * cos - forward * sin
  z = forward * cos + strafe * sin
  ```

  斜向输入先归一化 —— 否则斜着走时探针会被拉长到 1.414 倍，提前一格开始潜行。
- `probeBox`：碰撞箱**中心** + 位移，半径 0.06 的立方体，竖直方向整块压在
  `box.minY - 0.01` 之下。
  - 用中心点而不是整箱：原版潜行防边缘本身就是“脚下中心那一列还有没有方块”的
    语义；用整箱（0.6 宽）判定会让玩家在离边缘还有 0.3 格时永远不触发。
  - 竖直压到脚下：贴墙走时脚下的墙块不该被算成“支撑面”，否则永远不潜行、照样
    掉下去。
- 探针查不到方块 = “再走一步会踏空”。`distance` 默认 0.30，触发点在越过边缘前
  约 0.36 格（0.30 + 半个探针 + 原版潜行减速 0.3× 的余量），一个 tick 的
  走速是 0.216 格，足够在原版减速之后停住。

## 按键接管

- 触发时 `KeyBinding.pressed = true`（`keyBindSneak`，SRG `field_74311_E`）。
  原版 `MovementInputFromOptions.updatePlayerMoveState()` 把它读成
  `MovementInput.sneak`，`EntityPlayerSP` 据此发 C0B 动作包。
- 释放：`restoreSneakToPhysical()` 用 `getKeyCode()`（`func_151463_i`）+
  `Keyboard.isKeyDown()` 还原 —— 玩家真按着 Shift 时保持按下。
- `owned` 标记“这个 `pressed=true` 是我们写的”，只有自己写过的才还原；
  让位（`YIELD`）时一根手指都不碰，Scaffold / GodBridge 本 tick 写好的值原样保留。
- 关闭模块、配置重载、`disableAll()`、死亡 / 开 GUI / 换世界 → `release()` 还原。
- 不接管的情形：旁观、飞行（`capabilities.isFlying`）、水中 / 岩浆、蜘蛛网、
  梯子、骑乘、睡觉、没有任何世界或屏幕打开。

## 与别的模块的边界

| 模块 | 关系 |
|---|---|
| Scaffold `eagle` / `sneak` | 活跃时 SafeWalk `YIELD`（见上）；Scaffold 不调用 SafeWalk 的任何代码 |
| GodBridge `SNEAK TOGGLE` | `GodBridgeFeature.isActive()` 时同上 |
| AutoSprint | 不冲突：SafeWalk 只管 Shift；潜行期间是否停冲刺由原版与 AutoSprint 自己决定 |
| Velocity / Scaffold 的 moveFix | 都发生在 `movementInput` hook 里，SafeWalk 在 tick 开头只碰按键，不改 input 数值 |

## 反射 / SRG 名称

| 用途 | MCP | SRG |
|---|---|---|
| 玩家 / 世界 / 屏幕 / 设置 | `thePlayer` / `theWorld` / `currentScreen` / `gameSettings` | `field_71439_g` / `field_71441_e` / `field_71462_r` / `field_71474_y` |
| 潜行键 / 按下状态 | `keyBindSneak` / `pressed` | `field_74311_E` / `field_74513_e` |
| WASD | `keyBindForward/Back/Left/Right` | `field_74351_w` / `field_74368_y` / `field_74370_x` / `field_74366_z` |
| 输入向量 | `movementInput.moveForward/moveStrafe` | `field_71158_b` / `field_78900_b` / `field_78902_a` |
| 视角 | `rotationYaw` | `field_70177_z` |
| 落地 | `onGround` | `field_70122_E`（走 `scaffoldOnGround`） |
| 碰撞箱 | `getEntityBoundingBox` | `func_174813_aQ` |
| 碰撞查询 | `getCollidingBoundingBoxes` | `func_72945_a` |
| 按键码 | `KeyBinding.getKeyCode` | `func_151463_i` |
| 骑乘 / 飞行 / 液体 | `ridingEntity` / `capabilities.isFlying` / `isInWater` / `isInLava` | `field_70154_o` / `field_71075_bZ.isFlying` / `func_70090_H` / `func_180799_ab` |
| 网 / 梯子 / 睡觉 / 旁观 | `isInWeb` / `isOnLadder` / `isPlayerSleeping` / `isSpectator` | `field_70134_J` / `func_70617_f_` / `func_70608_bn` / `func_175149_v` |

## 调试

- 状态串（HUD 参数摘要 / 调试）：`OFF` / `IDLE` / `EDGE` / `HOLD` / `YIELD` / `ERROR`。
- `SafeWalkFeature.getTickCount()`：被驱动的 tick 数。
- 反射出错（映射差异）时 `failed=true` 并停用，只在 stderr 打一行，不刷屏。

## 回归测试

```bash
cmake --build build -j        # 先构建一次（需要 mc-classes / mcstubs-classes）
tools/safewalk-test/run.sh    # 61 项
```

覆盖：

- `decide` 决策表：关闭 / 让位 / 空中 / 玩家自按 / 无边缘 / 滞回 / 不移动；
- 滞回上下限（0..5 tick，默认 2）；
- `travelVector`：yaw 0/90/180、strafe 方向、斜向归一化、零输入；
- `probeOffset` 前伸距离；
- `probeBox` 几何：跟随中心、竖直压在脚底以下、半径 0.06；
- `setDistance` 夹紧（0.05..0.60）；
- `load` / `save` 往返与缺省值；
- 源码结构：只写 `keyBindSneak.pressed`、还原用 `Keyboard.isKeyDown`、
  不写 motion / rotation、不发包、`applySneak` 唯一写入口、让位判定、
  `OpenMaverFeatures` 每 tick 驱动一次、Scaffold 不调用 SafeWalk。

## 已知边界

- 探针是“脚下支撑面”判定，不模拟摩擦与击退：被 S12 打飞的一瞬间可能已经越界，
  那时潜行也没用（Velocity / Scaffold 的 clutch 负责那一段）。
- 半砖 / 台阶边缘按方块碰撞箱算，视觉上会“提前”一格潜行；用 `DISTANCE` 调。
- 只在 `ClientTickEvent.START` 写键；如果整合包也在这条路径上改 `keyBindSneak`
  （例如别的 scaffold），顺序按 `OpenMaverFeatures.onTickStart` 里的调用顺序决定。
- 服务端若把潜行当成减速判定（部分反作弊的 `Sprint` 检查），SafeWalk 触发时速度
  掉到 0.3× 是**真实**行为，和手按 Shift 完全一样，不存在伪造特征。
