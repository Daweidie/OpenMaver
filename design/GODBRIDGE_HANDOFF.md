# GodBridge（神桥）实现说明

## 触发流程

与手动神桥一致，状态全部由 MC 侧 `OpenMaverFeatures.updateGodBridge()` 驱动：

1. **待触发（armed）**：玩家手持可放置方块、在地面按住 Shift，且视线命中方块边缘
   （`objectMouseOver.hitVec` 距离本地方块边界 ≤ 0.30）。
2. **开始**：松开 Shift 时仍按住 D（`movementInput.moveStrafe < 0`）且仍在地面，
   立即进入 active 本 tick 就开始尝试放置。
3. **进行中**：按当前 W/A/S/D 输入 + yaw 计算移动方向，在脚下/前方补块；
   跳跃不会改变桥面高度，A/D 可随时改变铺设方向。
4. **停止**：再次按 Shift、关闭模块、打开 GUI/聊天、死亡、空手或切换物品。

## 放置策略

- 复用 `tryPlaceBlock()`（Scaffold 与 GodBridge 共用）：
  遍历目标空气格的 6 个相邻支撑面，只尝试从眼睛方向可见的面，设置视角后调用
  `EntityRenderer.getMouseOver(1.0F)` 刷新 `objectMouseOver`，命中位置与 `sideHit`
  完全一致才调用 `Minecraft.rightClickMouse()`。
- **先脚下、再前方**：真正需要补块的是玩家“踏空后所在的那一格”。此时身后
  上一格方块侧面朝向玩家，抬高一点点就能被 ray trace 命中。
- 神桥模式下支撑面可见性阈值放宽到 `dot > 0`，并把瞄准点的 inset 从 `0.05`
  降到 `0.002`，避免射线先打到支撑方块顶面而错过侧面。
- `godbridge.fastPlace` 默认开启，但实际是否压缩 `rightClickDelayTimer` 由
  `bypass.profile` 决定：`legit/balanced` 不压 0，等待原版冷却自然归零；
  只有 `aggressive`（或关闭 bypass）才写 0。停止时恢复为 4。
- `godbridge.legitAim` 默认开启：完全使用玩家真实准星，不修改 rotation，
  也不发送额外 flying packet；只有 `objectMouseOver` 已命中目标格支撑面时才右键。
  这是当前对 Grim 最干净的模式。
- `godbridge.packetRotation` 默认关闭（实验性）：在 C08 前补发一次
  `C05PacketPlayerLook`，让服务端在 C08 前更新 yaw/pitch。`legit/balanced`
  下会被强制抑制；只有 `aggressive` 才实际发送。额外 C05 会被 Grim 的 `Timer`
  当成额外 tick，也可能触发 `AimDuplicateLook` / `AimModulo360`，因此只保留用于对比。
- Scaffold 与 GodBridge 共用 `tryPlaceBlock()`，因此 Scaffold 也有
  `scaffold.legitAim`（默认 true）与 `scaffold.packetRotation`（默认 false）。
- `legit/balanced` 下 placement 自动旋转被强制回退为真实准星；`autoRotate`
  只在 `aggressive` 生效。成功/失败后都会恢复临时 rotation 与 `objectMouseOver`。
- 自动旋转路径的 yaw 统一 `wrapDegrees` 到 [-180, 180)，避免 `AimModulo360`。

## 配置与设置

- 配置路径：`movement.godbridge`，默认键位 `B`。
- `godbridge.delayTicks`：0~4 tick，默认 0。
- `godbridge.fastPlace`：默认 true。
- `godbridge.requireEdgeAim`：默认 true；关闭后蹲下即可 armed。
- `godbridge.autoRotate`：默认 true，放置后恢复原视角。

## 使用的反射 / SRG 名称

- `movementInput / field_71158_b`
- `moveForward / field_78900_b`
- `moveStrafe / field_78901_c`
- `isSneaking / func_70093_af`
- `onGround / field_70122_E`
- `rightClickDelayTimer / field_71467_ac`
- `objectMouseOver / field_71476_x`
- `MovingObjectPosition.typeOfHit / field_72313_a`
- `MovingObjectPosition.blockPos / field_178783_e`
- `MovingObjectPosition.sideHit / field_178784_b`
- `MovingObjectPosition.hitVec / field_72307_f`
- `Vec3.xCoord / field_72450_a`、`yCoord / field_72448_b`、`zCoord / field_72449_c`
- `BlockPos.getX/Y/Z / func_177958_n / func_177956_o / func_177952_p`
- `getMouseOver / func_78473_a`
- `rightClickMouse / func_147121_ag`
- `EntityPlayerSP.sendQueue / field_71174_a`
- `NetHandlerPlayClient.addToSendQueue / func_147297_a`
- `C03PacketPlayer$C05PacketPlayerLook(float yaw, float pitch, boolean onGround)`

## 相关文件

- `java/module/movement/GodBridge.java`
- `java/OpenMaver.java`
- `config/default.jsonc`
- `mc/OpenMaverFeatures.java`
- `mc/GuiOpenMaverClickGui.java`
- `README.md`
