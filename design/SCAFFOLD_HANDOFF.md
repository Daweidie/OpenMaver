# Scaffold（自动搭路）接力说明

## 当前状态

- 已删除 Telly 模块及其 MC 侧逻辑、Ready HUD、ClickGUI 设置与配置项。
- 已新增 `java/module/movement/Scaffold.java`，配置路径为 `movement.scaffold`。
- 已在 `java/OpenMaver.registerModules()` 注册 `new Scaffold()`。
- `config/default.jsonc` 已加入：

```jsonc
"scaffold": {
  "enabled": false,
  "key": "G"
}
```

- **已完成基础实现**：`OpenMaverFeatures.updateScaffold()` 会在安全状态下自动
  寻找脚下/运动方向前方的空气格和可见支撑面，通过 `getMouseOver` 校验后调用
  游戏自身的 `rightClickMouse` 放置。
- 已加入 `scaffold.delayTicks` / `scaffold.requireBackward` /
  `scaffold.autoJump` / `scaffold.autoRotate` / `scaffold.legitAim` /
  `scaffold.packetRotation`，并在 ClickGUI 中绑定。
- `legit/balanced` profile 下强制使用真实准星放置，自动旋转与 C05 被抑制；
  只有 `aggressive` 允许实验性自动旋转/发包。
- `FastPlace`、Aim Assist、Player ESP、AutoSprint、AutoClicker、HUD、ClickGUI、
  配置热重载和 mod/inject 双加载方式都保持原样。

## 接力目标（已完成）

实现一个基础、稳定的 Scaffold（自动搭路）功能，不做反检测、Packet 伪造、
Timer/Speed 或复杂寻路。优先保证：不会在 GUI/聊天/菜单中误放，不会崩溃，
不会每 tick 刷屏。

## 必须遵守的项目约束

1. `java/module/**` 属于 core，可能由 bootstrap ClassLoader 加载，禁止引用
   `net.minecraft.*`。模块类只保存开关、键位和配置。
2. MC 逻辑放在 `mc/OpenMaverFeatures.java`（建议新增
   `updateScaffold(Minecraft mc)`）或 `mc/` 下新类。
3. 优先使用现有反射辅助方法 `read / call / number / setNumber / findField`；
   MCP 名和 SRG 名都要传，例如：
   - `rightClickMouse / func_147121_ag`
   - `clickMouse / func_147116_af`
   - `rotationYaw / field_70177_z`
   - `rotationPitch / field_70125_A`
   - `rightClickDelayTimer / field_71467_ac`
   - `entityRenderer / field_71460_t`
   - `getMouseOver / func_78473_a`
   - `objectMouseOver / field_71476_x`
   - `gameSettings / field_71474_y`
   - `keyBindBack / field_74370_x`
4. 如果新增直接编译期 MC 调用，必须同步更新 `tools/McpReobf.java` 和
   `mcstubs/`，保证 mod 模式与 inject 模式运行期都能找到 SRG 成员。
5. 禁止 `java.awt.Robot`、系统级鼠标模拟、外部输入库。
6. 所有反射/异常必须捕获。失败时静默停用对应功能，并只记录一次日志。
7. 不要在同一个 tick 连续放置；不要补发积压放置。
8. 打开 GUI、聊天、ESC 菜单、玩家死亡、离开世界或关闭模块时必须重置计时器，
   不能残留一次立即放置。

## 建议实现路径

### 1. MC 侧入口

在 `OpenMaverFeatures.onTick(Minecraft mc)` 现有功能之后调用
`updateScaffold(mc)`，并通过：

```java
Module scaffold = ModuleManager.getModule("Scaffold");
```

判断模块是否启用。

### 2. 运行条件

只在真实游戏内生效：

- `currentScreen == null`
- `thePlayer / theWorld` 非空
- 玩家存活
- 手持可放置方块（可复用现有 `holdingBlock(player)`）
- 与 FastPlace 共用 `rightClickDelayTimer` 时注意执行顺序

### 3. 基础行为（建议从最稳版本开始）

搭路最常见的行为是：玩家向后移动/从方块边缘后退时，自动把方块放到脚下或身后
同一水平高度。建议先实现：

- 目标空气格：脚下 / 身后一格（必要时再覆盖两格）。
- 支撑方块：目标格相邻的 6 个方向中寻找实心方块。
- 只点击从眼睛方向可见的面，避免选中被方块自身挡住的背面。
- 设置 `rotationYaw / rotationPitch` 后调用
  `entityRenderer.getMouseOver(1.0F)` 刷新 `objectMouseOver`，再调用
  `rightClickMouse / func_147121_ag`。
- 校验 `objectMouseOver` 的 `blockPos` 和 `sideHit` 是否与目标一致。
- 放置成功后重置冷却；失败则下一 tick 再尝试，不要连续补发。

### 4. 设置项（建议）

在 `OpenMaverFeatures` 增加 `scaffold.*` 设置，并在
`refreshSettings()` / `saveSettings()` 中读写：

- `scaffold.delayTicks`：1~10 tick，默认 2。
- `scaffold.autoRotate`：默认 true，放置后恢复原视角。
- 如有必要再加 `scaffold.requireBackward`、`scaffold.autoJump`。

在 `mc/GuiOpenMaverClickGui.java` 的 `buildEntries()` 中为
`"Scaffold".equals(module.getName())` 增加设置绑定；setter 调用 `markDirty()`，
涉及冷却的 setter 同时重置冷却。

### 5. 关于自动移动

上一版 Telly 曾尝试通过 `keyBindBack / field_74370_x` 自动按住后退键。
如果搭路需要自动执行，请注意：

- `MovementInputFromOptions` 会在玩家 tick 中覆盖 `movementInput`，
  直接在 `ClientTickEvent` 末尾改 `movementInput.moveForward` 不一定生效。
- 可通过游戏自身 `KeyBinding.setKeyBindState / func_74510_a(int, boolean)`
  设置后退键状态，但必须在停止时恢复为 `Keyboard.isKeyDown(keyCode)`，
  避免卡键或覆盖玩家真实输入。
- 自动移动属于较强干预，建议先做手动后退版本，确认稳定后再考虑。

## 验收清单

1. `cmake --build build -j2` 成功。
2. `./launch-openmaver.sh --mod` 正常加载，`logs/latest.log` 无新增异常。
3. `./launch-openmaver.sh --inject` 正常注入，HUD/ClickGUI 可见。
4. Scaffold 能从 HUD 和 ClickGUI 正常开关。
5. 打开背包、聊天、ESC 菜单时立即停止，不误放。
6. 关闭模块后不残留一次立即放置。
7. `/tmp/openmaver-events.log` 无 `LinkageError`。
8. 不破坏 Aim Assist、Player ESP、AutoSprint、AutoClicker、FastPlace。

## 相关文件

- `java/module/movement/Scaffold.java`
- `java/OpenMaver.java`
- `config/default.jsonc`
- `mc/OpenMaverFeatures.java`
- `mc/GuiOpenMaverClickGui.java`
- `mc/OpenMaverHud.java`
- `README.md`
- `tools/McpReobf.java`（仅在新增直接 MC 调用时修改）
- `mcstubs/`（同上）
