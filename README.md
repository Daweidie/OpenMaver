# OpenMaver V2.0

私人服务器反作弊对测研究用的客户端骨架，适配 **Minecraft 1.8.9**（Forge 11.15.1.2318）。

> 说明：本仓库包含**基础设施骨架**（JNI 桥接、配置、日志、模块基类、Forge 事件桥接）
> 与基础功能模块（Aim Assist、Player ESP、AutoSprint、AutoClicker、Reach、Scaffold、GodBridge、FastPlace、FakePlayer、Target HUD、Player X-Ray）
> 以及 `legit / balanced / aggressive` 三档 Grim 对测 profile。
> 请仅在你拥有或获得明确授权的服务器上进行测试。

## 技术栈

- C++17（关闭异常与 RTTI：`-fno-exceptions -fno-rtti`）
- Java 8（编译目标 `--release 8`，字节码 major 52，兼容 MC 1.8.9 的 JRE）
- JNI 桥接
- JSONC 配置（支持 `//` 与 `/* */` 注释）
- CMake 构建
- Minecraft 1.8.9 / Forge 11.15.1.2318（可选集成）

## 目录结构

```
OpenMaver/
├── core/                          # 本地核心库（C++）
│   ├── jni/
│   │   ├── bridge.cpp             # JNI 导出实现
│   │   ├── bridge.h               # 接口声明
│   │   └── agent.cpp              # JVMTI agent 入口（Agent_OnAttach，运行期注入用）
│   └── CMakeLists.txt
├── java/                          # Java 核心（与 MC 无关，可独立运行）
│   ├── OpenMaver.java             # 入口 / 核心门面（start/stop + 独立驱动线程）
│   ├── config/Config.java         # JSONC 配置系统（含全局默认实例）
│   ├── util/Logger.java           # 日志系统
│   └── module/
│       ├── Module.java            # 模块抽象基类（按包名约定推导配置路径）
│       ├── ModuleManager.java     # 模块注册表 + 统一驱动
│       ├── movement/AutoSprint.java  # 示例模块（链路验证器）
│       ├── movement/Scaffold.java  # 自动搭路开关（MC 侧逻辑在 OpenMaverFeatures）
│       ├── movement/GodBridge.java # 神桥开关（MC 侧逻辑在 OpenMaverFeatures）
│       ├── player/FastPlace.java  # 快速右键开关（MC 侧逻辑在 OpenMaverFeatures）
│       ├── render/TargetHud.java  # 目标信息面板开关（MC 侧绘制在 mc/TargetHud）
│       └── render/PlayerXray.java # 玩家透视开关（MC 侧绘制在 OpenMaverFeatures）
│       └── misc/FakePlayer.java   # 假人玩家开关（MC 侧逻辑在 OpenMaverFeatures）
├── inject/                        # 运行期注入器（C++，可选）
│   └── injector.cpp               # 自研注入器：--re-self / --re-jattach
├── mc/                            # Forge 1.8.9 集成（源码为 MCP 名，构建时 reobf 成 SRG）
│   ├── mcmod.info                 # Forge mod 元数据（打包进 mod jar）
│   ├── OpenMaverMod.java          # @Mod 入口
│   ├── OpenMaverClientHandler.java# 客户端 tick / 渲染事件桥接 + 进入世界启动
│   ├── OpenMaverHud.java          # 右侧 ModuleList（胶囊列表 + 键位 + 启用动效）
│   └── GuiOpenMaverClickGui.java  # 游戏内 ClickGUI（分类 / 搜索 / 展开设置）
├── mcstubs/                       # 编译 mc/ 用的 API stub（仅编译期，不进 jar）
├── tools/McpReobf.java            # MCP -> SRG reobf（Forge 1.8.9 运行期需要）
├── config/default.jsonc           # 默认配置
└── CMakeLists.txt
```

> **关于包名**：核心门面 `OpenMaver` 沿用项目既有结构位于**默认包**。
> Java 语言规范不允许具名包引用默认包，因此 `mc/` 下的 Forge 类同样放在默认包，
> 以便直接调用 `OpenMaver.start()/tick()/render()/stop()`。

## 构建

需要 CMake ≥ 3.16、支持 C++17 的编译器、以及 JDK（含 `JNI` 开发头文件）。

### 1) 仅核心（可独立运行，默认）

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

产物：

- 本地库：`build/lib/libopenmaver_core.so`（同时是 JVMTI agent，导出 `Agent_OnAttach`）
- Java 类：`build/java-classes/`（Java 8 字节码）
- 核心类 jar：`build/openmaver-core.jar`（供运行期注入的 agent 加入 bootstrap 搜索路径）
- 自研注入器：`build/bin/openmaver_injector`（仅 UNIX）
- 游戏内 UI jar：`build/openmaver-mc.jar`（**已 reobf 成 SRG**，供运行期注入用）
- mod jar：`build/openmaver-mod.jar`（完整 mod：UI 类 + 核心类 + `mcmod.info` + 内置 `.so`）

### 2) 编译用 API：默认内置 stub，也可指向真实 Forge

`mc/` 源码用 **MCP 名**（`getMinecraft`/`fontRendererObj`）写，Forge 1.8.9 运行期用
**SRG 名**（`func_71410_x`/`field_71466_p`）。构建两步：

```
mc/*.java --javac(对 MCP API)--> mc-classes --McpReobf(MCP->SRG)--> mc-classes-reobf
```

- 编译用的 MCP API：默认是仓库内置的 `mcstubs/`（**无需任何 Forge/MCP 开发环境**）。
  想对着真实 Forge 编译就传：`-DOPENMAVER_MC_CLASSPATH="/绝对路径/forge-universal.jar"`。
- reobf 由 `tools/McpReobf.java` 完成（纯 JDK，映射表内置于该文件；用到新 MC API 时补一条即可）。

