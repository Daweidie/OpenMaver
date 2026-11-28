# KeepSprint：移动方向转向

模块 `movement.keepsprint` 默认关闭、不绑定热键。模块开启后的默认行为改为
`keepSprint.rotate=true`，已有 properties 没有这个键也默认开启。

## ROTATE 生命周期

| 边界 | 行为 |
| --- | --- |
| PlayerTick START | 摘掉旧 OMNI modifier，检查输入/旋转拥有者，安装共享 ScaffoldMovementInput，准备当前玩家 |
| MovementInputFromOptions 更新按键之后 | 保存相机 yaw；按 `viewYaw - degrees(atan2(strafe, forward))` 求移动 yaw；写入 yaw，把输入改成 `(1,0)`，启动原版疾跑 |
| 原版移动 | sprint 检查读到 forward=1；jump / moveFlying 使用同一移动 yaw；原版负责 modifier 和空中因子 |
| PlayerTick END | 保留移动 yaw；攻击标记按设置补 C0B START_SPRINTING（仅仍能疾跑且本地冲刺时） |
| 原版 onUpdateWalkingPlayer | 原版 C03 上报与移动物理相同的 yaw；本模块不构造任何移动包 |
| ClientTick END | 恢复保存的相机 yaw、prevRotationYaw 与手臂/头部 yaw；pitch 不变 |

后退对应 180°，左右对应 ±90°，斜向对应 ±45° / ±135°。输入转换保持世界方向，
速度使用原版前进键模型：原版后续把 forward 乘 0.98，使用原版 +30% sprint modifier。
不保留旧的斜向最大输入长度，不把 forward 放大到 sqrt(2)，不叠自定义 modifier。

输入回调仅对本 tick 准备的本地玩家/当前 input 生效，重复调用只处理一次，准备超过
500 ms 过期。切玩家、reset、配置切换会清理旧朝向；下一 START 也能恢复缺失 END
留下的镜头。模块在输入与 C03 之间被禁用时仍保留本 tick 物理用的 yaw，ClientTick END
归还镜头。hook 后的相对 yaw 增量在恢复时保留。

## 手动放置 / FastPlace 交接

`KeepSprintPlacement` 协调原版手动方块右键与移动旋转，独立于 FastPlace 开关。
`FastPlaceTransformer` 在 `Minecraft.rightClickMouse()` 的首条指令前检查交接；
未对齐时整次调用延后，冷却、挥手和交互包均由后续获准的原版调用执行。
FastPlace 的 0–5 tick 冷却设置保持原意。

收到右键请求或检测到实际 use-item 绑定按住时，以每 tick 20° 的偏移步长回到
相机朝向。offset 保留原始数值，最终精确回到 0；直接 wrap offset 或停在等价的
±360° 会在原版接回 idle yaw 时制造跳变。正常 KeepSprint 后退的初始差为 180°，
第一下放置最多等待约 9 个移动 tick（20 TPS 下约 450 ms）。鼠标本身的移动量仍保留。
原版最后上报的 `lastReportedYaw` 用于初始化拥有者切换和确认原生移动边界。

交接期间把按键映射到最接近目标世界方向的八方向离散输入，单步方向误差最多
22.5°，不写 motion、不构造 C03、不使用模拟器难以重现的连续模拟输入。
冲刺仅在原版条件允许且映射后的 forward >= 0.8 时启动；完全对齐相机后，
后退与纯侧移放置采用原版步行，前进放置仍可疾跑。

ClientTick END 在原版移动包之后发布就绪，再恢复相机。按住 use-item 期间保持
相机朝向，最后请求后另保留两个移动 tick，然后平滑返回移动 yaw；恢复后再次使用
原有 KeepSprint 逻辑。在返回中停下也先走到精确相机 yaw，再交回原版 idle 路径。
不改变正常 KeepSprint 在没有放置交接时的方向切换策略。

被延后的短按在就绪后的 ClientTick START 重试一次，先调用原版 `getMouseOver(1)`
刷新目标，再调用原版右键；按住或仍有排队的 KeyBinding press 时交由原版分派，
避免额外点击。不硬编码鼠标右键，因此重绑定到键盘也沿用同一状态。
玩家/世界、槽位或 ItemStack 身份变化、GUI、失焦、死亡、模块关闭和实际输入/旋转
拥有者接管时取消待重试点击。专用搭路模块仍持有自己的旋转与放置。

回归入口 `bash tools/keepsprint-fastplace-audit/run.sh` 执行最终 SRG jar 的真实回调，
覆盖 0–5t、八方向、七组多圈镜头 yaw、原版 look 历史、放置前后 ray/AABB、
短按目标刷新、队列去重、取消边界、原版冷却和停止移动时的连续朝向。
`python3 tools/fastplace-transform-test/run.py` 则执行真实变换后的方法，检查门禁
在所有原版副作用之前，并验证已有冷却 hook 的升级与 JVMTI 重复安装。
这些是本地回调/几何/字节码验证；Grim / Intave 服务器端结果仍待实测。

## 让位与设置

ROTATE 始终在潜行、使用物品、水平撞墙、饱食度不满足原版条件、失明、骑乘、飞行、
死亡或 GUI 打开时让位。`Scaffold` / `Scaffold2` / `Telly` / `GodBridge` / `KillAura`
开启时整体让位（HUD：`YIELD`），避免输入改写后又拿旧相机重算方向。

