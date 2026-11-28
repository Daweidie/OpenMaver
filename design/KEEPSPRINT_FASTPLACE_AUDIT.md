# KeepSprint / FastPlace 放置朝向冲突审计

日期：2026-11-28。范围：解释用户提供的 Grim `RotationPlace` 与 Intave
`InteractionRaytrace` / `rotation snapped suspiciously while scaffolding` 日志。
下列审计记录保留修复前基线；文末记录随后实施的交接修复。源码行号对应审计基线。

## VERIFIED：源码与本地复现

审计基线：

| 仓库 | commit |
| --- | --- |
| OpenMaver | `1555795dbfbfdf58d2327d4d6a13275d6017e6b2` |
| references/anticheats/Grim | `f319e165e100205989a442ad7c9f9fa10a504cb0` |
| references/anticheats/Intave | `d01b2e051d4ed843d5d215501f97a5e72f7cf203` |

**KeepSprint 上报移动 yaw，原版放置选择的是相机准星下的方块。FastPlace 仅缩短
原版冷却，两个方向没有统一。** 本地打包后的 SRG 回调复现了这一冲突。

`mc/KeepSprintFeature.java:379` 的公式为：

```java
return viewYaw - (float) Math.toDegrees(Math.atan2(strafe, forward));
```

`afterMovementInput()` 在 `:410` 写入这个 yaw，然后把输入改成 `(1, 0)` 并启动疾跑。
`onPlayerTickEnd()` 的 `:262` 保留 yaw 给原版移动包；`onTickEnd()` / `restoreCameraView()`
在 `:420` / `:433` 才恢复本地镜头。

| 物理输入 | 移动 yaw 相对相机 yaw |
| --- | --- |
| W | 0° |
| S | −180° |
| A / D | −90° / +90° |
| W+A / W+D | −45° / +45° |
| S+A / S+D | −135° / +135° |

`mc/FastPlaceFeature.java:20` 只返回新的 cooldown；
`mc/FastPlaceTransformer.java:35` 只替换 `rightClickDelayTimer` 的赋值。
FastPlace 沿用原版 `objectMouseOver`，没有重新选择服务端移动 yaw 所指的方块。

还直接核对了本机 Minecraft 1.8.9 客户端 jar 的 `javap -c -p` 输出：

```text
ave public void s();
53: invokevirtual #1848               // Method bfk.a:(F)V
1785: invokespecial #2054               // Method ax:()V
1842: invokespecial #2054               // Method ax:()V
1927: invokevirtual #2061               // Method bdb.h:(Lpk;)V
bew public void t_();
28: invokespecial #129                // Method bet.t_:()V
104: invokevirtual #170                // Method p:()V
```

Forge 映射分别为 `runTick`、`getMouseOver`、`rightClickMouse`、`updateEntity`、
`EntityPlayerSP.onUpdate`、父类更新与 `onUpdateWalkingPlayer`。因此关键路径为：

```text
相机 yaw 下 getMouseOver
  → 原版右键按该命中发 C08
  → 玩家输入回调：KeepSprint 写移动 yaw
  → 物理使用移动 yaw
  → 原版 C03 族包使用移动 yaw
  → ClientTick END 恢复本地相机
```

例如相机朝向 0°、持续按 S：客户端点击相机前方的支撑块，而服务端当前朝向和随后
上报的移动朝向都是 −180°。恢复镜头本身没有发送相机朝向给服务端。
稳定后退时两次移动包可保持同一个 yaw；这里没有声称每 tick 都往返发送两个 180° 转头。

## VERIFIED：Grim 两条日志的判据

源码：`references/anticheats/Grim/common/src/main/java/ac/grim/grimac/`。

* `events/packets/CheckManagerListener.java:98`：`handleQueuedPlaces()` 在后续移动包到达时
  处理排队的放置。`:125` 对 1.8 且 `hasLook` 的包使用这条移动包的 yaw/pitch。
  没有 look 时保留已有朝向；这也没有把本地相机恢复值送到服务端。
* `checks/impl/scaffolding/RotationPlace.java:83`：针对 C08 点击的支撑方块做射线检查。
  1.8 候选是 `(yaw, pitch)` 与 `(lastYaw, pitch)`，另有眼高与眼睛位于块内的容差。
* 同文件 `:69`：post 射线失败后设置 `flagBuffer = 1` 并产生 `post-flying`。
* 同文件 `:43`：buffer 已非零且下一次即时检查仍失败，产生 `pre-flying`，满足配置
  条件时调用 `place.resync()`。`ignorePost` 用来避免同次放置重复记录。

`post-flying` / `pre-flying` 是检查阶段标签，不足以单独证明客户端把 C08 发错了顺序。
本例已经存在更直接的方向冲突。首次从相机 yaw 切换到移动 yaw 时，`lastYaw` 可能
暂时掩盖问题。特别是 `CheckManagerListener.java:768` 只在 `hasLook` 时推进
`lastYaw`：一直不动视角的无 look 移动包可以继续保留旧相机 yaw，持续后退本身不保证
立即消耗这个回退。后续即使只有 pitch 轻微变化，原版也会发送带 look 的包，
`lastYaw` 就推进到已经偏转的服务端 yaw，当前与历史候选都可能指向相机反方向。