> **为什么必须 reobf**：FML 加载时把游戏类从混淆名还原成 SRG 名
> （`ave`→`net.minecraft.client.Minecraft`、`A`→`func_71410_x`），所以注入/加载的类必须用 SRG 名。
> 直接拿 javac 产物（MCP 名）注入会 `NoSuchMethodError`。

`mc/` 依赖的 API（均为 1.8.9 已确认的签名）：

| 用途 | 类型 |
|---|---|
| mod 入口 | `net.minecraftforge.fml.common.Mod`（含 `clientSideOnly` / `acceptedMinecraftVersions`） |
| 生命周期 | `FMLPreInitializationEvent`、`FMLInitializationEvent` |
| 事件总线 | `net.minecraftforge.common.MinecraftForge.EVENT_BUS` |
| 事件注解 | `net.minecraftforge.fml.common.eventhandler.SubscribeEvent` |
| 客户端 tick | `net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent` |
| HUD 覆盖层 | `net.minecraftforge.client.event.RenderGameOverlayEvent.Post` |
| 世界渲染 | `net.minecraftforge.client.event.RenderWorldLastEvent` |
| 客户端判定 | `net.minecraftforge.fml.relauncher.SideOnly` / `Side` |

> 1.8.9 中 `FMLCommonHandler.instance().bus()` 已废弃并返回 `MinecraftForge.EVENT_BUS`
> （源码注释：*"they're the same thing now"*），因此 tick 与渲染事件统一注册到 `EVENT_BUS`。

### 3) 安装到 Forge 1.8.9

1. **装 Forge**：用官方安装器给 1.8.9 装 Forge（如 `1.8.9-forge1.8.9-11.15.1.2318-1.8.9`）。
   PrismLauncher 用户：右键 `1.8.9` 实例 → *Edit* → *Version* → *Install Forge* → 选 `11.15.1.2318`。
2. 用上面的命令构建出 **`build/openmaver-mod.jar`**。
3. 把 `openmaver-mod.jar` 放进该实例的 `mods/` 目录（`~/.minecraft/mods/` 或 Prism 实例的 `minecraft/mods/`）。
4. 启动游戏 → 进入世界，右下角会出现「Loaded successfully, x features enabled!」。

> 用 PrismLauncher 的 vanilla 实例直接实现不了——`mc/` 的事件（`TickEvent`、`RenderGameOverlayEvent` 等）
> 全部来自 Forge，必须先给该实例装上 Forge。

## 运行

### 独立启动 Minecraft

项目可以直接启动本机已安装的 Forge 1.8.9，不会打开 Prism 启动器：

```bash
./launch-openmaver.sh              # mod 模式（默认）
./launch-openmaver.sh --mod        # 显式 mod 模式
./launch-openmaver.sh --inject     # 启动后自动运行期注入
```

两种模式互斥，避免同一实例里同时从 mod jar 和注入 jar 各加载一套 `OpenMaver` 类：

- `--mod`：把 `build/openmaver-mod.jar` 安装到 `minecraft/mods/OpenMaver.jar`，
  由 Forge 在启动时加载。
- `--inject`：把已有的 `mods/OpenMaver.jar` 重命名为 `OpenMaver.jar.disabled`，
  不安装 mod；启动 Forge 后等待资源加载完成，再用 `openmaver-core.jar` +
  `openmaver-mc.jar` 注入，并通过 Forge 事件总线注册 HUD / ClickGUI。

脚本先构建项目，再从 Prism 的共享缓存读取 Java、Minecraft/Forge 依赖和活动账号会话。
默认实例为 `1.8.9(1)`；可用 `OPENMAVER_INSTANCE` 指定实例名，或用
`OPENMAVER_INSTANCE_DIR` 和 `PRISM_ROOT` 指定自定义目录。账号会话沿用该机器已缓存的
有效游戏令牌；没有活动账号时可设置 `OPENMAVER_USERNAME` 以离线身份进入仅允许离线账号的服务器。

仅检查启动参数和本地依赖，不打开游戏：`python3 launch-openmaver.py --dry-run --inject`。

### 终端设置

```bash
./openmaver-tui.py
```

按 `1` 或 `2` 切换功能，设置会立即写入 Prism 实例的 `minecraft/config/openmaver.properties`，
运行中的游戏会在约一秒内读到更新。自定义实例可通过 `OPENMAVER_INSTANCE_DIR` 指定。

### 独立模式

```bash
java -Djava.library.path=build/lib \
     -Dopenmaver.config=config/default.jsonc \
     -cp build/java-classes OpenMaver
```

预期输出（控制台与 `./logs/openmaver.log` 同时可见）：

```
[OpenMaver] Core initialized
[2026-... ] [INFO] [OpenMaver] 版本 2.0.0 启动完成
[2026-... ] [INFO] [OpenMaver] 配置加载完成: .../config/default.jsonc
[2026-... ] [INFO] [OpenMaver] 独立运行模式：未检测到游戏主循环
[OpenMaver] Core shutdown
[2026-... ] [INFO] [OpenMaver] 已退出
```

### Minecraft 1.8.9 中

装上 Forge 1.8.9 后，把构建好的 **`build/openmaver-mod.jar`** 放入 `mods/` 即可
（本地库已随 jar 分发，**无需**再配 `-Djava.library.path`；安装步骤见上一节）。

> 开发调试时也可以不打包，直接让 JVM 找到本地库，在启动器 JVM 参数中加：
> `-Djava.library.path=/绝对路径/到/libopenmaver_core.so/所在目录`

生命周期与事件接线：

