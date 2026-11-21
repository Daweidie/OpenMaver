# Telly 跑跳搭路

独立模块 `movement.telly`，默认关闭、无默认热键。ClickGUI 的 Movement 分类显示 `Telly`，
右键展开参数；快捷栏放可放置整块方块，按移动键自动起跳，停下释放自动跳跃。

## 参考实现与接入

参考 `../OpenZen/src/main/java/shit/zen/modules/impl/movement/Scaffold.java` 的
`onTick`、`getTargetRotation`、`onPreMotion`、`doSnap`：

- `TELLY BRIDGE`：锁定桥面高度，起跳阶段使用 75.5° 俯角，空中第一拍与后续拍分别限制
  yaw 转动为约 90° / 50°，再经过灵敏度量化和平滑处理。
- `OLD TELLY`：地面阶段保持相机 yaw，空中沿用旧式转向逻辑。
- `SNEAK`：第 18 tick 潜行，第 21 tick 释放；`CLUTCH`：两 tick 垂直预测与击退等待。

`java/module/movement/Telly.java` 只负责开关及搭路模块互斥，不依赖 Minecraft 类。
`mc/TellyFeature.java` 管理独立设置，模式值 0 / 1 映射到引擎的 Telly Bridge / Old Telly。
`mc/ScaffoldFeature.java` 根据启用模块选择 `Settings`，共用一套目标搜索、快捷栏同步、
MovementInput 静默转向及待放置队列。Scaffold 原有四种模式和配置继续保留。

## 1.8.9 时序与输入

1. tick START 捕获相机、地面 / 空中阶段以及桥面高度，校验上一拍的待放置目标。
2. 规划本拍 aim；`ScaffoldMovementInput` 在原版采样按键后执行 MoveFix 并写入 aim，
   原版 C03 携带该角度。tick END 恢复客户端相机。
3. 下一拍使用已发送 aim 从当前眼睛做射线校验，再调用原版右键放置。换快捷栏的当拍不放，
   C09 由原版同步；C08 后的 C03 保留已经校验过的放置角度。

物理跳跃与 `KeyBinding.pressed` 必须分开：自动跳跃写 `pressed`，不能让它触发
`targetYLevel = floor(y) - 1` 的物理空格分支，否则正常跑跳会变成不断抬高桥面。
地面转向表仍读逻辑跳跃状态，与 OpenZen 的 `keyJump.isDown()` 对齐。物理读取支持键盘和鼠标绑定。
`doSnap` 的顶面限制同样只允许物理空格主动升高。

落地时取消仍在排队的空中放置，地面阶段不创建待放置目标。GUI、死亡、断线、玩家或世界对象变化、
模块切换时先还原槽位与物理按键状态，清理队列，再初始化新状态，避免旧 aim 跨世界继续放置。
SafeWalk 和 AttackReduce 通过原有 Scaffold 引擎状态判断让位；FastPlace 与方块 HUD 也识别独立 Telly。

## 配置

开关和热键保存在 `config/default.jsonc` 的 `movement.telly`。
参数位于 `config/openmaver.properties`：

```properties
telly.mode=0
telly.sneak=true
telly.clutch=true
telly.rotationTick=3
telly.slotMode=1
telly.itemSpoof=true
telly.hud=true
```

`slotMode=1` 为真实换手 HOTBAR；`slotMode=0` 为 SILENT，此时 `itemSpoof` 控制手部显示旧物品。
HUD 复用 Scaffold 的方块计数与速度卡片，通过 `telly.hud` 独立开关。

## 验证

```sh
cmake --build build -j 4
bash tools/telly-test/run.sh
bash tools/scaffold-test/run.sh
bash tools/godbridge-test/run.sh
bash tools/safewalk-test/run.sh
bash tools/fastplace-test/run.sh
```

Telly 测试运行真实 tick 生命周期与反射代码，使用无窗口游戏替身覆盖独立配置与互斥、物理 / 自动跳跃、
桥面高度、地面禁放、空中射线放置、未发送 aim 禁放、落地取消、相机恢复、潜行节拍、GUI / 世界 / 玩家切换、
断线与死亡释放，以及 MCP / SRG 按键字段及鼠标绑定。
这些检查不替代真实 Minecraft 1.8.9 / Forge 中的联机实测。OpenZen 的 `delayPackets` 拆 tick
没有移植到 1.8.9，沿用 Maver 的等待与重试机制。
