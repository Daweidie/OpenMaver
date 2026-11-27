# Augustus Velocity 移植交接

更新：2026-11-28。本文已从“仅完成名称定位”更新为实际实现状态。

## 当前结果与边界

目标项目 `/home/zty/OpenMaver`，来源 `../OpenAugustus-Maver` 只读。17 个模式已分别实现并接入所需的包、输入、移动、碰撞和成功攻击事件，合计 20 个 Velocity 模式。不是按名称猜测，也没有套壳或占位类。

完整的**17 模式“来源方法→实现→测试”对照、公共设置、来源异常、依赖适配与限制**见 [PORT_AUDIT.md](augustus-velocity-reference/PORT_AUDIT.md)。其中记录的预测副作用、外部事件与旋转管理器差异仍然适用，不能将“17 个实现存在”扩大为 Augustus 整个运行时已逐位复刻。

- [mode-names.json](augustus-velocity-reference/mode-names.json)：17 个工厂顺序与二进制类名。
- [constructor-strings.txt](augustus-velocity-reference/constructor-strings.txt)：构造器名称/选项解密输出。
- [settings.tsv](augustus-velocity-reference/settings.tsv)：114 个标量配置项的默认值、范围、小数位、枚举。
- [source-manifest.json](augustus-velocity-reference/source-manifest.json)：来源归档与 35 个相关类的 SHA-256。

尚未做真实游戏/服务器端到端验证，也未在 Java 8 VM 运行；产品按 Java 8 编译，JAR 字节码和运行成员链接已检查。不要据本地测试宣称反作弊效果。

## 代码入口

- `mc/AttackReduceFeature.java`：20 模式注册、配置、会话、主线程调度、Netty 队列、包轴标记适配，原有模式分支保留。
- `mc/antikb/augustus/AugustusVelocity.java`：公共设置/过滤/Lag/PacketCanceling、源工具的小型适配。
- `AugustusPacketModes.java`：Basic、Spoof、Grim-1.17、HypixelTest、PolarBlock。
- `AugustusMovementModes.java`：PushGround、Push、Intave13、Reverse、SafeWalk、OldMinemen、Karhu、CustomMotion、BoundingBox。
- `AugustusInputModes.java`：Legit 与 Intave 的不同状态机；`AugustusFlag.java`：Intave/PolarFlag。
- `AugustusRange.java`：来源区间、高斯、非重复与离群采样；`AugustusPrediction.java`：IntaveSmart 的无副作用位置预测。
- `AugustusVelocityHooks.java`：会话约束、移动/碰撞/PRE/POST/攻击事件桥；攻击帧正常和异常返回均清理。
- `mc/VelocityTransformer.java`、`VelocityLoadingPlugin.java`、`velocity-manifest.mf`：mods coremod 路径；`core/jni/velocity_hooks.h`、`java/OpenMaverMcBridge.java`：动态注入时重转换已加载类。明确失败会暂停新模式并显示 HOOKS UNAVAILABLE。
- `mc/GuiOpenMaverClickGui.java`：20 模式选择，来源设置的模式/子选项可见性和滚动访问。

目录仅组织源码，以上 MC 类都沿用默认 package。不要把来源 `net/47G*`、分析 `ref/C_47G*` 或混淆 bootstrap 带进运行 JAR。

## 必须保留的兼容行为

NoXZ=0、JumpReset=1、Mix=2，默认 JumpReset 不变。模式仍保存 `velocity.zen.mode` 字符串，新增设置用 `velocity.augustus.*`，settings.version 沿用 23。模块开关、热键仍为 `movement.attackreduce`。不要重排旧索引、清空旧参数或改变旧模式的立即 S12/输入/攻击行为。

保留未提交的 Teams 修复：`mc/TeamPacketGuard.java`、`mc/LegacySoundFeature.java` 的常驻兼容 hook，以及 Velocity 回放入口；在实际主线程 scoreboard 上过滤不存在队伍的失效操作，创建包和有效操作放行。Velocity 关闭时兼容 hook 仍有效。PacketCanceling 不包含 Teams。

网络/生命周期边界：Netty 只转交任务和写出，游戏状态/模式队列在 MC 主线程；每队列 4096 上限；ChannelPromise 必须完成；同连接 S01/S07 世界切换后的已捕获后续包继续处理，旧连接数据不能进入新会话。禁用与切模式释放，断线丢弃并失败对应 promise。S08 对新模式需要保留来源的观察与状态处理。

## 不能“修正”的来源特性

- Reverse 只注册更新/PRE，未注册其收包缩放方法；Strength 设置保留但不生效。
- HypixelTest 取消后的垂直判断 `Y<.2 && Y>.41995` 不可满足。
- Intave WaterInterval 只在模运算匹配时自增；interval=2 首包后停在计数 1。
- OldMinemen、PolarBlock 没有模式公共过滤；PolarBlock 还观察其他实体的 S12。
- CustomMotion 启用即武装；BoundingBox 是 `(int)posY+1`；跳跃 eligible 使用严格大于。
- Flag 排除私有 no-op 标记 47vS，不能错认成 C03。
- 攻击计数 47Cc.42 跨 tick 保留，在模块/会话初始化清零。

## 明确的适配限制

IntaveSmart 用已经接受的真实攻击结果和纯快照替代来源预测中再次调用伤害/攻击事件的副作用；未移入 Augustus 外部预测/MoveFlying listener 体系。方向与预测读取 Maver 当前有效玩家 yaw，未移入 Augustus 的独立旋转管理器。NotWhileLongJump 有设置及模块查询，但 Maver 当前没有 LongJump 模块。这些限制见审计文档，不应隐去。

来源 S12/S27 的自定义轴标记用包装包和原版 handler 前后运动快照适配；完成后的速度相同，但外部 mod 在 handler 中间读取速度的可见时序不能保证完全一致。

## 验证与产物

最终构建/验证命令及各项计数以 [PORT_AUDIT.md](augustus-velocity-reference/PORT_AUDIT.md) 为准。关键结果：旧模式/Teams 5,125、新模式 4,581、真实类/ASM/JVMTI 44、ClickGUI 150、启动 38，均通过；KeepSprint/Scaffold/GodBridge/SafeWalk/MoveDisabler 回归通过。两个 JAR 的真实字段/方法及 Java 8 字节码检查通过。

```sh
cmake --build build -j2
bash tools/attackreduce-test/run.sh
python3 tools/augustus-velocity/test_transform.py
python3 tools/augustus-velocity/check_runtime.py
bash tools/clickgui-test/run.sh
bash tools/startup-test/run.sh
git diff --check
```

会重建共享 build/mc-classes 的测试脚本需串行，不要与变换测试同时构建。分析工具复现命令见审计文档，不依赖 /tmp 下的历史输出。

交付：`build/openmaver-b2.2.0-2026-11-28.jar`（Forge mods）、`build/openmaver-mc.jar`（注入），native 为 `build/lib/libopenmaver_core.so`。未提交、推送或发布。后续继续修改时先看 git status/diff，保留整个未提交工作树。