| 时机 | 调用 |
|---|---|
| Forge `preInit` | 注册 JVM 退出钩子（`OpenMaver.stop()`） |
| 首次进入世界（`ClientTickEvent`） | `OpenMaver.start()` + 右下角弹「Loaded successfully, x features enabled!」 |
| `TickEvent.ClientTickEvent` (END) | `ModuleManager.onTick()` |
| `RenderWorldLastEvent` | `ModuleManager.onRender()` |
| `RenderGameOverlayEvent.Post` | `OpenMaverHud` 绘制右侧 ModuleList + 加载提示 |
| RSHIFT（在游戏内） | 开关 `GuiOpenMaverClickGui` |
| 模块绑定键（如 `V`） | 切换对应模块的启用状态 |

配置文件默认读取游戏工作目录下的 `config/default.jsonc`
（即 `.minecraft/config/default.jsonc`），可用系统属性覆盖：
`-Dopenmaver.config=/绝对路径/config.jsonc`。

> 本节描述的「把原生库放进 jar、首次运行解压再 `System.load`」已经实现（见
> `OpenMaver` 的 `loadBundledNativeCore`）：`build/openmaver-mod.jar` 里就带着
> `native/<os>/<arch>/libopenmaver_core.so`，开箱即用。

## 游戏内 UI（进入世界后）

按需求，OpenMaver 的核心**延迟到玩家真正进入世界**时才启动，并给出可视反馈：

- **ModuleList** `mc/OpenMaverHud.java`：屏幕右侧垂直堆叠的**已启用功能**列表，
  顶部固定显示 Maver Logo。每行是透明白色圆角底板，右边缘严格对齐，宽度随文字
  长短变化；模块名使用深色彩虹缓慢流动 + 呼吸式闪烁，右侧键位以同色弱化显示；
  禁用功能不会出现在列表中。每行拥有独立动画状态：启用时从右侧平滑淡入，
  禁用时先淡出再移除，剩余行平滑上移换位。所有动画基于 `System.nanoTime()`，与帧率无关。
- **Target HUD** `mc/TargetHud.java`：准星右侧的小卡片。**只有准星真正命中
  `EntityLivingBase` 时才显示**，移开准星立即隐藏；只保留正方形头像（玩家真实皮肤 /
  无头像时使用 Steve）、名称与细血条，血条颜色随血量从绿到橙再到深红渐变，
  无头像外圈。
- **Player X-Ray** `mc/OpenMaverFeatures.renderPlayerXray`：玩家透视。只扫描
  `EntityPlayer`，关闭深度测试后通过游戏自身的 `RenderManager` 渲染完整皮肤模型，
  让玩家皮肤、装备和手持物品穿墙可见；模型外侧再绘制 1.5px 描边。
  描边颜色默认按目标血量（绿 / 琥珀 / 红）变化，也可固定为橙 / 青 / 紫 / 红。
- **右下角提示**：进入世界时弹出「Loaded successfully, x features enabled!」提示，
  纯灰色背景 + 底部白色倒计时进度条，从屏幕右侧滑入、停留约 2.4 秒后
  先渐变为红色，再滑出淡出；连续触发时多张提示会以**卡片栈**形式从下往上叠加，
  每张卡片拥有独立时间线，旧卡片退场后其余卡片平滑补位。
  游戏内启用 / 禁用功能时也会弹出「xxx enabled / xxx disabled」。
  绘制在 `RenderGameOverlayEvent.Post`（`type == ALL`）里，ClickGUI 内也会同步显示。
- **游戏内 ClickGUI** `mc/GuiOpenMaverClickGui.java`：游戏内按 **RSHIFT** 开关。
  纯灰色面板 + 中性灰描边，左侧 Combat / Movement / Render / Player / Misc 分类栏，
  以及 SYSTEM → Configs / Statistics / Settings 页面（配置概览、模块统计与客户端设置），
  顶部搜索（`Ctrl+K`）、ALL / ENABLED / DISABLED 筛选与排序；点击行展开设置在
  `Gui.drawRect` 自绘的滑块 / 下拉 / 复选框中直接调参。分类、SYSTEM 入口、筛选页签、
  排序、模块行、搜索框、开关与全部设置控件都有统一的悬停 / 激活 / 按压过渡，
  滚动条与列表内容共用同一条平滑滚动值。模块列表来自
  `ModuleManager`，Aim Assist、Player ESP、Grim Bypass、AutoSprint、AutoClicker、Reach、
  FastPlace、Target HUD 与 Player X-Ray 的设置直接绑定 `OpenMaverFeatures`，参数写入
  `config/openmaver.properties`（拖拽结束与关闭 GUI 时落盘）。
- **模块键位**：`config/default.jsonc` 中的 `<分类>.<模块>.key`（如
  `movement.autosprint.key = "V"`）既会显示在 HUD 右侧，也会在游戏内绑定到该键。

HUD 与 ClickGUI 都在 `OpenMaverMod.init()` 里注册到 `MinecraftForge.EVENT_BUS`；
进入世界由 `OpenMaverClientHandler.onClientTick()` 首次检测到 `theWorld/thePlayer`
非空时触发：

```java
if (!worldEntered) {
    worldEntered = true;
    if (!OpenMaver.isStarted()) OpenMaver.start(); // 进入世界才启动核心
    OpenMaverHud.success("Loaded successfully, "
            + OpenMaverHud.enabledFeatureCount() + " features enabled!");
}
```

> 说明：HUD / ClickGUI 依赖 `net.minecraft.*`，由 Forge 的类加载器加载。
> 除了随 mod 加载，**纯运行期注入**也能显示（见下面「注入也能显示 UI」一节）。

## 功能模块

模块开关与键位来自 `config/default.jsonc`，模块参数来自
`config/openmaver.properties`（ClickGUI 中修改后自动落盘，重启保留）。

### Grim 低检测面 profile（`bypass.*`）

所有战斗/搭路模块共享一个 profile，默认 **BALANCED**，可在 ClickGUI 的
**Misc → Grim Bypass** 中切换，也可直接编辑 `config/openmaver.properties`：

```properties
bypass.enabled=true
bypass.profile=balanced     # legit | balanced | aggressive
debug.enabled=false         # true 时写入 logs/openmaver-debug.log
```

