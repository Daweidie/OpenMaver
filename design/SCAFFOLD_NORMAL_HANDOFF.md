# Scaffold / Normal（Augustus 参考实现）

Normal 在 `mc/AugustusScaffold.java` 中独立规划，由 `ScaffoldFeature.tickNormal()` 驱动。
`tick()` 的 OpenZen 搜索、旋转、targetYLevel、周期 Sneak、Clutch 与 S12 抑制只供其他模式使用。
旧 Normal 的 Snap、Eagle 状态/探针/设置、按住右键才能放置的门槛均已删除。

## 参考来源

上一级 `../OpenAugustus-Maver/src/main/java/augustus/recovered/modules/world/Scaffold_47Te.java`。
这是部分恢复的代码，不能把其中所有混淆分支或 native 依赖视为完整可移植实现。

| 参考方法 | 对应实现 |
|---|---|
| `method_48$f2eea116cc` | `findSupport`：移动预测 ×2，沿移动反方向分层探测，再做周向扫描，选择最近支撑，最后检查脚下 |
| `method_44$85a266d04d` / `method_46$4d73d08bb0` | `movementYaw`，WASD/对角线换算后朝移动反方向转向 |
| `method_49$f9db2aeeaf` | 默认准备俯角 80.34°；尚无可点侧面时也提前转向 |
| `method_43$e45a9973e5` | `scanPitch`：15° 至 <90°、0.04° 步进、鼠标量化、射线验证，必要时交替扩展 yaw |
| `method_41$26e4781eeb` | 平滑后再次扫描俯角并按 MaxPitchCorrection（默认 180°）限制修正 |
| `method_43$0d3917b489` | Nearest / Furthest / Highest / Lowest / Mid / Random 六种候选选择 |
| `method_48$e5b9dbd68d` | 用真实射线命中位置右键，按设置挥手，成功后更新 CPS 等待时间 |
| 构造器的默认配置 | 速度 30–50、加速度 7–15、CPS 10–20、Nearest、额外搜索/Always Place/Force Place 开启、No Swing 关闭 |

## 独立规划

1. 从玩家位置、水平 motion ×2 分层搜索支撑块，确认支撑实心且上方为空。
2. 根据相机与当前 WASD 求反向 yaw。扫描从预测一步后的眼睛能够命中的俯角（优先用上一 tick 实际位移，避免摩擦后的 motion 低估移动）；
   先用支撑的投影区间和水平包围盒裁掉不可能的角度，再保留参考实现的扫描步长。
   斜向跨角时，额外搜索同时检查周围支撑，优先选择刚放出的更近邻块。
3. 按选择模式从候选中选俯角；额外搜索启用时交替尝试 ±yaw 偏移。
   平滑后额外搜索会逐候选重新预测 MoveFix 位移，yaw 修正仍受本 tick 的速度采样上限限制。
   无可用侧面时仍朝移动反方向和默认 80.34° 预转，避免走到边缘才开始转向。
4. 从上一条已发送角度出发，对 yaw/pitch 分别加速度限速，再按相对鼠标步长量化。切换到反方向目标时重置该轴的平滑速度。
   平滑后若仍未命中，按当前 yaw 再扫描俯角，按 MaxPitchCorrection 限制相对上一条
   服务端俯角的修正；默认 180°，因此最终点击俯角可超过基础 Pitch Speed 的步长。
5. 用最终角度重做射线；必须命中所选支撑、点击面不是 DOWN、相邻格为空。
   UP 只在脚部高度高于支撑两格时参与（向上搭）。
6. 检查 Always Place / CPS / Force Place。两个强制开关可以越过 CPS 等待；
   两者关闭且 CPS 为零时不自动点击。Normal 不读取物理右键状态。

Nearest / Furthest 比较与上一条已发送俯角的距离；Highest / Lowest 取最大/最小俯角；
Mid 取 hitVec 在方块内的相对 Y 最接近 0.5 的候选；Random 随机选择有效候选。

## 与现有客户端的适配

- 参考旋转辅助类 `src/dependency/java/6e65742f34376767/C_47gg.java` 只有 native 声明。
  此处用带速度/加速度上限的实现替代，不能声称 native 曲线逐帧相同。
- 不包含 Augustus 的全部可选功能，如各种 Flick/移动子模式、SameY、可选 Sneak、Telly、Tower 设置。
  这里实现普通搭路的默认路径和当前界面提供的参数。
- 共用 OpenMaver 的库存扫描、碰撞/空气判断、静默输入 hook 和放置传输。
  Normal 从预转阶段即使用八方向 MoveFix，潜行保持原版 0.3 倍输入。
  原有针对修正前输入的疾跑限制继续生效，KeepSprint 在 Normal 下让位。
- 预测眼睛、实际眼睛和 hitVec 全程使用 double；不沿用旧控制器的 float 眼睛坐标或随机面点。
  用上一 tick 实际位移估计加速度，再按本 tick MoveFix 的实际朝向修正预测，
  避免细小 yaw 变化导致拐角命中面变化。放置时若仍命中同一支撑块的另一面，
  重新检查该面相邻格并使用当次命中面，不复用旧面的假设。
- C03 先携带 aim，下一 START 重新检查当前世界/眼睛和同一角度，仍命中所选支撑且当前面相邻为空才发 C08；
  C08 使用当次真实 hitVec，成功后保持该角度至本 tick C03。
  快捷栏 C09 仍由原版发送，换槽当 tick 禁止点击，每 tick 共用一次放置预算。
- GUI、世界/玩家变化、disable、模式切换和配置加载都会清理目标、旋转速度及 CPS 计时。
  Normal 默认不接管跳跃或潜行，SafeWalk 按自己的设置独立工作。

## 配置与界面

`scaffold.normal.yawSpeedMin/Max`、`pitchSpeedMin/Max`、`yawAccelerationMin/Max`、
`pitchAccelerationMin/Max`、`cpsMin/Max` 保存区间；其余为 `pitchSelection`（0..5）、
`maxPitchCorrection`（0..180，默认 180）、`extraBlockSearch`、`alwaysPlace`、`forcePlace`、`noSwing`，均带 `scaffold.normal.` 前缀。
区间加载拒绝 NaN/Infinity，限制边界并保持上下限顺序。

ClickGUI 依据模式显示参数；SLOT、ITEM SPOOF 和 BLOCK HUD 共用。
旧 `scaffold.eagle` 和 `scaffold.snap` 不再读取/输出；旧 Sneak、Clutch、Rotation Tick
仍为其他模式保留，Normal 不使用。

## 验证

`cmake --build build -j 4` 后运行：

- `tools/telly-test/run.sh`：Normal 生命周期/配置/点击计时与八方向连续搭桥，含百万坐标；
  实际输入 hook、地面加速度/摩擦、真实方块射线与 C03/C09/C08 校验，同时覆盖 Simple/Telly。
- `tools/scaffold-test/run.sh`：共享放置决策、数学与独立规划分派。
- `tools/keepsprint-test/run.sh`、`tools/safewalk-test/run.sh`、`tools/clickgui-test/run.sh`：关联模块和界面。

这些是无图形环境仿真，尚不能替代真实 Minecraft 客户端及服务器测试。