## VERIFIED：Intave 射线错误与 180° 跳转是两条检查

源码：`references/anticheats/Intave/src/main/java/de/jpx3/intave/`。

`check/world/InteractionRaytrace.java:507` 的预检查尝试服务器保存的当前/历史 yaw，
以及位置偏移。排队后的 `receiveMovement()` 在 `:442` 使用上一位置、当前 yaw/pitch，
并构造历史 yaw 候选；`processInteraction()` 从 `:668` 再验证，允许额外位置/姿态容差。
这些候选都没有客户端私有的相机 yaw。

`:1047` 的分支明确为：射线实际命中的方块坐标与 C08 点击的支撑块不同，
`vl = 2.5`，消息 `looking at ... block`。两块材质相同就在 `:1049` 添加
`a different`。用户日志的 `wool block on wool block, looking at grass block` 与
`wool block on grass block, looking at a different grass block` 都落在这条分支。
它表示目标坐标不一致，而非放置间隔检查；同材质也不等于同一方块。

`check/combat/heuristics/combatpatterns/rotation/RotationSnapHeuristic.java`：

* `:137` 计算服务端相邻 yaw 的差值。
* `:140` 开始检查突然转向时的推断输入变化，`:150` 判断 `SILENTMOVE`。
* `:167` / `:168` 的 lite 分支要求三次 yaw 运动满足 `0, >25, <9`，同时历史状态为
  `SILENTMOVE`、旋转包计数 `>10`、距传送 `>7` tick。
* `:232` 至 `:235` 才输出用户看到的 `rotation snapped suspiciously while scaffolding`，
  并把中间那次跳转显示为 `yaw: ...`。普通 snap 分支还有近期挥手/攻击等条件，
  而且会抢先处理并清掉 `liteFlag`。

KeepSprint 在方向切换或让位/恢复时直接写计算结果，没有逐步转向。S 相对相机正好
产生 180° 差值；这与日志数值吻合。单独看到 180° 并不等于已验证全部 heuristic 前提。
该检查中的 scaffolding 是行为标签，未读取本客户端 Scaffold 模块开关。

## VERIFIED：为何关闭 Scaffold 后出现，以及为何设置没有解决冲突

`mc/KeepSprintFeature.java:360` 的旋转拥有者包含 Scaffold 等模块，缺少手动放置/
FastPlace。`mc/OpenMaverFeatures.java:2250` 则让 FastPlace 在 Scaffold 等模块开启时暂停。
因此关闭 Scaffold 会同时允许 KeepSprint 转向和 FastPlace 冷却修改恢复。
复现也覆盖了这个模块切换。

`canSprint()` 在 `mc/KeepSprintFeature.java:369` 检查 `isUsingItem()`，没有检查右键
放置请求或当 tick 已发生的放置。该标记主要覆盖持续使用物品；普通方块右键缺少对应
让位协议。另一个细节是 `load()` / `setRotate()` 在 `:92` / `:123` 固定 `rotate=true`，
旧配置 `keepSprint.rotate=false` 实际不生效。

改变 FastPlace 的 0–5t 仅改变冷却，方向差仍存在；FastPlace 关闭后原版右键也仍可能
遇到同一个方向冲突。单独增加延迟、提前恢复渲染镜头，均未建立目标与服务端 yaw 的一致性。

## CANDIDATE：现场归因与实现边界

用户日志与上述确定的模块冲突、Grim buffer 状态转移及 Intave 消息分支吻合。
Scaffold 关闭后的恢复路径尤其符合现象。完整修复需要一个跨放置与移动的旋转所有权协议：
C08 点击的支撑块/面应当与服务端允许的射线一致，且随后用于校验的移动朝向继续一致。
同时，KeepSprint 的移动物理依赖移动 yaw，修改它需要同步处理输入与疾跑状态。
这是最初诊断的实现边界；后续修复见文末。

## UNRESOLVED：现场仍缺少的证据

* 现场服务器加载的 Grim / Intave 二进制是否对应本文 reference commit。
* 16:00:13 / 16:00:42 的 C08 目标/面、前后 C03 朝向与位置、其他旋转拥有者状态。
* Intave 当时的推断按键、yawMotions、SILENTMOVE、旋转包计数与传送计时。

离线程序执行真实打包后的 KeepSprint / FastPlace 回调，射线部分使用明确的 ray/AABB
模型，并建模原版 look 标记与 Grim 的 hasLook 历史更新。它覆盖固定角度时旧 yaw 回退
仍有效，以及 pitch 从 60° 轻微变为 60.25° 后两个 yaw 候选同时失配。
程序不启动完整 Minecraft/Grim/Intave，也不重放用户那一局的数据包。

## VERIFIED：修复前复现命令与输出（历史回执）

```sh
bash tools/keepsprint-fastplace-audit/run.sh
```

下列输出来自修复前运行，退出码 0；当前同一入口已改为修复回归。实际输出切片：