| profile | Reach 实际上限 | Aim 单 tick 上限 | AutoClicker | FastPlace / GodBridge fastPlace | Placement rotation |
|---|---|---|---|---|---|
| `legit` | 3.00（Grim 扩张箱） | yaw 18° / pitch 12° | ≤8 CPS，强制抖动 | 完全不改 `rightClickDelayTimer` | 仅真实准星 |
| `balanced`（默认） | 3.00（Grim 扩张箱） | yaw 35° / pitch 20° | ≤12 CPS，抖动+停顿 | FastPlace 最低 2 tick；GodBridge 不压 0 | 仅真实准星 |
| `aggressive` | ≤3.30（仅对比测试） | 上限放宽 | ≤20 CPS | 允许压到 0 | 允许 auto-rotate / packetRotation |

> `bypass.enabled=false` 会恢复旧的对比行为（Reach 最高 5.9），**不要**在正式
> 对测中关闭。`aggressive` 会主动越过 Grim 的 Reach 阈值，只用于记录 flag 差异。

**阈值依据（Grim 2.0，commit `f9b801a`）**

- `combat/Reach`：1.8 客户端 `maxReach = 3.0`；目标 AABB 先扩张
  `Reach.threshold(0.0005) + 1.8 hitbox(0.1) [+ movementThreshold(0.03)]`。
  因此安全实现是“眼睛到扩张箱 ≤ 3.0”，折算回真实命中箱约 3.13 格。
- `combat/Hitboxes`：Reach 的射线完全没命中 AABB 时触发。
- `aim/AimDuplicateLook`：重复相同 rotation 会 flag；本项目在没有实际变化时不写
  rotation 字段。
- `aim/AimModulo360`：`|deltaYaw| > 320` 会 flag；所有自动 yaw 都 wrap 到
  `[-180, 180)`，并且单 tick 限速。
- `scaffolding/RotationPlace`：C08 使用服务端已收到的 yaw/pitch 做 ray trace；
  因此 `legit/balanced` 下自动搭路只使用真实准星，不再“只改客户端视角”。
- `timer/Timer`：每个 tick 包固定按 50ms 计费；本项目在安全 profile 下不额外发
  `C05PacketPlayerLook`。
- `multiactions/*`：使用物品时攻击/挥击、同 tick 方块+实体交互、移动中开背包都会
  flag；AutoClicker/Reach 已加入互斥与使用物品保护。

**观测**：`debug.enabled=true` 时记录 aim rotation 变化、C02 攻击、
C08 放置、点击间隔、被抑制的 C05，以及每 100 tick 的统计。默认关闭，
IO 失败只记录一次并自动停用 debug。

完整审计、Grim commit、checks.yml 对照项与对测记录模板见
`design/GRIM_BYPASS_HANDOFF.md`。

### GodBridge 神桥

- 配置路径：`movement.godbridge`，默认关闭，默认键位 **B**。
- 使用方式：
  1. 手持可放置方块，按住 **Shift** 蹲在方块边缘；
  2. 视角瞄准方块边缘，并按住 **D**（右平移）；
  3. 松开 **Shift** 立即开始神桥，模块会按当前移动方向在脚下/前方铺路；
  4. 神桥期间可以正常跳跃，或用 **A/D** 改变铺设方向；再次按下 **Shift** 停止。
- 触发校验：蹲下、地面站立、按住 D、视线命中方块边缘（可在 ClickGUI 关闭
  `REQUIRE EDGE AIM` 放宽为“瞄准任意方块”）。
- 默认 `LEGIT AIM` 开启：模块不会自动转视角，只会在你真实准星已经命中
  可放置支撑面时右键；请保持准星在方块边缘，桥速接近正常玩家。
- 稳定性：每 tick 最多放置一次，GUI/聊天/死亡/空手/切换物品时立即停止；
  是否压缩 `rightClickDelayTimer` 由 profile 决定，停止时恢复原版 4 tick。
- 设置项（ClickGUI 中展开 GodBridge）：
  - `DELAY`：`godbridge.delayTicks`，0~4 tick，默认 `0`。
  - `FAST PLACE`：`godbridge.fastPlace`，默认 `true`；仅在 `aggressive` profile
    或关闭 bypass 时才会把冷却压到 0。`legit/balanced` 下等待原版冷却自然归零。
  - `REQUIRE EDGE AIM`：`godbridge.requireEdgeAim`，默认 `true`。
  - `AUTO ROTATE`：`godbridge.autoRotate`，默认 `true`。`legit/balanced` 下
    自动旋转会被抑制，只使用真实准星；`aggressive` 才允许临时改视角并恢复。
  - `LEGIT AIM`：`godbridge.legitAim`，默认 `true`，只使用玩家真实准星放置，
    不自动转视角、不补发飞行包；对 Grim 最干净。
  - `PACKET ROTATION`：`godbridge.packetRotation`，默认 `false`，实验性模式，
    在 C08 前补发一次 `C05PacketPlayerLook`（不再发 placement 后的重复 C05）。
    `legit/balanced` 下会被强制抑制；只有 `aggressive` 才实际发送。额外 flying packet
    会被 Grim 的 `Timer` 计数，也可能触发 `AimDuplicateLook` / `AimModulo360`，仅用于对比。
- 实现说明：`design/GODBRIDGE_HANDOFF.md` 记录了状态机、放置顺序与反射/SRG 名称。
- Grim 对测结论：C05 补发包虽然能让服务端在 C08 前看到旋转，但会直接触发
  `Timer`，并可能触发 `AimDuplicateLook` / `AimModulo360`，因此默认关闭；
  `legit/balanced` 建议保持 `LEGIT AIM`。

### Scaffold 自动搭路