Scaffold 的 `Normal` 模式会完整暂停 KeepSprint（`YIELD SCAFFOLD`）：清理旧速度补偿、
输入准备和攻击补包标记，并释放 KeepSprint 启动的疾跑。只释放一次，之后玩家仍可手动疾跑。
暂停不修改模块开关或配置；关闭 Scaffold 后自动恢复，暂停期间手动关闭 KeepSprint 则保持关闭。
START、输入 hook、攻击与 END 都检查暂停，模式在 tick 中途切换时也不再补发疾跑包；
已用于移动物理的 yaw 仍保留到 C03，ClientTick END 再归还镜头。

Scaffold 自身还会在 Normal 输入 hook 按 MoveFix 前的 forward 限制疾跑：后退、
侧移与潜行不因转换为前进而保留疾跑，临时屏蔽疾跑键并清除双击计时。
这条限制在 KeepSprint 关闭时同样执行；END 或清理状态时归还物理疾跑键。

Velocity 不再凭开关阻止 KeepSprint。三个模式在实际写方向/跳跃时显式调用
`AttackReduceFeature.claimMovementInput`，静默转向实际写 yaw 时也认领。即使写入值与
原输入相同，也记录输入所有权；只比较前后数值会漏掉这种接管。KeepSprint 的回调在
Velocity 之后检查当前玩家、输入对象、连接和 tick，仅此时显示 `YIELD VELOCITY`。
新 tick、输入回调开始、END、disable/切模式/断线清理时归还，Velocity 空闲后自动恢复。
原版输入才会被替换；其它 mod 的自定义输入保留（HUD：`NO HOOK`）。

```properties
keepSprint.rotate=true
keepSprint.pauseSneak=true
keepSprint.pauseItem=true
keepSprint.speed=true
keepSprint.attackResend=true
```

ClickGUI 固定使用静默朝向路径，不再显示旋转切换或旧 OMNI 的速度选项；旧配置中的
`keepSprint.rotate=false` 会迁移为开启。`PAUSE SNEAK`、`PAUSE ITEM` 遵守原版暂停条件，
`HIT RESEND` 仍可配置。core 中的 KeepSprint 仍只负责开关，不引用 Minecraft 类。

## 保留的 OMNI 路径与先前修复

`rotate=false` 保留此前的属性补偿与不转向行为：START 按物理键预判，END 校正独立
modifier、空中因子和冲刺状态，攻击后可补 C0B。独立 modifier UUID 为
`6a0f3d9e-6d0e-4a4e-9f4a-0d8f7a4b1c21`，原版 UUID 为
`662a6b8d-da3e-4c1c-8813-96ea6097278d`。只有原版一定取消冲刺的输入才补自己的速度。

原版 MovementInputFromOptions 保留斜向原始 `(1,1)`，冲刺检查之后才归一化。
此前 START 提前归一化为 `(0.707,0.707)` 会误挂自定义 modifier，同时原版保留自己的
modifier，速度叠为 0.169；现在预判保留原始分量。OMNI 模式的完整 tick 测试覆盖该故障。

另一个先前修复：真实 Minecraft.thePlayer 类型为 EntityPlayerSP，旧 stub 的
EntityPlayer 字段描述符导致 NoSuchFieldError。事件桥接现在用 MCP/SRG 反射过滤
本地玩家，stub 已校正，打包 SRG 事件测试覆盖 START / END 与攻击。

## 验证边界

```sh
bash tools/keepsprint-test/run.sh
cmake --build build -j 4
python3 tools/attackreduce-test/check_runtime_fields.py
bash tools/scaffold-test/run.sh
bash tools/godbridge-test/run.sh
```

KeepSprint 原有测试覆盖 OMNI 决策、属性 UUID、配置、打包后的事件桥接和斜向速度。
新增 KeepSprintRotationTest 用只有 SRG 成员的 MovementInput 替身执行打包后的真实
ScaffoldMovementInput 回调。七组相机 yaw（含多圈与 ±180° 边界）、八方向间的全部切换
检查世界移动方向、单份原版速度、首 tick 冲刺、原版发送边界的 yaw 和镜头恢复；还覆盖
持续后退、跳跃输入、暂停条件、模块让位、自定义输入、切世界、disable/reset 与攻击设置。
Normal 回归还驱动 Scaffold 自身的 SRG 输入 hook 和后续原版疾跑判断，覆盖
KeepSprint 开/关、八方向、已有疾跑、按住疾跑键和待处理双击；同时检查 Simple
兼容、走路属性速度、END 与 reset 后的按键恢复。

这些替身测试验证本地状态和边界，不是实际 Minecraft / Grim 联机测试。
转向对齐使本地使用前进输入与对应 yaw，但 Simulation 是否消失仍需用新 jar 复测。
Forge 重连时 HandshakeReset / ServerHello / ModList 转 RegistryData 的 ClassCastException
是另一个握手状态机问题，本轮未改动。

`ZenAntiKBTest.keepSprintCoexistence` 以真实 Netty pipeline 串起两套 feature 回调，覆盖
三模式空闲共存、实际输入/转向让位、同值输入认领、C03 前保留 Velocity yaw、镜头恢复、
玩家/input 身份过滤、claim 清理和受击窗口结束自动恢复。