```text
[placement-audit] delay=0t camera=0.0 previous=-180.0 next=-180.0 has-look=true camera-hit=true reported-hits=false/false
[placement-audit] delay=1t camera=0.0 previous=-180.0 next=-180.0 has-look=true camera-hit=true reported-hits=false/false
[placement-audit] delay=2t camera=0.0 previous=-180.0 next=-180.0 has-look=true camera-hit=true reported-hits=false/false
[placement-audit] delay=3t camera=0.0 previous=-180.0 next=-180.0 has-look=true camera-hit=true reported-hits=false/false
[placement-audit] delay=4t camera=0.0 previous=-180.0 next=-180.0 has-look=true camera-hit=true reported-hits=false/false
[placement-audit] delay=5t camera=0.0 previous=-180.0 next=-180.0 has-look=true camera-hit=true reported-hits=false/false
```

```text
[placement-audit] FastPlace OFF: cooldown=4t, backward yaw mismatch persists
```

```text
[placement-audit] Scaffold OFF: FastPlace resumes and KeepSprint reports backward yaw again
[placement-audit] 160 checks passed (offline callbacks + ray/AABB model)
```

冷却行为与真实 1.8.9 字节码钩子的原有回归也通过，命令与实际输出切片如下，
两项退出码均为 0：

```sh
bash tools/fastplace-test/run.sh
```

```text
[fastplace-test] 60 checks passed
```

```sh
python3 tools/fastplace-transform-test/run.py
```

```text
[fastplace-transform] 442 checks passed
```

诊断阶段新增：本文、`tools/keepsprint-fastplace-audit/run.sh`、
`tools/keepsprint-fastplace-audit/KeepSprintFastPlaceAudit.java`。


## 修复：跨右键与原生移动的旋转交接

变更位于 `mc/KeepSprintPlacement.java`、`mc/KeepSprintFeature.java`、
`mc/FastPlaceTransformer.java`、`mc/FastPlaceFeature.java`。
原版右键入口在任何副作用前等待朝向对齐；每 tick 最多改变 20° 的隐藏偏移，
使用八方向离散输入与对应的原版冲刺状态；放置前后的原生移动保持相机朝向。
短按只重试一次且重新取原版准星命中，按住输入继续走原版间隔。
重试在玩家/世界、物品/槽位和界面/拥有者变化时取消。

后退放置时采用原版步行，松开后平滑恢复 KeepSprint。首次从后退移动交接约需
9 tick；冷却选项没有被改成强制限速。原始 yaw 偏移一直走到精确的目标值再交回
原版，避免跨 ±180° 和回到 idle 时的数值跳变。原生 C03、移动物理、pitch、镜头恢复
仍由现有路径处理，没有额外移动包或 motion 写入。

当前回归脚本替换了修复前的 bug 确认断言：获准的放置要求 pre/post 几何命中，
检查 pitch-only look 后的两个历史方向、0–5t 间隔、八方向 x 七组镜头角度、
真实 KeyBinding 输入、短按和队列去重、取消边界，以及返回中停止移动的原始 yaw。
完整交接说明见 `design/KEEPSPRINT_HANDOFF.md`。

UNRESOLVED：没有启动完整 Minecraft / Grim / Intave 进行联机复测；本地测试通过
只证明相应回调、几何模型和字节码边界，现场告警结果仍需用构建产物复测。

## VERIFIED：修复后回执

以下命令实际执行，退出码均为 0。输出取自对应 build 日志。

```sh
bash tools/keepsprint-fastplace-audit/run.sh
```

```text
[placement-regression] 97854 checks passed (packaged SRG callbacks + ray/AABB model)
```

```sh
python3 tools/fastplace-transform-test/run.py
```

```text
[fastplace-transform] 499 checks passed
```

```sh
bash tools/keepsprint-test/run.sh
```

```text
KeepSprint rotation SRG: 13594 checks passed
```

```sh
bash tools/fastplace-test/run.sh
```

```text
[fastplace-test] 60 checks passed
```

```sh
bash tools/attackreduce-test/run.sh
```

```text
ZenAntiKBTest: 5125 passed, 0 failed
AugustusVelocityTest: 4581 passed, 0 failed
[runtime-fields] PASS build/openmaver-mc.jar: 39 field references match Minecraft 1.8.9 + Forge
[runtime-fields] PASS build/openmaver-b2.2.0-2026-11-28.jar: 39 field references match Minecraft 1.8.9 + Forge
```

```sh
bash tools/scaffold-test/run.sh
```

```text
checks: 94  passed: 94  failed: 0
```

```sh
bash tools/godbridge-test/run.sh
```

```text
checks: 73  passed: 73  failed: 0
```

```sh
cmake --build build -j 4
```

```text
-- Packed /home/zty/OpenMaver/build/openmaver-b2.2.0-2026-11-28.jar
[100%] Built target openmaver_mod
```

ROLLBACK：以下脚本将 `build/keepsprint-placement-backup/` 下九份 `.bak` 恢复到
对应源路径并删除新增 `mc/KeepSprintPlacement.java`，回到实施修复前的诊断状态；
随后重建产物。脚本路径与备份属于当前工作区构建目录。

```sh
python3 build/keepsprint-placement-backup/rollback.py
cmake --build build -j 4
```