- 配置路径：`movement.scaffold`，默认关闭，默认键位 **G**。
- 行为：手持可放置方块且未打开任何界面时，自动检查脚下/运动方向前方的空气格，
  在六个相邻面中寻找安全可见的实心支撑面。`legit/balanced` 下只使用玩家真实准星：
  通过 `EntityRenderer.getMouseOver(1.0F)` 刷新 `objectMouseOver`，只有命中位置和
  `sideHit` 完全匹配时才通过游戏自身的 `rightClickMouse` 放置。
- 稳定性：每个 tick 最多放置一次，失败不补发；打开背包、聊天、ESC 菜单、
  关闭模块、死亡、空手或左键挖掘时立即停止并重置冷却。放置结束后无论
  `AUTO ROTATE` 如何都会恢复临时 rotation 与 `objectMouseOver`。
- 设置项（ClickGUI 中展开 Scaffold）：
  - `DELAY`：`scaffold.delayTicks`，1~10 tick，默认 `2`。
  - `REQUIRE BACKWARD`：`scaffold.requireBackward`，默认 `true`，只向后移动时工作。
  - `AUTO JUMP`：`scaffold.autoJump`，默认 `false`，脚前有可跨越障碍时跳跃。
  - `AUTO ROTATE`：`scaffold.autoRotate`，默认 `true`；`legit/balanced` 下被抑制，
    `aggressive` 下才允许临时转向目标面并在放置后恢复。
  - `LEGIT AIM`：`scaffold.legitAim`，默认 `true`，只用真实准星放置，
    不自动转视角、不补发飞行包。`legit/balanced` 下即使关闭也会被强制回退到此路径。
  - `PACKET ROTATION`：`scaffold.packetRotation`，默认 `false`，实验性 C05 补发；
    `legit/balanced` 下被强制抑制，会触发 Grim `Timer` / `Aim` 类检查。
- 实现说明：`design/SCAFFOLD_HANDOFF.md` 记录了反射/SRG 名称与实现约束。

### Target HUD 目标信息面板

- 配置路径：`render.targethud`，默认关闭，默认键位 **H**。
- 行为：**仅在准星命中生物时显示**（玩家、怪物、动物均可），准星移开立即隐藏；
  不再自动索敌，也不保留历史目标。
- 卡片内容（极简版）：
  - 准星右侧的小卡片，只保留正方形头像、名称、细血条三样信息。
  - 头像：玩家读取 `AbstractClientPlayer#getLocationSkin` 绘制真实皮肤头；
    非玩家生物或读取不到皮肤时统一使用 Steve 头像。
  - 血条：颜色随血量从绿（满血）过渡到橙、再到深红（低血），无刻度、无发光；
    深色纯色底 + 1px 描边，整体随 GUI 缩放设置缩放。
  - 头像不绘制外圈，保持干净的正方形皮肤/Steve 头像。
- 设置项（ClickGUI 中展开 Target HUD）：
  - `RANGE`：`targethud.range`，8~64 格，默认 `32`。
  - `SCALE`：`targethud.scale`，0.7~1.4，默认 `1.0`。
  - `PLAYERS ONLY`：`targethud.playersOnly`，默认 `false`，关闭时所有生物都显示。
- 实现说明：绘制在 `mc/TargetHud.java`，由 `OpenMaverHud` 在
  `RenderGameOverlayEvent.Post` 中调用；对 MC 成员的访问同时兼容 MCP / SRG
  运行期名称，失败时只停用该面板，不影响 ModuleList 与 ClickGUI。
  设计稿见 `design/targethud.html` / `design/targethud-4k.png`（完整版视觉参考）。

### Player X-Ray 玩家透视

- 配置路径：`render.playerxray`，默认关闭，默认键位 **J**。
- 行为：每帧扫描 `World.loadedEntityList` 中的其他 `EntityPlayer`（排除自己与死亡玩家），
  在 `range` 内关闭深度测试，通过 `RenderManager.renderEntitySimple` 渲染完整玩家模型，
  因此可以穿墙看到玩家皮肤、装备和手持物品；模型外侧绘制 1.5px 描边。
- 颜色：描边颜色 `COLOR = Health`（默认）时按目标血量在绿 / 琥珀 / 红之间切换；
  也可固定为 Orange / Cyan / Violet / Red。皮肤模型本身保持原版材质颜色。
- 设置项（ClickGUI 中展开 Player X-Ray）：
  - `RANGE`：`playerxray.range`，8~128 格，默认 `64`。
  - `COLOR`：`playerxray.color`，Health / Orange / Cyan / Violet / Red，默认 `Health`。
- 与既有 **Player ESP** 的区别：Player ESP 是通用生物线框（可包含怪物/动物）；
  Player X-Ray 只处理玩家，并额外提供穿墙皮肤模型与血量配色描边。
- 实现说明：`mc/OpenMaverFeatures.renderPlayerXray()`，开关模块为
  `java/module/render/PlayerXray.java`。

### FastPlace 快速右键

- 配置路径：`player.fastplace`，默认关闭，默认键位 **F**。
- 通过限制 `Minecraft.rightClickDelayTimer` 加快右键放置/使用物品；
  关闭模块、打开 GUI、玩家死亡或切换到非方块物品时恢复原版延迟。
- profile 行为：
  - `legit`：完全不修改 `rightClickDelayTimer`。
  - `balanced`：允许加速，但最低保留 **2 tick** 冷却。与 Scaffold/GodBridge
    同时启用时会让位（不修改 timer），避免把 timer 卡在 2 导致搭路永远等不到 0。
  - `aggressive` / 关闭 bypass：按 `DELAY` 设置，允许压到 0。
- 设置项（ClickGUI 中展开 FastPlace）：
  - `DELAY`：`fastplace.delayTicks`，0~4 tick，默认 `2`。
  - `ONLY BLOCKS`：`fastplace.onlyBlocks`，默认 `true`，仅在手持方块时生效。

### Reach 攻击距离

- 配置路径：`combat.reach`，默认关闭，默认键位 **K**，位于 ClickGUI 的 **Combat** 分类。
- 行为：每次**新的攻击/挥击**按 `CHANCE` 掷一次随机数。命中几率时，本次挥击会在
  profile 允许的距离内做自定义视线射线检测，找到最近且可见的生物后通过
  `PlayerControllerMP.attackEntity` 发送 `C02PacketUseEntity`；
  未命中几率时，本次挥击完全使用原版距离。
- 设置项（ClickGUI 中展开 Reach）：
  - `EXTRA`：`reach.extra`，0.0~0.5 格，默认 `0.0`。它是“在 3.0 基础上的额外量”，
    但会被 profile 硬上限截断；`legit/balanced` 下最终都是 3.00。
  - `CHANCE`：`reach.chance`，1~100 %，默认 `100`。
- 距离上限（Grim 扩张箱度量）：
  - `legit/balanced`：**3.00**。Grim `Reach` 对 1.8 客户端使用
    `maxReach=3.0`，并把目标 AABB 扩张 `0.0005 + 0.1 [+ 0.03]`；因此
    眼睛到扩张箱 ≤ 3.0 时不会 flag，折算回真实命中箱约 3.13 格。
  - `aggressive`：最高 3.30，会主动越过 Grim 阈值，仅用于记录 flag 差异。
  - `bypass.enabled=false`：保留旧的 5.9 上限，仅作历史对比。
- 实现说明（已核对实际 1.8.9 字节码与 Grim 2.0）：
  - `EntityRenderer.getMouseOver` 在生存模式会把 `getBlockReachDistance()`（4.5）
    用作射线长度，但只要实体距离超过 3 格就会丢弃目标；这是原版实体瞄准的硬限制。
  - `PlayerControllerMP.getBlockReachDistance()` 在 1.8.9 是根据 GameType
    **动态返回** 4.5 / 5.0 的方法，并不存在可反射修改的 `blockReachDistance` 字段。
  - Reach 使用精确 eye position + 目标 AABB + `canEntityBeSeen`，只在真实挥击时
    触发；若检测不到 animation，会先补 `swingItem()`，保证 1.8 的
    `PacketOrderB`（ANIMATION → INTERACT_ENTITY）顺序。
  - 使用物品或本 tick 已有右键动作时不做自定义攻击，避免 `MultiActionsA/F`。
  - 方块交互距离保持原版（生存 4.5 格），**本模块只扩展实体攻击/挥击距离**。

### Aim Assist 平滑瞄准

- 设置路径：`config/openmaver.properties` 的 `aim.*`，ClickGUI 中位于 Combat。
- 每 tick 的 `ClientTickEvent.START` 只负责选择目标和重置步长预算；实际 rotation
  在每个渲染帧按真实帧间隔平滑更新，避免 20 TPS 下“每 tick 跳一下”的卡顿感。
  每 tick 的 rotation delta 仍限制在 profile 上限内，服务端看到的步长不变。
- `GCD QUANTIZE`（`aim.gcd`，默认 `true`）：按当前鼠标灵敏度计算
  `(sens*0.6+0.2)^3 * 1.2` 度并量化步长，未走完的零头跨帧累积，保证每 tick
  总 delta 仍是 GCD 的倍数。
- `MAX STEP`（`aim.maxStep`，默认 `35°`）：单 tick 最大 yaw 步长；`legit` 进一步
  收紧到 18°/12°（yaw/pitch），`balanced` 为 35°/20°。yaw 始终 wrap 到
  `[-180,180)`，不发送额外 C05。
- 没有实际 rotation 变化时不写字段，避免 `AimDuplicateLook`。

### AutoClicker 自动点击

- 设置路径：`config/openmaver.properties` 的 `autoclicker.*`，ClickGUI 中位于 Combat。
- 默认 `6~10 CPS`，`HUMANIZE` 默认开启：每次点击有 ±12% 间隔抖动，
  2% 概率追加 120~350ms 停顿。
- `legit` 上限 8 CPS，`balanced` 上限 12 CPS，`aggressive` 上限 20 CPS。
- 同一 tick 左右键互斥；使用物品时完全停止；`legit/balanced` 下连续 MISS 空挥超过
  6 次会暂停 250~500ms。打开 GUI/背包/聊天或死亡时重置计时器，不补发点击。

### FakePlayer 假人玩家

- 配置路径：`misc.fakeplayer`，默认关闭，默认键位 **N**。
- 行为：开启后在本机客户端世界、玩家前方生成指定数量的 `EntityOtherPlayerMP`。
  假人只存在于本地，不会向服务端发送玩家生成/移动数据包，服务端与其他玩家都看不到，
  因此适合测试 **Aim Assist / Player ESP / Target HUD** 等依赖玩家实体的模块。
- 假人默认面向玩家、停留在生成位置（`EntityOtherPlayerMP` 自身不施加重力），
  修改 `COUNT` / `DISTANCE` 会立即重建假人；切换世界、关闭模块时自动回收。
- 设置项（ClickGUI 中展开 Fake Player）：
  - `COUNT`：`fakeplayer.count`，1~5，默认 `1`。
  - `DISTANCE`：`fakeplayer.distance`，2~10 格，默认 `4`。
  - `NAME TAG`：`fakeplayer.showName`，默认 `true`，是否显示头顶名字。
- 实现说明：通过 `WorldClient.addEntityToWorld` 注入客户端实体，使用随机 UUID 的
  `GameProfile`（默认 Steve/Alex 皮肤）；实体 ID 从 `1000000000` 起递增，避开服务端真实实体。

## 运行期注入（不重启、不改启动参数）

`libopenmaver_core.so` 同时实现了 JVMTI agent（`core/jni/agent.cpp` 的 `Agent_OnAttach`）。
attach 时它会在目标进程内：
1. 用 `jar=` 参数把 `openmaver-core.jar` 加入 **bootstrap** 搜索路径，加载并启动核心；
2. 用 `mcjar=` 参数把 `openmaver-mc.jar`（SRG 类）加入**游戏类加载器**
   （`LaunchClassLoader.addURL`），并反射注册 HUD / ClickGUI 到 `MinecraftForge.EVENT_BUS`。

### 注入也能显示 UI（无需装 mod）

前提：目标是**装了 Forge 的 MC 1.8.9**（Java 8），且该实例**没有**加载
`mods/OpenMaver.jar`。mod 模式和注入模式会各自加载一套 `OpenMaver` 类，同时存在会触发
`LinkageError`（`Module` / `ModuleManager` 来自不同 `ClassLoader`）。

推荐直接使用注入脚本：它会先构建，自动把 `mods/OpenMaver.jar` 重命名为
`OpenMaver.jar.disabled`，并检查目标进程是否已经加载 mod 或已经注入过：

```bash
./inject-prism.sh --find      # 只嗅探，不改实例
./inject-prism.sh             # 构建并注入已运行的干净 Forge 实例
./inject-prism.sh --pid 12345 # 指定 PID
```

也可以手动执行底层命令：

```bash
cd /home/zty/OpenMaver
./build/bin/openmaver_injector --find                 # 嗅探，确认目标
./build/bin/openmaver_injector --re-self \
  --so    "$(pwd)/build/lib/libopenmaver_core.so" \
  --options "jar=$(pwd)/build/openmaver-core.jar" \
  --mcjar "$(pwd)/build/openmaver-mc.jar" \
  --follow "$(pwd)/logs/openmaver.log"                 # 可选：注入后持续跟随日志
# 省略 --pid 会自动选嗅探到的目标
```

成功后：目标进程控制台出现 `[OpenMaver] 已注册游戏内 UI 到 Forge 事件总线`，
**进入世界后右下角弹出「Loaded successfully, x features enabled!」，按 RSHIFT 打开 ClickGUI。**
`--follow` 会让注入器在注入成功后继续运行；指定日志文件时从当前文件末尾开始跟随新增行，目标游戏退出后监视结束。按 Ctrl+C 只结束注入器的监视，不影响游戏进程。未提供日志文件时只显示目标进程存活状态。

> 原理：`OpenMaverMcBridge`（在 core jar 里）用 JVMTI 找到游戏的 `LaunchClassLoader`，
> `addURL` 挂上 `openmaver-mc.jar`，再取 `MinecraftForge.EVENT_BUS` 反射 `register(...)`。
> 若桥接失败（非 Forge 客户端），agent 自动退回独立驱动线程（无 UI）。

### 两种注入器

注入器只有一个可执行文件 `build/bin/openmaver_injector`，用参数切换实现：

```bash
# 自研模式：自己实现 HotSpot Dynamic Attach 协议（.attach_pid + SIGQUIT + unix socket）
build/bin/openmaver_injector --re-self \
    --pid <pid> \
    --so  build/lib/libopenmaver_core.so \
    --options "jar=$(realpath build/openmaver-core.jar)"

# jattach 模式：交给外部 jattach
build/bin/openmaver_injector --re-jattach \
    --pid <pid> --so build/lib/libopenmaver_core.so \
    --options "jar=$(realpath build/openmaver-core.jar)"
```

`--re-jattach` 等价于直接执行（`--pid` 省略时会自动在 `/proc` 里找 Minecraft）：

```bash
jattach <pid> load /绝对路径/libopenmaver_core.so true "jar=/绝对路径/openmaver-core.jar"
```

### 自动嗅探 MC 1.8.9 进程

`--pid` 可以省略：注入器会扫描 `/proc` 自动找出「像 MC 1.8.9」的目标。判定规则：

1. 必须是**与当前用户同 euid** 的 **Java 进程**（Dynamic Attach 只允许同用户）；
2. 命令行命中特征后加权打分：

   | 特征 | 分值 |
   |---|---|
   | `net.minecraft.launchwrapper.Launch`（1.8.9 默认主类） | +6 |
   | `net.minecraft.client.main.Main` | +6 |
   | `minecraftforge` / `net.minecraftforge` | +3 |
   | `launchwrapper` | +2 |
   | 含 `1.8.9` | +2 |
   | `minecraft` | +1 |
   | `-Djava.library.path` | +1 |
   | 已存在 `/tmp/.java_pid<pid>` attach socket | +2 |

3. 取分数最高者；命中 `openmaver` 的进程直接排除。

先看候选（`*` 为最终会选中的目标）：

```bash
build/bin/openmaver_injector --list
# [injector] 嗅探到 1 个候选：
#   * pid=16531   score=13  java -cp classes net.minecraft.launchwrapper.Launch --username Dev --version 1.8.9
```

然后不带 `--pid` 直接注入即可：

```bash
build/bin/openmaver_injector --re-self \
    --so build/lib/libopenmaver_core.so \
    --options "jar=$(realpath build/openmaver-core.jar)"
# [injector] 嗅探到 Minecraft 进程 pid=16531
# [injector] 注入完成 (rc=0)
```

> 嗅探基于 `/proc` 命令行特征，属于「启发式」：多个 JVM 同时开、或启动器本身也是 Java 进程时，
> 请用 `--list` 确认，必要时显式 `--pid`。真实 MC 1.8.9 由启动器拉起时，
> 命令行通常包含 `net.minecraft.launchwrapper.Launch`，会被稳定识别。

目标进程的标准输出会打印 `[OpenMaver] Core initialized`、`版本 2.0.0`、
`已注册模块 AutoSprint`、`独立驱动线程已启动` 等，表示注入成功。

### 自研 vs jattach：重复代码在哪

`--re-self` 里那段「造 `.attach_pid` → `SIGQUIT` → 连 `/tmp/.java_pid<pid>` socket →
发 `load <so> true <options>` → 解析返回码」正是 `jattach` 已经实现的东西。
两者对照如下：

| 维度 | `--re-self`（`inject/injector.cpp`） | `--re-jattach` / `jattach` |
|---|---|---|
| 代码量 | ~400 行 C++（协议编解码、pid 扫描、超时重试） | 注入逻辑≈0，只是 `fork/exec` 转发 |
| 运行时依赖 | 无 | `jattach` 可执行文件 |
| 容器（net/ipc/mnt namespace） | 不支持 | 支持 |
| root → 目标 euid/egid 切换 | 不支持 | 支持 |
| OpenJ9 | 不支持 | 支持 |
| 命令集 | 仅 `load` | `load/properties/threaddump/dumpheap/...` |
| 可维护性 | 需跟随 HotSpot 协议演进 | 上游维护 |

**结论**：生产环境建议只保留 `--re-jattach`（甚至不要在仓库里放注入器，直接调用 `jattach`），
把 `inject/injector.cpp` 中重复实现 attach 协议的部分删掉。`--re-self` 仅用于
「目标机上没有 jattach」或教学对照。

## 链路验证（不依赖 Forge）

`main()` 会自建一个守护驱动线程（tick 20 TPS / render 60 FPS）来验证整条链路：

```bash
# 1) 默认配置：AutoSprint 关闭
java -Djava.library.path=build/lib -cp build/java-classes OpenMaver
```

预期关键输出：

```
[OpenMaver] Core initialized                                  # JNI 加载 ✓
[INFO] [OpenMaver] 版本 2.0.0 启动完成
[INFO] [OpenMaver] 配置加载完成: .../config/default.jsonc       # Config 读取 ✓
[INFO] [ModuleManager] 已注册模块 AutoSprint (configKey=movement.autosprint, enabled=false)
[DEBUG] [OpenMaver] 调试查询 getModule("AutoSprint") -> movement.autosprint, enabled=false
[INFO] [OpenMaver] 独立驱动线程已启动 (tick 20 TPS / render 60 FPS)  # 事件驱动 ✓
[INFO] [OpenMaver] 链路验证统计: tick 循环=22 次, render 循环=67 次  # 约 1.1 秒 ✓
[OpenMaver] Core shutdown
[INFO] [OpenMaver] 已退出
```

`tick≈22 / render≈67` 表示 20 TPS 与 60 FPS 的循环确实在跑；此时模块 `enabled=false`，
**未**被驱动（符合“disabled 不调用”的要求）。

```bash
# 2) 把 movement.autosprint.enabled 改成 true 后再跑，验证 Module 基类驱动
java -Djava.library.path=build/lib -Dopenmaver.config=/tmp/om-enabled.jsonc \
     -cp build/java-classes OpenMaver
```

此时会看到：

```
[INFO] [AutoSprint] 模块已启用 (movement.autosprint)         # onEnable ✓
[DEBUG] [AutoSprint] tick #1（链路验证，尚未接入 Forge）      # Module.onTick 被驱动 ✓
...
[INFO] [AutoSprint] 模块已禁用 (movement.autosprint)         # onDisable ✓
```

> Tip：把 `tick #N` 的日志级别看住即可确认 tick 计数在持续推进。
> 由于 `onTick` 每个 tick 都写 debug 日志，默认关闭模块可避免刷屏。

### 链路检查清单

| 环节 | 验证点 |
|---|---|
| JNI 加载 | 控制台出现 `[OpenMaver] Core initialized` |
| 云端库版本 | 日志中打印 `版本 2.0.0` 与 `getVersion()` 一致 |
| Config 读取 | 注册日志里的 `configKey=movement.autosprint` 与 `enabled=` 等于 jsonc 的值 |
| 事件/循环触发 | `链路验证统计: tick 循环=… render 循环=…` 均大于 0 |
| Module 基类驱动 | `enabled=true` 时出现 `onEnable` 与递增的 `tick #N`，退出时 `onDisable` |
| 热重载 | 运行中修改 jsonc 的 `enabled`，约 5 秒内（100 tick）自动同步开关 |

## 扩展新模块

1. 在 `java/module/<分类>/` 下新建类继承 `Module`：

```java
package module.combat;            // 包名第二段即配置分类

public final class Example extends Module {
    public Example() {
        super("Example");         // 配置路径自动推导为 "combat.example"
    }
    @Override public void onEnable() { /* 启用时 */ }
    @Override public void onDisable(){ /* 禁用时 */ }
    @Override public void onTick()   { /* 由 ModuleManager.onTick 驱动 */ }
    @Override public void onRender() { /* 由 ModuleManager.onRender 驱动 */ }
}
```

2. 在 `OpenMaver.registerModules()` 中注册：`ModuleManager.register(new Example());`
3. 在 `config/default.jsonc` 里加 `"combat": { "example": { "enabled": false } }`。

**配置路径约定**：`<包名去掉根包 module> + "." + 类名小写`

| 类 | 配置路径 |
|---|---|
| `module.movement.AutoSprint` | `movement.autosprint` |
| `module.combat.AimAssist` | `combat.aimassist` |
| `module.combat.Reach` | `combat.reach` |
| `module.render.Esp` | `render.esp` |
| `module.tools.Logger` | `tools.logger` |

需要自定义时用 `super("Name", "explicit.key")` 显式指定。

## 配置热重载

`ModuleManager.onTick()` 每 100 tick 调用一次 `Config.reloadIfChanged()`；
文件被外部修改时会自动重解析，并调用所有模块的 `syncFromConfig()` 同步开关状态。
该逻辑位于 `ModuleManager`，因此独立模式与 Forge 模式行为一致。

## 字符串混淆

`core/jni/bridge.cpp` 中的文案常量通过编译期简易 XOR 混淆，
避免明文（如版本号）直接出现在 `strings` 输出中。修改 `obf::kKey` 即可重新混淆。
