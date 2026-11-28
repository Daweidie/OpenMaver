# OpenMaver B2.2.0

私人服务器反作弊对测研究用的客户端骨架，适配 **Minecraft 1.8.9**（Forge 11.15.1.2318）。

> 说明：本仓库包含**基础设施骨架**（JNI 桥接、配置、日志、模块基类、Forge 事件桥接）
> 与基础功能模块（Aim Assist、Player ESP、AutoSprint、AutoClicker、KillAura、Reach、Criticals、Scaffold、Telly 跑跳搭路、Scaffold2 教材搭路辅助、GodBridge 神桥、SafeWalk 方块边缘潜行、KeepSprint 全方向疾跑、AttackReduce、FastPlace、FakePlayer、Target HUD、Player X-Ray、Motion Blur、Chest ESP、Chest Stealer 开箱搬运、Inventory Manager 自动整理背包 / 自动穿甲、FPS / CPS 统计）
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
│       ├── combat/KillAura.java  # 自动攻击开关（MC 侧逻辑在 OpenMaverFeatures）
│       ├── movement/Scaffold.java # 搭桥开关（MC 侧逻辑在 ScaffoldFeature，OpenZen 移植）
│       ├── movement/GodBridge.java # 神桥/蹲起搭路开关（MC 侧逻辑在 mc/GodBridgeFeature）
│       ├── movement/SafeWalk.java # 方块边缘潜行开关（MC 侧逻辑在 mc/SafeWalkFeature）
│       ├── movement/AttackReduce.java # 防击退开关（MC 侧逻辑在 AttackReduceFeature）
│       ├── player/FastPlace.java  # 快速右键开关（MC 侧冷却钩子在 FastPlaceFeature）
│       ├── player/ChestStealer.java # 开箱搬运 / 整理 / 存入开关（MC 侧逻辑在 mc/ChestFeature）
│       ├── player/InventoryManager.java # 自动整理背包 / 穿盔甲 / 丢多余开关（MC 侧在 mc/InventoryManagerFeature）
│       ├── render/ChestEsp.java   # 箱子透视开关（MC 侧绘制在 mc/ChestFeature）
│       ├── render/TargetHud.java  # 目标信息面板开关（MC 侧绘制在 mc/TargetHud）
│       ├── render/PlayerXray.java # 玩家透视开关（MC 侧绘制在 OpenMaverFeatures）
│       ├── render/Fps.java        # 左上角 FPS 显示开关（MC 侧绘制在 mc/StatsHud）
│       ├── render/Cps.java        # 左上角 CPS 显示开关（MC 侧绘制在 mc/StatsHud）
│       └── misc/FakePlayer.java   # 假人玩家开关（MC 侧逻辑在 OpenMaverFeatures）
├── inject/                        # 运行期注入器（C++，可选）
│   └── injector.cpp               # 自研注入器：--re-self / --re-jattach
├── mc/                            # Forge 1.8.9 集成（源码为 MCP 名，构建时 reobf 成 SRG）
│   ├── mcmod.info                 # Forge mod 元数据（打包进 mod jar）
│   ├── OpenMaverMod.java          # @Mod 入口
│   ├── OpenMaverClientHandler.java# 客户端 tick / 渲染事件桥接 + 进入世界启动
│   ├── OpenMaverHud.java          # 右侧 ModuleList（胶囊列表 + 参数摘要 + 启用动效）
│   ├── StatsHud.java              # 左上角 FPS 胶囊 + CPS 小方块（灰色毛玻璃）
│   ├── ChestFeature.java          # 箱子：存储方块 ESP + 开箱搬运/整理/存入
│   ├── InventoryManagerFeature.java # 背包整理：读格子 → 规划 → windowClick
│   ├── InventoryRules.java        # 纯规则表：分类 / 材质评分 / 盔甲槽（无 MC 依赖）
│   ├── InventoryPlanner.java      # 纯计划器：45 格快照 → 下一步点击（无 MC 依赖）
│   ├── ChestPacketHook.java       # 入站 S2D/S30/S2E 窗口包记账（Netty 管线）
│   ├── GuiOpenMaverClickGui.java  # 游戏内 ClickGUI（分类 / 搜索 / 展开设置）
│   ├── GuiMaverMainMenu.java      # 开始主菜单（透明按钮 / 原版清晰全景 / 账号头像）
│   ├── MenuSkinPreview.java       # 无需世界实体的完整皮肤模型展示
│   ├── MaverWallpaper.java        # 主菜单壁纸（原版全景 / 图片 / GIF / MP4 视频）
│   ├── MaverVideo.java            # 视频壁纸解码：ffmpeg 流式 RGBA 帧 → 环形缓冲
│   ├── MaverLogo.java             # 打包的 Maver Logo 纹理绘制
│   ├── BundledResourcePack.java   # 首次启动安装自带的 2pimp v2 [16x] 材质包
│   └── resources/openmaver/resourcepack/2pimp-v2-16x.zip  # 自带材质包本体
├── mcstubs/                       # 编译 mc/ 用的 API stub（仅编译期，不进 jar）
├── tools/McpReobf.java            # MCP -> SRG reobf（Forge 1.8.9 运行期需要）
├── tools/resourcepack.py          # 自带材质包安装（launch / inject 脚本共用）
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

> **`build/` 必须是 CMake 配置过的目录**：如果 `build/` 存在但没有
> `build/CMakeCache.txt`（例如手动 `mkdir -p build/java-classes` 编测试类造成），
> `cmake --build build` 会直接报 `Error: not a CMake build directory (missing CMakeCache.txt)`。
> 这时先跑 `cmake -S . -B build -DCMAKE_BUILD_TYPE=Release` 即可；
> `./launch-openmaver.sh` 与两套 `tools/*-test/run.sh` 现在都会自动处理这种情况。
> 一条命令自检：`tools/build-doctor.sh`（版本 → build 状态 → configure → build → 回归测试）。

产物：

- 本地库：`build/lib/libopenmaver_core.so`（同时是 JVMTI agent，导出 `Agent_OnAttach`）
- Java 类：`build/java-classes/`（Java 8 字节码）
- 核心类 jar：`build/openmaver-core.jar`（供运行期注入的 agent 加入 bootstrap 搜索路径）
- 自研注入器：`build/bin/openmaver_injector`（仅 UNIX）
- 游戏内 UI jar：`build/openmaver-mc.jar`（**已 reobf 成 SRG**，供运行期注入用）
- mod jar：`build/openmaver-b2.2.0-YYYY-MM-DD.jar`（完整 mod：UI 类 + 核心类 + `mcmod.info` + 内置 `.so`，日期为构建日期）

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
| 打开界面（主菜单替换） | `net.minecraftforge.client.event.GuiOpenEvent` |
| HUD 覆盖层 | `net.minecraftforge.client.event.RenderGameOverlayEvent.Post` |
| 世界渲染 | `net.minecraftforge.client.event.RenderWorldLastEvent` |
| 客户端判定 | `net.minecraftforge.fml.relauncher.SideOnly` / `Side` |

> 1.8.9 中 `FMLCommonHandler.instance().bus()` 已废弃并返回 `MinecraftForge.EVENT_BUS`
> （源码注释：*"they're the same thing now"*），因此 tick 与渲染事件统一注册到 `EVENT_BUS`。

### 3) 安装到 Forge 1.8.9

1. **装 Forge**：用官方安装器给 1.8.9 装 Forge（如 `1.8.9-forge1.8.9-11.15.1.2318-1.8.9`）。
   PrismLauncher 用户：右键 `1.8.9` 实例 → *Edit* → *Version* → *Install Forge* → 选 `11.15.1.2318`。
2. 用上面的命令构建出 **`build/openmaver-b2.2.0-YYYY-MM-DD.jar`**。
3. 把生成的版本日期 jar 放进该实例的 `mods/` 目录（`~/.minecraft/mods/` 或 Prism 实例的 `minecraft/mods/`）。
4. 启动游戏 → 进入世界，右下角会出现「Loaded successfully, x features enabled!」。

> **自带材质包**：Maver 内置 `2pimp v2 [16x]` 材质包
> （`mc/resources/openmaver/resourcepack/2pimp-v2-16x.zip`，已打进 jar）。
> 首次启动时 mod 会把它复制到实例的 `resourcepacks/` 并加入资源包列表，正常退出
> 游戏后下次启动自动启用；之后可在 **选项 → 资源包** 里自由开关，Maver 不会再强制打开。
> 使用 `./launch-openmaver.sh` 或 `./inject-prism.sh` 时，安装脚本会在启动前完成同样的
> 安装并立即启用。落地文件名使用纯 ASCII（`2pimp v2 [16x].zip`）：1.8.9 的 JVM 在部分
> 系统上以 ASCII 解码文件名，带 `§` 颜色代码的文件名会变成 `?` 导致永远匹配不上。

> 用 PrismLauncher 的 vanilla 实例直接实现不了——`mc/` 的事件（`TickEvent`、`RenderGameOverlayEvent` 等）
> 全部来自 Forge，必须先给该实例装上 Forge。

### 4) 把 jar 发给别人时：为什么「我这里能跑，他装上什么都不工作」

平台能力（B2.2.0）：

| 平台 | mod 模式（`mods/` 放 jar） | 注入模式（`inject-prism.sh` / `openmaver_injector`） | 本地核心库 |
|---|---|---|---|
| Linux x86_64 | ✅ | ✅（需要 JVMTI + `.so`） | ✅ 随 jar 携带 `native/linux/x64/libopenmaver_core.so` |
| Windows x86_64 | ✅（java-only） | ❌ 注入器是 Linux 专用 | ❌ 无 `.dll`，自动降级 |
| macOS（x64 / arm64） | ✅（java-only） | ❌ | ❌ 无 `.dylib`，自动降级 |
| Linux arm64 | ✅（java-only） | ❌（无对应 `.so`） | ❌，自动降级 |

历史上两个人拿到同一个 jar、只有作者能用，原因就三条，现在都已修掉：

1. **本地库只有 Linux x64 一份。** 以前 `OpenMaver.start()` 直接调用 native `init()`，
   Windows / macOS 上抛 `UnsatisfiedLinkError` → 进世界那一下被 `onClientTick` 的
   `catch (Throwable) { failed = true; }` 捕获 → **tick 桥整条停掉**，HUD、功能、右下角提示
   全都不出现，玩家看到的就是「装上了但什么都没有」。现在 `init()/shutdown()/getVersion()`
   都走 Java 包装：native 拿不到就置 `nativeDead`，退回 **java-only 模式**继续跑
   （功能本身全在 Java 侧，native 只负责 `Core initialized` 日志、版本号和 agent 注入）。
2. **jar 不带 `config/default.jsonc`。** 以前只有把仓库里的配置手动拷进实例目录才有模块开关和
   默认键位，别人只拿到一个 jar 时配置为空 → 所有模块 `enabled=false`、键位为空，
   HUD 显示 `Loaded successfully, 0 features enabled!`。现在 `config/default.jsonc`
   打进 jar 的 `/openmaver/config/`，首次启动由 `OpenMaver.ensureDefaultConfig()`
   释放到游戏目录（已有配置**绝不覆盖**）。
3. **装法叠加 / 装错环境。** `mods/` 里既有 mod jar 又注入了 `openmaver-mc.jar` 时，
   两套 handler 各 toggle 一次 → 开关键看起来「按了没反应」；Lunar / vanilla 1.8.9 /
   Forge 1.12.2 上 mod 根本不会被加载（`mc/` 全部依赖 Forge 1.8.9 的事件）。

排查顺序（让对方按这个报日志）：

1. 启动器 `latest.log`（或控制台）搜 `[OpenMaver]`：应有 `start begin` → `版本 B2.2.0 启动完成`
   → `配置加载完成: .../config/default.jsonc` → 一串 `已注册模块 ...`。
   只有 `start begin` 之后就断了 = jar 没被加载或 `mcmod.info` 不匹配。
2. 进世界后右下角有没有 `Loaded successfully, x features enabled!`；没有就是事件桥没注册
   （非 Forge 客户端 / 双份类加载 `LinkageError`）。
3. 有提示但按开关键没反应 → 看 `%TEMP%\openmaver-events.log`（Linux 为
   `$TMPDIR/openmaver-events.log`）：`first key event: code=.. name=..` 说明键盘上报的
   键码是什么，`clickgui ignored ... not in world yet` / `currentScreen=GuiInventory` 会直接
   指出当时的阻挡条件。

## 运行

### 独立启动 Minecraft

项目可以直接启动本机已安装的 Forge 1.8.9，不会打开 Prism 启动器：

```bash
./launch-openmaver.sh              # mod 模式（默认）
./launch-openmaver.sh --mod        # 显式 mod 模式
./launch-openmaver.sh --inject     # 启动后自动运行期注入
```

`./launch_openmaver.sh` 是同一启动流程的兼容入口。

启动前会校验并安装 `~/Downloads/viaforge-mc189-4.3.2.jar` 到当前实例的 `mods/`。
ViaForge 在 `--mod`、`--inject` 两种模式下都由 Forge 启动加载；进入服务器前在游戏内
选择目标协议 `1.21.1`，本地游戏仍为 Forge 1.8.9。
已安装且 SHA-256 匹配的 ViaForge 会被复用，下载文件之后可移走；冲突版本或重复 mod 会阻止启动。
用 `OPENMAVER_VIAFORGE_JAR=/path/to/viaforge-mc189-4.3.2.jar` 指定下载文件位置。
`OPENMAVER_VIAFORGE=0` 跳过自动安装与校验，已有的 ViaForge mod 仍照常加载。

两种模式互斥，避免同一实例里同时从 mod jar 和注入 jar 各加载一套 `OpenMaver` 类：

- `--mod`：把最新的 `build/openmaver-b2.2.0-YYYY-MM-DD.jar` 安装到 `minecraft/mods/OpenMaver.jar`，
  由 Forge 在启动时加载。
- `--inject`：把已有的 `mods/OpenMaver.jar` 重命名为 `OpenMaver.jar.disabled`，
  不安装 mod；启动 Forge 后等待资源加载完成，再用 `openmaver-core.jar` +
  `openmaver-mc.jar` 注入，并通过 Forge 事件总线注册 HUD / ClickGUI。

脚本先构建项目，再从 Prism 的共享缓存读取 Java、Minecraft/Forge 依赖和活动账号会话。
默认实例为 `1.8.9(1)`；可用 `OPENMAVER_INSTANCE` 指定实例名，或用
`OPENMAVER_INSTANCE_DIR` 和 `PRISM_ROOT` 指定自定义目录。账号会话沿用该机器已缓存的
有效游戏令牌；没有活动账号时可设置 `OPENMAVER_USERNAME` 以离线身份进入仅允许离线账号的服务器。

仅检查启动参数和本地依赖：`./launch-openmaver.sh --dry-run --inject`；此模式跳过构建、
ViaForge 安装和实例修改，也可直接运行 `python3 launch-openmaver.py --dry-run --inject`。

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
[2026-... ] [INFO] [OpenMaver] 版本 B2.2.0 启动完成
[2026-... ] [INFO] [OpenMaver] 配置加载完成: .../config/default.jsonc
[2026-... ] [INFO] [OpenMaver] 独立运行模式：未检测到游戏主循环
[OpenMaver] Core shutdown
[2026-... ] [INFO] [OpenMaver] 已退出
```

### Minecraft 1.8.9 中

装上 Forge 1.8.9 后，把构建好的 **`build/openmaver-b2.2.0-YYYY-MM-DD.jar`** 放入 `mods/` 即可
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
| RSHIFT（在游戏内，可在 Config 齿轮 → GUI key 改键） | 开关 `GuiOpenMaverClickGui` |
| 模块绑定键（如 `V`） | 切换对应模块的启用状态 |

配置文件默认读取游戏工作目录下的 `config/default.jsonc`
（即 `.minecraft/config/default.jsonc`），可用系统属性覆盖：
`-Dopenmaver.config=/绝对路径/config.jsonc`。

> 本节描述的「把原生库放进 jar、首次运行解压再 `System.load`」已经实现（见
> `OpenMaver` 的 `loadBundledNativeCore`）：构建出的日期版本 jar 里就带着
> `native/<os>/<arch>/libopenmaver_core.so`，开箱即用。

## 游戏内 UI（进入世界后）

按需求，OpenMaver 的核心**延迟到玩家真正进入世界**时才启动，并给出可视反馈：

- **ModuleList** `mc/OpenMaverHud.java`：屏幕右侧垂直堆叠的**已启用功能**列表。
  每行是深色毛玻璃圆角卡片，右边缘严格对齐，宽度随文字长短变化；模块名使用白色，
  右侧以较淡的白色显示该功能的当前参数摘要（例如 AutoClicker 的 CPS 区间、Reach 的
  实际距离），最右侧是一枚白色竖条；不再显示键位提示。禁用功能不会出现在列表中。每行拥有独立
  动画状态：启用时从右侧平滑淡入，禁用时先淡出再移除，剩余行平滑上移换位。
  所有动画基于 `System.nanoTime()`，与帧率无关。ClickGUI 打开时该列表保留在背景中，
  提示卡则在界面上方绘制。
- **Target HUD** `mc/TargetHud.java`：准星右侧的小卡片。**只有准星真正命中
  `EntityLivingBase` 时才显示**，移开准星立即隐藏；只保留正方形头像（玩家真实皮肤 /
  无头像时使用 Steve）、名称与细血条，卡片背景使用与功能栏相同的局部毛玻璃，
  血条颜色随血量从绿到橙再到深红渐变，无头像外圈。
- **FPS / CPS 统计** `mc/StatsHud.java`：默认显示在**屏幕左上角**。
  最左侧是灰色毛玻璃的小正方形 CPS 面板（上下两行分别显示左键 / 右键最近 1 秒的 CPS），
  右侧紧挨一枚同样灰色毛玻璃的圆角 FPS 胶囊。两者都模糊自身覆盖的画面，
  与 ModuleList / 右下角提示共用 `GuiBlur`；由 `render.fps` 与 `render.cps`
  两个模块控制，默认都开启。
- **Chest ESP 箱子透视** `mc/ChestFeature.onRenderWorld`：世界里的箱子 / 大箱子 /
  末影箱（可选漏斗、熔炉、发射器）穿墙线框，由 `render.chestesp` 控制。
  箱子内容预览（`Chest Info` 信息栏）已移除：搬运直接走开箱会话（界面里 shift-click 拿走），
  不再需要维护「这个箱子我开过、里面有什么」的预览数据。
- **Player X-Ray** `mc/OpenMaverFeatures.renderPlayerXray`：玩家透视。只扫描
  `EntityPlayer`，关闭深度测试后通过游戏自身的 `RenderManager` 渲染完整皮肤模型，
  让玩家皮肤、装备和手持物品穿墙可见；模型外侧再绘制 1.5px 描边。
  描边颜色默认按目标血量（绿 / 琥珀 / 红）变化，也可固定为橙 / 青 / 紫 / 红。
- **右下角提示**：进入世界时弹出「Loaded successfully, x features enabled!」提示，
  灰色透明毛玻璃卡片（只模糊卡片自身覆盖的那块画面）+ 底部白色倒计时进度条，
  从屏幕右侧滑入、停留约 2.4 秒后滑出淡出；连续触发时多张提示会以**卡片栈**形式
  从下往上叠加，每张卡片拥有独立时间线，旧卡片退场后其余卡片平滑补位。
  游戏内启用 / 禁用功能时也会弹出「xxx enabled / xxx disabled」。
  绘制在 `RenderGameOverlayEvent.Post`（`type == ALL`）里，ClickGUI 内由 GUI 自己绘制。
- **Maver 主菜单** `mc/GuiMaverMainMenu.java`：替换原版标题界面（`GuiOpenEvent`），
  默认使用清晰的原版旋转全景图，中央展示白色 Maver 字标，以及三行半透明圆角按钮
  **Singleplayer / Multiplayer / Settings**。背景和按钮均不使用模糊。
  左上角展示当前账号头像与用户名，旁边的 **+ / Add account** 自动打开浏览器登录微软账号；
  点击头像或用户名可管理已保存的正版账号，支持刷新登录、切换和移除。
  右上角依次为 **Fullscreen / Wallpaper / Quit Game**，悬停显示入口名称。
  头像从微软 / Mojang 官方服务异步获取，支持旧版 64×32 皮肤，失败时展示 Steve。
  兼容 **In-Game Account Switcher** 等通过 Forge 添加主菜单按钮的模组：保留原版主菜单
  类型，绘制模组按钮并通过 Forge 标准事件处理点击；IAS 入口位置沿用其自身配置。
  主菜单兼容回归测试：`bash tools/mainmenu-test/run.sh`（先完成构建）。
  背景可以替换成静态图片、动态 GIF 或 MP4 等视频，详见下面「主菜单壁纸」。
- **游戏内 ClickGUI** `mc/GuiOpenMaverClickGui.java`：游戏内按 **RSHIFT** 开关（默认键位，可在
  Config 面板右上角齿轮展开设置，在 **GUI key** 行点一下切换：RSHIFT / LSHIFT / RCTRL / RALT / NUM ENTER /
  SEMICOLON / APOSTROPHE / GRAVE / TAB，写进 `config/openmaver.properties` 的
  `ui.clickgui.key`）。
  仅保留可拖动的列式界面：Combat / Movement / Render / Player / Misc，
  以及独立的 **Config / Personalization** 浮动面板；旧版侧栏界面与 Client 列已移除。
  Personalization 收纳 Wallpaper、Color、Text 与 Taunt 设置；主题色会同步更新 ClickGUI 高亮和 Config 控件，HUD 上方闪烁文字也可自定义。
  **Color → Rainbow** 可开启右上角功能名的动态彩虹渐变，默认关闭，随配置保存；功能栏文字略小于原版字号。
  拖动标题栏移动面板，点击或右键标题栏折叠，位置与折叠状态在本次游戏会话内保留。
  左键模块切换开关、右键展开滑块 / 选项 / 复选框；底部搜索支持直接输入与 `Ctrl+K`。
  Velocity 左键切换开关、右键在 Movement 列内展开参数；列宽按当前可见配置的文字平滑加宽，
  完整显示标签与数值，收起后平滑恢复。模式及选项左键向后切换、右键向前切换。
  所有面板都在内容溢出时单独滚动，主菜单与游戏画面均不使用整屏模糊。模块列表来自
  `ModuleManager`，Aim Assist、Player ESP、Bypass、AutoSprint、AutoClicker、Reach、
  Criticals、FastPlace、Target HUD 与 Player X-Ray 的设置直接绑定 `OpenMaverFeatures`，参数写入
  `config/openmaver.properties`（拖拽结束与关闭 GUI 时落盘）。
- **Anti Bot 反假人** `mc/AntiBotFeature.java`：在 **Combat → Anti Bot** 中开启（默认关闭）。
  默认按 UUID 检查 Tab 名单，持续缺席 40 tick（约 2 秒）才判定为假人；Tab 信息恢复后立即解除。
  右键可调整 `TAB CHECK`、`NAME CHECK`、`CHECK DELAY`（0–100 tick）和 `FILTER RENDER`。
  名称格式校验默认关闭，开启后要求名字为 1–16 位英文字母、数字或下划线。
  自动选敌 / 攻击（Aim Assist、KillAura、Reach、AutoClicker、Velocity）会忽略判定出的假人；
  `FILTER RENDER` 同时让 Player ESP、Player X-Ray、Target HUD 和 NameTag 忽略它们，原版实体仍保留。
  检测结果每 tick 更新，离开世界或重生后重新检测；Tab 数据尚未加载或不可读取时保留玩家。
  服务器若主动从 Tab 隐藏真人，可关闭 `TAB CHECK`；拥有正常 Tab 记录和名字的假人无法仅靠这两项检查识别。
  内置 FakePlayer 也会受 Tab 校验影响，训练时可关闭 Anti Bot。设置随 Config 配置保存。
- **NameTag 玩家标签** `mc/NameTagFeature.java`：在 **Render → NameTag** 中开启，
  玩家头顶显示名称、实时血量/血量上限与细血条，例如 `Steve 18/20 HP`。
  血量从高到低依次显示为绿、橙、红色；默认范围 64 米，右键可调整距离、字号和穿墙显示。
  开启后替换原版玩家名字，关闭后恢复；不显示自己、死亡或对自己不可见的玩家。
  NameTag 与 Player X-Ray 同时启用时，透视模型的额外渲染不会重复绘制标签。
- **Effect Tag 效果卡片** `mc/EffectTagHud.java`：在 **Render → Effect Tag** 中开关，
  配置路径 `render.effecttag`，默认开启。屏幕左侧按效果排列白色半透明流体玻璃圆角卡片，
  带局部背景模糊、边缘折射和高光；使用原版 Minecraft 字体及深色文字，
  显示游戏语言对应的名称、等级、彩色图标和剩余时间；30 秒内倒计时变为深黄色，
  10 秒内变为红色。没有效果时隐藏，并避开左上角 FPS / CPS 面板。
- **主菜单壁纸** `mc/MaverWallpaper.java`：ClickGUI 的 **Personalization → Wallpaper** 面板
  （或主菜单右上角的图片图标）可以切换开始菜单背景：

  | 模式 | 效果 |
  |---|---|
  | `PANORAMA` | 清晰的原版旋转全景图（默认，无模糊） |
  | `STATIC` | 静态 PNG / JPG / BMP / WebP / AVIF 图片；GIF / 视频只显示第一帧 |
  | `DYNAMIC` | 动态 GIF 循环播放；MP4 / MOV / WebM / MKV 循环播放视频；普通图片做缓慢平移 / 缩放；未选文件时使用原版全景 |

  把素材放到 `config/openmaver/wallpapers/`（支持 `.png` / `.jpg` / `.jpeg` / `.bmp` /
  `.gif` / `.webp` / `.avif` / `.mp4` / `.m4v` / `.mov` / `.webm` / `.mkv`），
  然后在 Wallpaper 面板选择文件即可；点击 `Source` 恢复内置背景；
  从主菜单打开时，选中的壁纸加载后会立即显示在设置页背景中。
  `FIT` 可设为 `COVER`（裁切铺满）、`CONTAIN`（完整显示、留黑边）
  或 `STRETCH`（拉伸铺满）。GIF 会在后台线程解码并限制到 720px 边长 / 64 帧，避免大文件拖慢主菜单。
  主菜单始终保持清晰，背景纹理使用逐像素采样，旧配置中的 `menu.wallpaper.blur` 已忽略。
  旧版 `dynamic` / `static` 模式未选文件时自动恢复原版全景，不再显示柔和的渐变背景。

  Java 8 的 ImageIO 只认 PNG / JPG / BMP / GIF：`.webp`、`.avif`，以及**扩展名和内容不符**
  的图片（例如把 WebP 改名成 `.png`）会由 ffmpeg 解一帧后当作静态壁纸显示，
  所以装了 ffmpeg 的机器上这些格式一样能直接用。解不出来时不会静默失败：
  Wallpaper 面板会显示红色的 `CAN'T DECODE · NEEDS FFMPEG` / `VIDEO NEEDS FFMPEG`，
  控制台也会打印一次原因，并且会自动重试（装好 ffmpeg 后无需重启）。

  视频走 `mc/MaverVideo.java`：后台线程启动
  `ffmpeg -stream_loop -1 -re -i <file> -f rawvideo -pix_fmt rgba pipe:1`，
  解码线程把 RGBA 帧转成 ARGB 写进 3 槽环形缓冲，渲染线程每帧只上传最新一帧到双缓冲纹理
  （`GL_LINEAR`），解不过来时继续画上一帧，主菜单帧率不会被视频拖慢。
  帧尺寸按视频原始比例缩到 `1280×720` 以内，`ffprobe` 读不到分辨率时退回 1280×720；
  `STATIC` 模式只解首帧（一个 ffmpeg 进程、一帧纹理）。
  视频壁纸需要本机有 **ffmpeg**（`ffprobe` 用于读分辨率，缺失时只是尺寸退化）：
  解析顺序为 `-Dopenmaver.ffmpeg=<path>` → 环境变量 `OPENMAVER_FFMPEG` →
  `config/openmaver/bin/ffmpeg[.exe]` → `PATH`。找不到 ffmpeg 时视频壁纸自动回退到原版全景图，
  控制台会给出一行提示，图片 / GIF 壁纸不受影响。
  主菜单停止绘制（例如进入世界）超过 6 秒会自动停掉 ffmpeg 进程，回到主菜单再自动拉起。
  软件解码有 CPU 成本：1080p H.264 缩到 1280×720 @24fps 约占用单核 70–80%，
  觉得重就用下面的参数降帧率或缩小尺寸（4K 源同理按比例缩放）。
  视频解码参数可以用 JVM 参数覆盖：

  ```text
  -Dopenmaver.wallpaper.videoWidth=1280    # 帧最大宽度
  -Dopenmaver.wallpaper.videoHeight=720    # 帧最大高度
  -Dopenmaver.wallpaper.videoFps=24        # 输出帧率（5–60）
  ```
  配置写入 `config/openmaver.properties`：

  ```properties
  menu.wallpaper.mode=panorama        # panorama（默认）| static | dynamic
  menu.wallpaper.file=my-wallpaper.png
  menu.wallpaper.fit=cover            # cover | contain | stretch
  ```

  `menu.wallpaper.file` 也支持绝对路径和 `~/` 开头的路径；相对路径优先在
  `config/openmaver/wallpapers/` 下查找。壁纸解码失败或文件被删除时会自动回退到原版全景图，
  不会让主菜单黑屏。壁纸目录也可以通过 `-Dopenmaver.wallpapers=<dir>` 覆盖。
- **模块键位**：`config/default.jsonc` 中的 `<分类>.<模块>.key`（如
  `movement.autosprint.key = "V"`）会在游戏内绑定到该键；键位提示不再显示在 HUD 上。

HUD、ClickGUI 与主菜单替换逻辑都在 `OpenMaverMod.init()` 里注册到
`MinecraftForge.EVENT_BUS`；进入世界由 `OpenMaverClientHandler.onClientTick()` 首次检测到
`theWorld/thePlayer` 非空时触发：

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

### 微软正版账号登录

主菜单左上角 **+ / Add account** 会自动打开系统浏览器。完成微软授权后，Maver 验证 Xbox Live、
XSTS、Minecraft Java 角色，再切换当前游戏会话并刷新主菜单头像和用户名。授权失败、取消或
关闭账号页不会切换账号。无法自动打开浏览器时，可以点击 **Open browser / Copy link**。
点击主菜单头像或用户名打开账号列表，**Login** 刷新凭据并切换账号，**Remove** 移除本地保存记录。
切换仅允许在退出世界后进行；移除保存记录不会注销当前游戏会话，也不会撤销微软端授权。

与 [HMCL 的 OAuth 实现](https://github.com/HMCL-dev/HMCL/blob/main/HMCLCore/src/main/java/org/jackhuang/hmcl/auth/OAuth.java)
及 [OAuthServer](https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/game/OAuthServer.java)
一样，**发行者需要提供自己的微软公开应用 Client ID**。仓库不预置其他启动器的应用身份，
没有配置 ID 时会显示明确提示。应用应支持个人微软账号、桌面公共客户端和
`http://localhost` 回调（端口由本地监听器动态选择），并具备 Xbox Live / Minecraft Services 所需的使用资格。
不需要 Client Secret，用户密码只在微软网页输入。

发行时可把公开 ID 打进 jar：

```bash
cmake -S . -B build -DOPENMAVER_MICROSOFT_CLIENT_ID=你的应用ID
cmake --build build --target openmaver_mod -j 2
```

本地使用也可创建 `config/openmaver-auth.properties`，写入 `clientId=你的应用ID`。
覆盖优先级为 JVM 参数 `-Dopenmaver.microsoft.clientId=...`、环境变量
`OPENMAVER_MICROSOFT_CLIENT_ID`、上述文件、jar 内置 ID。已保存账号使用当时授权的 ID 刷新。

账号独立保存在 `config/openmaver-accounts.json`，只存角色资料、公开 Client ID 和微软刷新令牌，
不保存密码或 Minecraft 访问令牌，也不写入 Config 导出的 `.cfg`。文件未加密；POSIX 平台限制为
当前用户读写（0600），写入采用临时文件与原子替换。不要分享此文件；路径可通过
`-Dopenmaver.accounts=/path/to/accounts.json` 覆盖。

登录回归测试使用模拟官方服务与本机回调，不需要真实账号：`bash tools/account-test/run.sh`。

### Config 面板：配置档案（保存 / 导入）

ClickGUI 的独立 **Config** 浮动面板管理配置档案，对应 `mc/ConfigProfiles.java`。
图标按钮悬停显示操作名称；右上角齿轮在面板内展开客户端设置。

| 控件 | 作用 |
|---|---|
| 名字输入框 | 输入档案名（自动过滤非法字符、去掉 `.cfg` 后缀，最长 40 字），点一下获得焦点后直接打字 |
| 标题栏 `+` | 填一个不冲突的默认名（`config_MM.dd`），并把光标放进输入框 |
| 刷新图标 | 刷新列表并清除结果提示 |
| 保存图标 / 输入框 Enter | 把**当前内存状态**落盘后打包成 `config/profiles/<名字>.cfg`（同名覆盖） |
| 载入图标 | 应用档案：还原两个配置文件 → 重载配置 → 按档案恢复模块开关 / 键位 |
| 删除图标 | 删除输入框里那个档案 |
| `Folder` | 打开 `config/profiles/`（单文件，直接发给别人即可） |
| `Import` | 选择本地 `.cfg` 文件导入列表；同名时自动加后缀，保留已有档案，随后点击载入图标应用 |
| 列表行 | 选中档案并填入名字输入框 |

档案是纯文本、三段式，便于手工 diff：

```
# OpenMaver profile v1
# name: LF_Opal10.26
@@FEATURES        <- config/openmaver.properties 原文（滑块 / 开关 / ui.clickgui.key）
@@MODULES         <- config/default.jsonc 原文（模块 enabled / key）
@@STATE           <- 保存时 GUI 里的实时模块状态：名字|on/off|键位
```

> 保存前会先 `flushSettings()` 把 GUI 里刚拖的滑块落盘，所以存下来的一定是当前值。
> 导入会在同一个 tick 内完成 `Config.reload()` + `OpenMaverFeatures.reloadSettingsNow()`
> + `ModuleManager.syncAll()`，不需要重启游戏。

## 功能模块

模块开关与键位来自 `config/default.jsonc`，模块参数来自
`config/openmaver.properties`（ClickGUI 中修改后自动落盘，重启保留）。

### Bypass 反作弊选择与 profile（`bypass.*`）

所有战斗/搭路模块共享一个 profile，默认 **BALANCED**，可在 ClickGUI 的
**Misc → Bypass** 中选择目标反作弊和 profile，也可直接编辑 `config/openmaver.properties`：

```properties
bypass.enabled=true
bypass.anticheat=grim       # grim | intave；旧配置缺省为 grim
bypass.profile=balanced     # legit | balanced | aggressive
debug.enabled=false         # true 时写入 logs/openmaver-debug.log
```

`ANTICHEAT` 选择会保存到配置和导出的 profile。现有 profile 限制继续共用。
Intave 专用 MoveDisabler 目前实现了玩家背包（window 0）的惯性帧点击候选：光标清空后成组提交，
每批撤销一帧水平加速度并保留真实惯性。Physics 与服务器物品结果尚待新实测，Inventory Manager
的自动规划也未完整适配。因此选择 `INTAVE` 不代表
所有背包检查均已通过。源码判据和待确认项见 [Intave 背包审计](design/INTAVE_INVENTORY_AUDIT.md)。

测试 Intave 时，可在 **Misc → Intave Debug** 启用纯观测记录器。它会记录客户端移动/窗口包、
服务端窗口与事务响应，以及每条记录对应的 GUI、窗口号、光标物品、移动输入和位置；不会
改包或改输入。完成一次测试后关闭模块并发送实例目录下的
`logs/intave-movedisabler-debug.log`，同时附上服务器 Intave 对应时间段的点击/Physics 日志。

| profile | Reach 实际上限 | Aim 单 tick 上限 | AutoClicker / KillAura | FastPlace | Placement rotation |
|---|---|---|---|---|---|
| `legit` | 0~5.00；GRIM CAP 开启时 ≤3.00 | yaw 18° / pitch 12° | 使用配置 CPS（1~20），抖动+停顿 | 使用独立 TICK（0–5t） | 仅真实准星 |
| `balanced`（默认） | 0~5.00；GRIM CAP 开启时 ≤3.00 | yaw 35° / pitch 20° | 使用配置 CPS（1~20），抖动+停顿 | 使用独立 TICK（0–5t） | Scaffold 直接走 onPlayerRightClick |
| `aggressive` | 0~5.00；GRIM CAP 开启时 ≤3.00 | 上限放宽 | 使用配置 CPS（1~20） | 使用独立 TICK（0–5t） | 同左（Scaffold 始终直接发包） |

> Reach 独立使用 `reach.distance`（0~5 米）与 `reach.grimCheck`（默认 false），
> 包括 `bypass.enabled=false` 时也执行独立距离校验。KillAura 保留其原有 profile 距离上限。

**阈值依据（Grim 2.0，commit `f9b801a`）**

- `combat/Reach`：1.8 客户端 `maxReach = 3.0`；目标 AABB 先扩张
  `Reach.threshold(0.0005) + 1.8 hitbox(0.1) [+ movementThreshold(0.03)]`。
  因此安全实现是“眼睛到扩张箱 ≤ 3.0”，折算回真实命中箱约 3.13 格。
- `combat/Hitboxes`：Reach 的射线完全没命中 AABB 时触发。
- `aim/AimDuplicateLook`：重复相同 rotation 会 flag；本项目在没有实际变化时不写
  rotation 字段。
- `aim/AimModulo360`：`|deltaYaw| > 320` 且上一 tick `< 30` 会 flag，抓的是
  **客户端自己把 yaw 取模 360**（跨 ±180 时包间凭空跳 358°）。所以自动 yaw
  必须**连续累加、不取模**（`advanceYaw` / `continuousYaw`），单 tick 限速照旧。
  早期这里写成「wrap 到 [-180,180)」是反的，实测会被连续 flag（vl 每次 +0.975）。
- `scaffolding/RotationPlace`：**判定时机是「C08 之后的那条飞包」**。
  `CheckManagerListener` 把 C08 先入队，等下一条飞包到达才派发
  `onPostFlyingBlockPlace`；1.8 客户端走的分支无条件用**这条飞包**的
  yaw/pitch 覆盖 `player.yaw/pitch`，位置取 `lastClaimedPosition`（C08 之前那条
  飞包的位置 = 发包时的当前位置）。`didRayTraceHit` 的 pitch 只有这一个来源，
  yaw 才允许 `lastYaw` 回退（1.8 没有 lastPitch）。
  因此 Scaffold 的不变量是：**用服务端已经收到的角度做 ray trace，发 C08，
  然后把这一 tick 的飞包锁成同一个角度**（`flushPending` + `holdPlaceAim`），
  该 tick 不再规划新目标。角度对不上就重新瞄准、下一 tick 再放
  （`pendingDecision` / `MAX_PENDING_RETRIES=2`），绝不拿没校验过的角度发包。

  > 17:31 那局证明了「先发 aim、下一 tick 再放」**不够**：Grim 用的是再下一条
  > 飞包的角度，所以放置那一 tick 的包会被下一个目标的 aim（或相机）占掉，
  > 100% flag。第二版修复（`holdPlaceAim`）、源码引用与代价分析见
  > `design/SCAFFOLD_ROTATIONPLACE_HANDOFF.md` §2.1 / §11。
  > 代价是每 2 tick 一块（10 块/秒）。回归测试：`tools/scaffold-test/run.sh`
  > （70 项：决策表、旋转数学、支撑面映射、clutch 窗口与源码结构）。

  > 这两条不变量在 OpenZen 移植版里依然成立：`place()` 只由 `flushPending()`
  > 调用（唯一 `onPlayerRightClick` 调用点），并且共用
  > `OpenMaverFeatures.claimPlacement()` 的“一 tick 一个 C08”名额（`MultiPlace`）。
- `timer/Timer`：每个 tick 包固定按 50ms 计费；Scaffold 不再额外发
  `C05PacketPlayerLook`，每 tick 只有一条 C03 族包。
- `multiactions/*`：使用物品时攻击/挥击、同 tick 方块+实体交互、移动中开背包都会
  flag；AutoClicker/Reach 已加入互斥与使用物品保护。

**观测**：`debug.enabled=true` 时记录 aim rotation 变化、C02 攻击、
C08 放置、点击间隔、被抑制的 C05，以及每 100 tick 的统计。默认关闭，
IO 失败只记录一次并自动停用 debug。

完整审计、Grim commit、checks.yml 对照项与对测记录模板见
`design/GRIM_BYPASS_HANDOFF.md`。

### Scaffold 自动搭路 / 搭桥

- 配置路径：`movement.scaffold`，默认关闭、不绑定默认热键；ClickGUI 提供 `Normal` / `Simple`。
- **Normal 已整体替换为 Augustus 控制器**，参考上一级 `OpenAugustus-Maver` 的
  `augustus/recovered/modules/world/Scaffold_47Te.java`：预测移动位置搜索支撑方块，
  朝移动反方向转向，扫描可命中的俯角，再用实际射线交点放置。自动搭路无需按住右键。
  旧 Normal 的菱形搜索、随机面点、Snap、Eagle、周期潜行和 Clutch/击退锁定逻辑均不再进入。
- Normal 设置：Yaw/Pitch Speed（默认 30–50°/tick）、Yaw/Pitch Acceleration（7–15）、
  CPS（10–20）、Max Pitch Correction（180°）、Pitch Selection（Nearest/Furthest/Highest/Lowest/Mid/Random）、
  Extra Block Search、Always Place、Force Place、No Swing。默认开启额外搜索、可放置即放置和
  踏空时强制放置，默认挥手；Always Place / Force Place 可越过 CPS 等待。
  设置保存在 `scaffold.normal.*`；旧 `scaffold.eagle` / `scaffold.snap` 不再读取或保存。
- **Simple** 保留 OpenZen 的 Telly Bridge（`scaffold.mode=1`），包括桥面高度锁定、
  自动起跳、空中转向、周期 Sneak、Clutch 和 S12 击退窗口；Old Telly / Keep Y 保留配置兼容。
  Normal 的界面隐藏这些旧模式参数。
- 共享放置时序：tick N 把 aim 写入 C03，tick N+1 从当前眼睛用已发送角度重做射线，
  仍命中所选支撑且当前点击面的目标格为空才发 C08；成功后本 tick 保持该角度，每 tick 最多放置一次。
  Normal 全程使用 double 世界坐标和实际 hitVec，避免远坐标眼睛位置截断。
- `SLOT` 默认 `HOTBAR`：真实换到方块槽，C09 交给原版同步，换槽当 tick 不放置，
  C08 使用当前手持方块。`SILENT` 可配合 `ITEM SPOOF` 在 tick 末恢复客户端槽位。
  关闭模块、切换模式或世界时释放旧目标、相机、槽位及接管的按键。
- 静默 aim 和八方向 MoveFix 由 `ScaffoldMovementInput` 执行，END 恢复相机；
  潜行保留原版 0.3 倍输入。Normal 根据修正前的后退/侧移/潜行输入取消意外疾跑，
  在原版检查期间屏蔽疾跑键和双击意图，正常前进仍遵循原版疾跑条件。
- `BLOCK HUD` 显示方块图标、数量和搭路速度；Normal 隐藏世界目标框。
- 实现：`mc/AugustusScaffold.java`（Normal）、`mc/ScaffoldFeature.java`（模式分派、
  其他模式及共享传输）、`mc/ScaffoldMovementInput.java`（输入 hook）、`mc/ScaffoldHud.java`。
- 参考工程部分旋转辅助函数只有 native 声明；这里采用加速度限速和相对鼠标步长适配，
  不包含 Augustus 的全部可选子模式。来源对应与适配范围见 `design/SCAFFOLD_NORMAL_HANDOFF.md`。
- 回归：`tools/scaffold-test/run.sh`、`tools/telly-test/run.sh`；Normal 测试覆盖实际输入/tick、
  八方向连续搭桥、远坐标、C03/C09/C08 顺序、射线交点和生命周期。
  共享机制详见 `design/SCAFFOLD_HANDOFF.md`。

### Telly 跑跳搭路（OpenZen）

- 在 ClickGUI 的 Movement 分类开启 `Telly`；默认关闭、没有默认热键，可自行绑定。
  快捷栏准备整块方块，按移动键自动起跳搭路；停下会释放自动跳跃，物理按住空格可抬高桥面。
- 支持 `TELLY BRIDGE` 和 `OLD TELLY`，沿用 OpenZen 的目标高度锁定、地面起跳与空中转向。
  地面阶段不放块，空中按已发送的视角校验支撑面后放置，客户端相机保持原视角。
- 右键展开可调 `MODE`、`SNEAK`、`CLUTCH`、`ROTATION TICK`、`SLOT`、`ITEM SPOOF`、`BLOCK HUD`。
  `SLOT` 默认 `HOTBAR`；`ITEM SPOOF` 只在 `SILENT` 下生效。
- 模块开关配置为 `movement.telly`，参数单独保存为 `config/openmaver.properties` 中的 `telly.*`，
  与 Scaffold 参数独立。Telly 和 Scaffold / GodBridge / Scaffold2 互斥，后开启的模块接管。
- 打开界面、死亡、断线、切换世界或关闭模块时释放自动按键并取消待放置目标；恢复后重新捕获桥面高度。
- 复用 `mc/ScaffoldFeature.java` 的放置和静默转向引擎，参数在 `mc/TellyFeature.java`；
  测试：`bash tools/telly-test/run.sh`（先完成 CMake 构建），实现说明见 `design/TELLY_HANDOFF.md`。

### GodBridge 神桥 / 蹲起搭路（教材重写版）

- 配置路径：`movement.godbridge`，默认关闭，默认键位 **N**；开启时会自动关掉
  `Scaffold`（两套搭桥抢同一个 Shift 键与 C08 名额）。
- 三个模式直接对应手动搭桥教材的三种手法（20 TPS 折算）：
  | 模式 | 操作 | 速度 |
  |---|---|---|
  | `SPEED BRIDGE` | Shift + S，全程按住 Shift | 12 tick/方块 → **1.67 方块/秒** |
  | `DIAGONAL 45` | Shift + S + D，视角与桥线成 45° | 10 tick/方块 → **2.00 方块/秒** |
  | `SNEAK TOGGLE` | 放块 → 松 Shift → 贴边按回 | 7 tick/方块 → **≈3 方块/秒** |
- 方向换算：`travelYaw(cameraYaw, forward, strafe)`。S = 相机 +180°，
  S+D = 相机 +225°（与桥线正好 45°），`suggestedCameraYaw` 是它的反解，
  测试里做了往返校验。
- 蹲起循环：`RELEASE`（放完块松开 `keyBindSneak`，恢复全速）→ `RESNEAK`
  （松手预算 1~4 tick、余量 ≤ 0.12、或离地，任一成立立刻按回）→ `WAIT_EDGE`
  （贴着边缘，余量 ≤ `edgeMargin` 才按右键）→ 放置。贴边余量用
  `getCollidingBoundingBoxes` 沿行进方向 0.05 格步进取样。
- “准星对准方块边缘”：目标格六个支撑面按“支撑块越靠身体这块越优先”打分，
  点击点落在面的上沿（`y += 0.25`，水平 ±0.28 抖动），俯角通常 65°~77°。
- 放置时序与 Scaffold 相同：tick N 把 aim 写进 C03，tick N+1 用**服务端已收到的
  角度**做 ray trace，命中支撑面才发 C08，否则重试 1 次；`claimPlacement()`
  保证一 tick 一条 C08，`minPlaceInterval`（12/10/6）挡住连发。
- 不跳：没有 telly 的 75.5° 俯角表、不碰 `jumpTicks` —— 教材的手法本来就不跳。
- `SAFETY`：踩空（余量 < 0 且 `motionY < -0.08`）时往下补一块。
- HUD：`SPEED HUD` 卡片显示模式、阶段（IDLE/EDGE/RELEASE/RESNEAK）、实测
  `x.xxb/s` 与已放方块数，进度条按该模式目标速度归一。
- 实现位置：`mc/GodBridgeFeature.java`（状态机）、`mc/GodBridgeHud.java`（速度卡）、
  `java/module/movement/GodBridge.java`（开关）；静默转向复用
  `ScaffoldMovementInput` hook。
- 回归测试：`tools/godbridge-test/run.sh`（73 项：速度、方向、循环、放置闸门、
  aim 数学、贴边阈值、源码结构与接线）。
- 实现说明：`design/GODBRIDGE_HANDOFF.md`（教材逐步对照、调参表、已知边界）。

### Scaffold2 教材搭路辅助

- 配置路径：`movement.scaffold2`，默认关闭，默认键位 **G**；旧 Scaffold 默认不绑定热键，GodBridge 仍绑定 **N**。
- `STRAIGHT`：按住 S 和右键搭路并维持潜行；`DIAGONAL 45`：按住 S+D 和右键搭路并维持潜行，视角由玩家按教材摆成与桥线 45°；`SNEAK TOGGLE`：成功放置后松开 Shift 指定 tick 数，再自动按回。
- 模块使用玩家当前准星，不自动转动视角；只在准星命中方块、手持方块物品且按住右键时尝试放置。`RELEASE` 设置蹲起模式松开 Shift 的时长。

### SafeWalk 方块边缘潜行（从 Scaffold 的 eagle 拆出的独立模块）

- 配置路径：`movement.safewalk`，默认关闭，默认不绑定热键。原来的实现只有
  `scaffold.eagle` 一行，且只在 `Scaffold` 的 `NORMAL` 模式下生效；拆成独立模块后
  任何场景都能用，不用先开 Scaffold。
- 做法：站在方块边缘时替玩家按住 `keyBindSneak`（原版潜行键）。只改
  `KeyBinding.pressed`，本 tick 的 `updatePlayerMoveState()` 会把它读成
  `MovementInput.sneak`，随后**原版自己**发 C0B `START/STOP_SNEAKING`。
  不写 motion、不改 rotation、不发任何伪造包 —— 服务端看到的就是一次普通潜行输入。
- 探针：沿本 tick 的输入方向（与 vanilla `moveFlying` 同一个换算
  `x = strafe*cos - forward*sin`、`z = forward*cos + strafe*sin`）把碰撞箱中心前推
  `DISTANCE` 格，在**脚底以下**放一个 0.12 宽的探针盒，用
  `World.getCollidingBoundingBoxes` 查支撑面；查不到就是“再走一步会踏空”。
  探针整体压低到脚下，贴着墙走不会被脚下高度的方块误判。
- 设置：
  | 设置 | 默认 | 说明 |
  |---|---|---|
  | `DISTANCE` | 0.30b | 探针前伸距离；越大越早接管（0.05~0.60） |
  | `ONLY GROUND` | 开 | 只在地面生效 |
  | `REQUIRE MOVING` | 开 | 只有按着 WASD 才生效 |
  | `RESPECT SNEAK` | 开 | 玩家自己按着 Shift 时不接管（原版潜行本来就防边缘） |
  | `HOLD` | 2t | 松开边缘后继续按住的 tick 数，避免贴着边缘走时逐 tick 抖 SHIFT |
- 让位：`Scaffold` 的 `Eagle` / `Sneak`、`GodBridge` 的 `SNEAK TOGGLE` 写的是同一个
  `keyBindSneak`。它们活跃时 `SafeWalk` 整体让位（状态 `YIELD`），一根手指都不碰这个键；
  想让 SafeWalk 接管全局潜行，把 Scaffold 的 Eagle/Sneak 关掉或关掉 Scaffold 即可。
- 配置写在 `config/openmaver.properties`：`safewalk.distance` / `safewalk.onlyGround` /
  `safewalk.requireMoving` / `safewalk.respectSneak` / `safewalk.holdTicks`（settings.version 22 起）。
- 实现位置：`mc/SafeWalkFeature.java`（行为）、`java/module/movement/SafeWalk.java`（开关）、
  `OpenMaverFeatures.onTickStart()`（在三套搭路之后驱动一次）、
  `ScaffoldFeature.ownsSneakKey()`（让位判定）、`GuiOpenMaverClickGui`（设置面板）、
  `OpenMaverHud`（`EDGE` / `0.3b` 参数摘要）。
- 回归测试：`tools/safewalk-test/run.sh`（61 项：决策表、滞回、方向换算、探针几何、
  配置往返、只写按键不写 motion/rotation、接线与 GUI 断言）。
- 实现说明：`design/SAFEWALK_HANDOFF.md`。

### KeepSprint 全方向疾跑（移动方向转向）

- 模块：`movement.keepsprint`，默认关闭、不绑定热键。开启后默认使用 `ROTATE`：
  在原版更新按键后、移动物理前，将 yaw 对齐本次移动方向，输入改为前进。
  后退转 180°，侧移转 ±90°，斜向按方向转 45° / 135°；保持原来的世界移动方向。
- 本 tick 的跳跃、移动物理、原版 C03 使用同一个 yaw，C03 发出后的
  `ClientTickEvent.END` 恢复镜头。pitch 不变，不额外构造移动包，不写 motion。
- 只使用原版疾跑 modifier，ROTATE 会摘掉旧 OMNI 补偿，不额外放大空中速度。
  潜行、使用物品、撞墙、低饱食度、失明、骑乘、飞行、死亡、GUI 时让位。
  斜向变为单一前进键，速度使用原版前进输入的 0.98 阻尼。
- Scaffold / Scaffold2 / Telly / GodBridge / KillAura 开启时显示 `YIELD`，不争用输入与朝向。
  Scaffold 的 `Normal` 模式会完整暂停 KeepSprint（`YIELD SCAFFOLD`），释放它留下的疾跑、
  移除旧速度补偿并停止攻击后的疾跑补包；关闭 Scaffold 后按原开关自动恢复。
  Velocity 空闲时与 KeepSprint 共存，仅实际写入输入或转向的 tick 显示 `YIELD VELOCITY`，
  接管结束后自动恢复。其它 mod 的自定义 MovementInput 保留，显示 `NO HOOK`，
  不自动退回旧的速度补偿。
- 设置写在 `config/openmaver.properties`：`keepSprint.rotate=true`（缺省也为 true），
  ClickGUI 对应 `ROTATE`。关闭后使用旧 OMNI；`OMNI BOOST` / `PAUSE SNEAK` /
  `PAUSE ITEM` 只控制旧模式。`HIT RESEND` 控制攻击后的 C0B 冲刺恢复，两模式共用。
- 构建：`cmake --build build -j 4`；回归：`bash tools/keepsprint-test/run.sh`。
  测试包含打包后的 SRG 输入 hook、八方向切换、移动朝向/速度、C03 边界和镜头恢复。
  这些是离线验证，Grim `Simulation` 的联机结果仍需实测。
- 实现说明：`design/KEEPSPRINT_HANDOFF.md`。

### Velocity 防击退

- Velocity 保留 `JumpReset`（新配置默认）、`NoXZ`、`Mix`，并新增来源于 `../OpenAugustus-Maver` 字节码的 17 个模式：Basic、Legit、PushGround、Push、Intave/PolarFlag、Intave、Intave13、Reverse、Spoof、SafeWalk、OldMinemen、Karhu、CustomMotion、BoundingBox、Grim-1.17、HypixelTest、PolarBlock。ClickGUI/HUD 仍显示 `Velocity`，开关与热键保留 `movement.attackreduce`。
- **Movement → Velocity** 左键控制模块开关、右键展开列内参数；Movement 列平滑加宽至能完整显示当前配置文字，收起时恢复。**Mode** 左键向后切换、右键向前切换，参数按模式及子选项显示，超出窗口时在列内滚动；调整选项、拖动结束及关闭界面时保存设置。
- `NoXZ`：所有 S12 在主线程收到后立即应用，不额外延迟，也不缩放速度或缓存其他入站/出站包；不会因受击额外强制跳跃。地面有目标且疾跑时安排原版攻击，空中只保留最多 12 tick 的落地补刀意图。落地补刀使用当时速度，不重新施加旧 S12；无目标、超时、新击退、校正或退出环境会清理旧意图。Aura 目标优先，否则取准星生物，眼睛到可见目标 AABB 距离上限为 3.0，减速由原版伤害结果决定。
- `JumpReset`：参考 `~/Downloads/script.lua` 的短按跳跃与 GUI 暂停思路；自己的正 Y 且带水平分量的 S12 立即应用，地面和空中受击都可触发。下一次有效输入短暂向前疾跑 2 tick：地面写入一次跳跃输入；空中保持原包 Y，在有效 Aura/准星目标上尝试一次原版疾跑攻击，水平减速只由原版伤害结果决定。没有有效目标时仅接管移动输入；攻击名额忙时最多等到下一输入 tick，不与 AutoClicker/Aura 同 tick 重复攻击。随后归还输入和模块开启的疾跑；不在空中调用 `jump()`，不缓存/改写/回放 S12，不伪造 onGround，不改写相机或 Timer。
- JumpReset 参数：`Chance=100%`（每次有效受击触发概率）、`Accuracy=100%`（下一次输入准时响应概率；失准时仅输入延后 1–2 tick）、`Only When Targeting=false`（开启后需要 Aura 或准星可见生物，距离 ≤ 3.0）、`Water Check=true`（开启后水中跳过；关闭仅放开水中检查）。执行前重查条件；离地后仍可执行重置，尚未执行的空中意图在落地时取消，避免补跳。目标失效、GUI、潜行、使用物品、Scaffold/GodBridge、校正及退出同样取消旧意图，缺少输入回调时最多 3–5 tick 过期。
- `Mix`：选择性缓存速度、实体移动、事务和传送包，落地或连续空中 24 tick 释放；支持受伤跳跃、短时方向接管、静默转向及 Aura 双击。两次攻击分配到两个空闲 tick，按执行时的疾跑状态走原版物理；与 AutoClicker/Aura 共享每 tick 的实体攻击名额。
- NoXZ 参数默认：`Attack Amount=5`、`Sprint State Check=true`，`Instant Attack=false`；Mix 的开关默认 false。`Instant Attack` 按等待落地时长增加最多 3 次补刀，与基础攻击一起分摊到正常 tick；始终保留原 Timer 倍率，并与其他模块共享每 tick 的实体攻击名额。
- 配置版本沿用 23：模式仍保存为 `velocity.zen.mode` 字符串，原有三个模式索引和参数不变；新增参数独立保存于 `velocity.augustus.*`。缺省/无效模式仍使用 `JumpReset`，旧 `Sprint Reset` / `Jump Reset` 迁移规则保留。界面按模式及子选项显示参数，Push Expert 的逐 hurtTime 参数可滚动访问。
- Augustus 公共设置包括 S 输入/LongJump/着火过滤、0–400ms 的双向 Lag、伤害声音过滤，以及 S00/S32/S48 的地空条件与取消次数。SafeWalk/BoundingBox/CustomMotion 接入实际移动与碰撞；Intave 接入原版成功攻击分支及无副作用的单步位置预测。来源→实现→测试对照、完整参数表、保留的来源异常行为和适配限制见 [移植记录](design/augustus-velocity-reference/PORT_AUDIT.md)。
- 1.8.9 的移动/碰撞/攻击钩子通过 mods JAR 的 coremod 或动态注入的 JVMTI 重转换安装；注入明确失败时 Augustus 模式暂停并显示 `HOOKS UNAVAILABLE`。构建：`cmake --build build -j2`；行为测试：`tools/attackreduce-test/run.sh`；真实类/重转换验证：`python3 tools/augustus-velocity/test_transform.py`。本地验证不能证明游戏内及服务器端效果，Java 8 字节码已检查，实际 Java 8 VM 尚未运行。
- 原有模式发起的实体攻击统一为必要的 C0B 疾跑同步 → C0A 挥手 → C02 攻击，继续共享每 tick 名额。旧模式到期入站队列在模块动作前回放，Motion/禁用/切模式中产生的迟到回放推迟到下一次准备阶段；连续 S12 先释放旧队列再接收新击退。Augustus 的 Lag 与 Flag 按各自来源状态机保序释放，Intave 的攻击钩子只调整已发生的原版攻击，不另发攻击。
- JumpReset 调试中 `PASS AIR RESET READY` 仅表示意图就绪；`RESET JUMPRESET` / `RESET AIR ATTACK` / `RESET AIR MOVE / NO TARGET` 分别记录地面输入、实际发起的空中攻击和无目标移动，附动作后的本地速度。`AIR ATTACK` 不是服务端命中确认。已删除曾导致空中额外 `0.42` 起跳的直接 `jump()` 路径；本地回归测试验证物理与包序，服务器端效果仍待复测。
- 独立的声音兼容 hook 将旧协议未注册的 `damage.thorns` 映射到 1.8 原生 `game.player.hurt`，保留位置、音高和音量；Velocity 关闭时仍生效。
- 同一兼容 hook 在主线程按序检查 S3E 队伍包，忽略针对不存在队伍的更新、删除和成员操作，避免原版 `handleTeams` 空指针刷屏；Velocity 重放入口也做同样检查。S01/S07 切换世界后，已入队且仍属于同一连接的数据包继续处理。

### FPS / CPS 统计 HUD

- 配置路径：`render.fps` / `render.cps`，**默认开启**，位置固定在左上角。
- 布局：CPS 按键面板在最左，FPS 面板在它右边。
- 视觉：**正方形深色半透明底**，与右上角功能栏（`OpenMaverHud` 的模块列表）
  完全同一套 —— `0x000000` + alpha `0x80` 的直角矩形，无圆角、无描边，
  阴影只做 `1px/2px` 偏移。之前这里用的是圆角灰块，和功能栏摆在一起像两套 UI。
- FPS：统计每秒 `RenderGameOverlayEvent.Post` 的帧数，满 1 秒结算一次；
  首个统计窗口完成前显示 `FPS --`。
- CPS：每帧轮询鼠标左 / 右键的按下边沿，分别显示 `L`（左键）/ `R`（右键）
  最近 1 秒内的点击次数，数值会随时间自然回落。
- 实现说明：绘制在 `mc/StatsHud.java`，由 `OpenMaverHud` 在
  `RenderGameOverlayEvent.Post` 中调用；ClickGUI 打开时会作为模糊背景的一部分保留。

### Chest ESP 箱子透视

- 配置路径：`render.chestesp`（默认关闭，默认键位 **P**）。
- 每帧扫描 `World.loadedTileEntityList`，对箱子 / 大箱子 / 末影箱
  （可选漏斗、熔炉、发射器、投掷器、酿造台）画 1.5px 穿墙线框，按距离过滤。
  - 设置项：`RANGE`(8~128m)、`THICKNESS`(0.5~4px)、`OPACITY`(0.15~1)、
    `COLOR`(Gold / Cyan / Violet / Red / White)、`STORAGE BLOCKS`(是否包含机器容器)。
- 只读数据，不发送任何包。
- **箱子内容预览已移除**：原来贴在左上角 CPS 下方的 `Chest Info` 信息栏（含
  `NO DATA - OPEN ONCE` 提示、`ROWS`/`WIDTH` 设置、准星箱子的白线高亮、`render.chestinfo`
  配置项与模块）整体删除。现在搬运走开箱会话（可见界面 / 静默两种，见下一节），客户端不再需要维护
  「这个箱子我开过、里面有什么」的预览数据。
- 实现说明：`mc/ChestFeature.java`（绘制挂在 `RenderWorldLastEvent`），开关模块为
  `java/module/render/ChestEsp.java`。

### Projectiles 投射物提示

- 配置路径：`render.projectiles`，默认关闭，默认不绑定按键。
- 对一定范围内的箭、投掷物和火球绘制穿墙位置框、玩家指向线及短程预测轨迹；范围可在
  ClickGUI 中调整（8~128 格，默认 64）。预测轨迹按当前速度和近似重力外推 20 tick，未做方块碰撞模拟。
- 只读取客户端实体状态，不发送网络包。实现位于 `mc/ProjectileFeature.java`，由
  `RenderWorldLastEvent` 驱动。

### No Hurt Cam 受击镜头关闭

- 配置路径：`render.nohurtcam`，默认关闭，默认不绑定按键。
- 渲染帧内临时清除本地玩家的受击计时，并在帧结束恢复，关闭受击镜头晃动；不改服务端生命值或伤害结算。
- 实现位于 `mc/HurtCameraFeature.java`，由 `RenderTickEvent` 驱动。

### Chest Stealer 开箱搬运（拾取 / 整理 / 存入）

- 配置路径：`player.cheststealer`，默认关闭，默认不占按键（ClickGUI 里开）。
- **两种开箱方式**（`SHOW CHEST GUI` 开关，默认开 = 可见）：
  - **可见（默认，`chest.stealer.hideGui=false`）**：对着箱子按拾取键（或准星停留）时，
    客户端替玩家发**一次**右键开箱包，服务端照常把容器开起来并同步内容，原版
    `GuiChest` **就留在屏幕上**；搬运是在这个真界面里完成的（等于玩家自己开着箱子
    shift-click），拿完再按「玩家关箱子」的流程发 `C0D` 关窗 + 关界面退出。
    服务端与反作弊看到的是「打开了箱子 → 在界面里拿了东西 → 关掉界面」，
    客户端与服务端的容器状态始终一致。
  - **静默（`chest.stealer.hideGui=true`）**：界面在**同一个 client tick 内**被
    `Minecraft.displayGuiScreen(null)` 收起来，屏幕上看不到箱子 GUI，但仍然 shift-click
    搬运。这条路径「没有界面却在点窗口」，正是 Grim 那类 click window 预测检查最容易
    盯上的形态 —— 只在需要完全隐藏界面时开。
- **为什么必须先让原版把界面挂一遍**：1.8.9 的 `NetHandlerPlayClient.handleOpenWindow`
  先让 `GuiChest` 构造出 `ContainerChest`，再由 `Minecraft.displayGuiScreen →
  GuiScreen.setWorldAndResolution → GuiContainer.initGui` 把容器挂到
  `player.openContainer`，**最后才写入服务端的 `windowId`**。所以
  `ChestFeature.onGuiOpen` **不取消 `GuiOpenEvent`**：让原版把容器、`windowId`、
  S30 窗口内容整套流程跑完（槽位内容只有窗口号对得上才会落到容器里），只是记下
  「这一次箱子会话开始了」。以前「在事件里取消 + 自己 `setWorldAndResolution` 抢容器」
  拿到的是 `GuiChest` 自己 new 的容器，`windowId` 停在 0（背包窗口），服务端发的 S30
  内容被整包丢掉、C0E 点击也不认，表现就是「箱子开了、界面也没弹，但一件都搬不走」。
- **内容同步门（S30）**：`ChestPacketHook` 把 handler 插在 Netty 管线的
  `packet_handler` 之前，记下服务端 `S2D` 开窗 / `S30` 窗口物品 / `S2E` 关窗的窗口号。
  只有在**本次会话开始之后**收到过这个 `windowId` 的窗口物品包，才会动手点格子 ——
  人也是看到箱子里的东西才点的。两条免等捷径（本机实测修复）：
  - **本地容器已有内容就直接开搬**：`chestSideHasItems()` 扫描容器槽位，箱子侧已经有
    物品就说明内容早落到容器里了，不再等服务端 S30 的记账；
  - 钩子晚挂 / 版本改包名时，兜底从 `OPEN DELAY + 12 tick`（≈600ms）收到
    `+ 4 tick`（≈200ms），拿不到内容就早一点收摊。
  - 包钩子现在**每 2 tick 重试挂载**（原来是每 40 tick ≈ 2 秒）：进服 / 重连之后
    第一次开箱不再整场丢 S30 记账（本机日志里「开箱 0.9s 才点第一格」就是它）。
  - 服务端自己关窗（走远 / 死亡 / 管理员指令）时，会话立刻作废，绝不对着已经不存在的
    窗口继续点。
  - 界面被玩家按 E 关掉、但服务端容器还挂着时，也立刻发 `C0D` 收摊并停止搬运。
- **一件一件拿（`ONE BY ONE`，默认开）**：每批只 shift-click **一格**箱子，下一格由
  **人类化节拍（`HUMANIZE`，默认开）** 决定：每格间隔是 **100~160ms** 的随机毫秒
  （硬下限 `MIN_CLICK_INTERVAL_MS = 80`，配置写更小会被夹上来），不是「每 tick 一格」
  的 50ms 等距；每连续 4 格之后才有 6% 概率插一段 200~380ms 的停顿，首格前还有一次
  随机反应时间（80ms + 0~150ms 抖动），关箱前等 2~5 tick。
  10 格满箱实测 ≈1.0~1.6s（客户端掉帧时按下面的「追赶」补点，最多 2 格/tick）。
  `CLICKS/TICK` 只在同时关掉 `ONE BY ONE` 和 `HUMANIZE` 时才生效（那时一批可连拿 N 格）。
- **点击包必须排在 C03 之前（本机实测修复，Grim `Post` 的根因）**：搬运逻辑现在挂在
  `ClientTickEvent.START`，和玩家自己在 `GuiContainer` 里点鼠标的包序一致
  （`C0F` ack（tick 开头的 scheduled task）→ `C0E` windowClick → `C03` flying）。
  以前挂在 tick **END**：每个 `C0E` 都跨了 tick 边界，服务端的 transaction ack 必然
  落在「END 的 `C0E`」和「下一个 `C03`」之间 —— Grim `Post` 的判据正是
  「`C03` 之后、下一个 `C03` 之前出现 action 包 + 合法 transaction」，
  于是**每一条搬运点击后面都跟着一条**
  `[GrimAC] Mincx1203 failed Post (vl:x.x): click window v1.8`（本机日志 13:30
  一次 10 格的箱子里 vl 从 0 涨到 13）。改到 START 之后这条包序特征消失。
- **为什么不再「一格一 tick」**：本机在 `mc.loyisa.cn` 的日志里，6 格物品在
  **12:55:23 一秒内、250ms 总时长**搬完（即每格精确 50ms），随即连续 6 条
  `[GrimAC] Mincx1203 failed Post (vl:0.0): click window v1.8`，服务器同时广播
  `CCBlueX ... 1504ms / 250ms / 6`（开箱到搬完的总时长 / 阈值 / 物品数）。
  结合 Grim 2.0 `Post` 的源码，**真正被判的依据是「包序」**：`C03` 之后的
  action 包只要没被下一个 `C03` 清掉，中间来了合法 transaction 就 flag。
  `HUMANIZE` + START 发包一起解决这件事：
  - 所有 `C0E` 都在本 tick 的 `C03` 之前发出（`ClientTickEvent.START`），包序 = 真人；
  - 间隔取随机毫秒（100~160ms）并刻意避开 50ms 的整数倍，密度回到真人区间；
  - 同一 client tick 内最多 2 个 `windowClick`（`MAX_CLICKS_PER_TICK`，只在客户端
    掉帧、节拍落后时才补到 2；正常 20 TPS 永远是 1）；
  - 关箱延迟、下一个箱子的冷却都随机化（关 2~5 tick；两箱之间 0.45~1.35s），
    `SWEEP CD` 只是下限。
- **连开箱子不再互相顶掉（本机实测修复）**：`GuiOpenEvent` 里以前只要 `armed=false`
  就先把「上一个会话的容器」关掉（C0D + `openContainer` 换回背包）。快速连开或服务端
  关窗包晚到时，被关掉的其实是**刚打开的新 GuiChest** —— 界面还在屏幕上、`openContainer`
  却已经是背包容器（`windowId=0`），主循环一句 `windowId<=0` 直接 return，
  表现就是「点开箱子直接不拿」，玩家一关界面再补一条 `chest session gone`。
  现在任何箱子界面都接管成新会话（`startChestSession(gui, owned)`），
  并且主循环发现「界面开着但容器是背包」时会从 `inventorySlots` 把容器和
  `windowId` 恢复回来（日志 `recovered chest container from gui`）。
- **过期关窗包拦截**：1.8.9 的 `handleCloseWindow` 不比较窗口号，任何 S2E 都会
  `closeScreen()`。`ChestPacketHook` 现在按 `clientWindowId` 判断，窗口号不匹配的
  S2E 只记账、不往下传；同时关窗后 450ms 内不再自动开下一个箱子
  （`CLOSE_SETTLE_NANOS`），避免旧窗口关窗包追击新窗口。
- **开箱后不再白等（本机实测修复）**：1.8 服务端把 S2D 与 S30 放在同一次 flush，
  Netty 线程常常先处理完 S30、主线程下一 tick 才建会话。以前严格要求
  `netItemsNanos >= sessionStartNanos`，这种同批到达被判成「内容没同步」，
  白等 `12 tick`（≈600ms）才走 fallback ——「打开箱子一两秒才动手」的另一半就是它。
  现在 `contentSynced()` 允许 S30 比会话开始早 400ms（`WINDOW_SYNC_TOLERANCE_NANOS`），
  内容到齐就立刻按节拍点第一格。
- **扫箱冷却（`SWEEP CD`，默认 450ms）**：搬完一个箱子到对下一个箱子动手之间的最短间隔，
  `HUMANIZE` 打开时会再叠加 0~900ms 随机量。以前写死 1.2 秒，连续扫箱时一半时间在干等。
- **刚搬空的箱子 1.5s 内不再扫（`SWEEP_REVISIT_NANOS`）**：本机日志里关箱 0.66s 之后
  又对同一个箱子发了开箱包，窗口里 0 件物品 —— 白花一次往返，还让服务端的开箱统计
  多记一条空箱。
- 等窗口期间**绝不重发**右键包（`openPacketPending`），否则服务端会反复关旧容器
  开新容器，手上挂着的容器随即作废（以前日志里一秒能出现三条 `send open packet`）。
- **识别边界的坑**：背包界面 / 创造模式物品栏（`GuiInventory` / `GuiContainerCreative`
  + `ContainerCreative`）永远不算箱子。创造物品栏有 45 个页签槽位、槽位里的
  `inventory` 是 `InventoryCreative`（不是玩家背包），旧版「槽位 > 36 且有箱子侧」
  的兜底会把它判成箱子 —— 于是按 E 打开物品栏时被开箱逻辑接管：容器被换成
  `ContainerCreative`、界面被取消，玩家看到的就是「物品栏打不开 / 一打开就没了」。
  现在 `isChestScreen` 先按类名排除 `guiinventory` / `creative`，接管会话还要过
  更严格的 `isChestGuiStrict`（容器类名必须真是 chest / hopper / shulker / furnace…）。
- 搬运原理：每一批从**第一个箱子格开始按槽位顺序（从上到下、从左到右）**扫一遍，
  对命中的第一格调用 `PlayerControllerMP.windowClick(windowId, slotId, 0, 1, player)`
  （shift-click 整摞搬运），等服务端 S32 确认后再扫下一格 —— 一次只拿一格，
  直到箱子空或背包满。每批都重新扫描一次真实容器状态，天然自愈，
  不会对着空槽打空气包。
- **一轮没搬动不会立刻放弃**：服务端的窗口物品包（S30）可能比开窗包晚几个 tick 到，
  会话会在「一件都没搬成」时继续等最多 6 轮，内容晚到也照常搬；以前第一轮空手就
  `silent close ... moving 0`，内容晚到时就是「箱子开了、一件没拿」。
- **过滤不再静默吞掉一切**：`TAKE` 选 `NO JUNK` / `VALUABLE` 时，如果箱子侧有物品但全被
  过滤掉，会打一条 `loot: takeFilter=... 把箱子侧 N 格物品全过滤掉了` 日志，并提示改成
  `0 = ALL`；默认就是 `ALL`（右键箱子全拿）。
- 行为：
  - **自动扫箱（`AUTO SWEEP`，默认开）**：不用按键也不用先开界面 —— 准星在同一个箱子上
    停留 4 tick 就自动开箱并搬空，之后 1.2 秒冷却，避免扫过就乱开。
  - **自动拾取**：开箱后自动把箱内物资搬走；背包满（主背包 36 格全占）自动停手。
  - **快捷键**：`L` 拾取、`G` 整理、`O` 存入（可在
    `config/openmaver-chest.properties` 的 `chest.key.*` 改）。
    未开箱时按任一键：`AUTO OPEN` 开（默认）会先开箱，再自动搬运；
    箱子一直开不了（领地保护 / 距离太远）时 2.5 秒后自动放弃。
  - **整理**：先合并同物品的零散堆叠，再按「名称 → 数量」排序；每一组交换用
    「拿起 → 放下 → 把原槽位剩下的放回去」三次点击完成，动手前先把鼠标上的
    物品塞进背包空槽，避免关界面时把东西掉在地上。
  - **存入**：`STORE` 过滤命中时反向 shift-click（背包 → 箱子），箱子没空槽时停手。
  - **自动退出（`AUTO CLOSE`，默认开）**：确认没有可搬的东西后再等 2 tick 让服务端
    结算完最后一次点击，然后 `EntityPlayerSP.closeScreen()` 发 `C0D` 关窗 +
    `displayGuiScreen(null)` 关界面 —— 和玩家自己按 E 退出箱子完全一样。
    我们替玩家开的箱子（拾取键 / 自动扫箱）无论如何都会退出，否则扫箱没法接着跑下一个；
    玩家自己开的界面尊重这个开关（关掉时不会偷偷关掉你的界面，以前
    「界面一闪就没了、东西一件没动」的观感就是这么来的）。
- 设置项（ClickGUI 中展开 Chest Stealer）：
  - `TAKE`：拾取过滤 `ALL / NO JUNK / VALUABLE`（默认 `ALL`：右键箱子从上到下全拿）。
  - `STORE`：存入过滤 `ALL / JUNK / VALUABLE`（默认 `JUNK`）。
  - `ONE BY ONE`：一件一件拿（默认 `开`）—— 每批只拿一格。
  - `CLICKS/TICK`：一批最多几次 `windowClick`，1~9，默认 `3`
    （`ONE BY ONE` 与 `HUMANIZE` 都关掉时才生效）。
  - `CLICK GAP`：`HUMANIZE` 关掉时，两批点击之间至少隔几个 tick（0~10，默认 `1`）。
  - `HUMANIZE`：人类化节拍总开关（默认 `开`）。关掉即回到旧的「一格一 tick」机械节奏。
  - `CLICK MS` / `CLICK MS+`：两格之间的随机间隔区间（默认 `100`~`160` ms，硬下限 `80`）。
  - `FIRST MS`：每箱第一格之前的额外反应时间（默认 `80` ms，按 tick 取整后最多 2 tick，另加 0~150ms 抖动）。
  - `PAUSE %`：每连续 4 格之后插一段长停顿的概率（默认 `6` %）。
  - `SKIP %`：真人偶尔漏拿一格的概率（默认 `0` %，长时间连续搬箱可开到 3~8 打散统计）。
  - `CLOSE T` / `CLOSE T+`：搬完后等多少 tick 再关箱（默认 `2`~`5`）。
  - `SWEEP CD`：自动扫箱冷却下限（100~3000ms，默认 `450`，humanize 时再叠加 0~900ms）。
  - `OPEN DELAY`：开窗后等多少 tick 再动手，0~20，默认 `1`（humanize 另加 2~4 tick 反应）。
  - `SHOW CHEST GUI`：默认 `开`（可见模式）；关掉 = 静默开箱。
  - `AUTO LOOT` / `AUTO SORT` / `AUTO CLOSE` / `AUTO OPEN` / `AUTO SWEEP`：
    默认 `开 / 关 / 开 / 开 / 开`。
- 配置与持久化：参数写在 `config/openmaver-chest.properties`
  （`chest.esp.* / chest.stealer.* / chest.key.*`），20 tick 检查一次
  外部改动，和 `openmaver.properties` 的其它功能参数互不影响。
- 实现说明：`mc/ChestFeature.java`（会话 / 搬运 / 识别）与 `mc/ChestPacketHook.java`
  （入站窗口包记账），**tick 入口在 `OpenMaverClientHandler` 的 `ClientTickEvent.START`
  阶段**（必须早于原版 `C03`，见上文「点击包必须排在 C03 之前」），
  `GuiOpenEvent` 入口在同文件的 `onGuiOpen`；容器槽位 / 物品名等成员同时按 MCP 名、
  SRG 名、字段类型名三级兜底读取，任一版本名对不上时只会降级，不会把异常抛进游戏循环。
- 兼容性兜底：箱子界面 / 容器的识别是三级判定（`lowerChestInventory` 字段 → 类名 →
  GUI 容器槽位），任何一级能命中就能搬；`runStealer` 出异常只跳过 4 tick（≈200ms）后重试，
  不再永久停用（以前的「一次异常 = 整功能失效」是「箱子开了却没有拿」的元凶之一）。
- 诊断日志：`[OpenMaver] chest:` 前缀同时写 stderr 与 `/tmp/openmaver-chest.log`
  （日志流复用，不再每次 `open/close`），记录开箱包（`send open packet`）、会话接管
  （`visible open accepted` / `silent open accepted`）、静默收起与 `windowId`
  （`silent open ok (... windowId=N)`）、包钩子状态（`packet hook attached` /
  `packet hook unavailable: ...`）、内容门捷径（`content gate: local container already
  has items, skip S30 wait`）、每一次搬运的实际件数与档位（`loot: moved N stack(s) in
  window W (filter=..., free slots=..., next in Xms)`）、**每箱速度小结**
  （`session done: N stack(s) / M click(s), open→first Xms, first→last Yms, total Zms`）、
  退出原因（`visible close after moving N stack(s)` / `chest gui closed mid-session`）、
  以及放弃原因（`silent open dropped: ...`）。箱子「没反应」时先看这个文件：有 `moved`
  就是过滤器/背包满，没有 `open accepted` 就是开箱包没被服务端接受（领地保护 / 距离 /
  反作弊）；`session done` 里的 `open→first` > 300ms 说明开箱往返（RTT）或内容门还在拖。
- 回归测试：`tools/chest-test/run.sh`（离线，用最小 MC 替身驱动真实 `ChestFeature`），
  覆盖「可见模式界面留在屏幕上并在里面搬完再退出 / S30 没到不点格子 /
  一件一件拿（一批只拿一格、等 S32 确认）/ CLICK GAP 隔开点击 / 界面被关掉立刻 C0D 收摊 / 只发一次开箱包 / 静默模式同 tick 收起界面 /
  容器与 windowId 正常挂载 / 点击打在正确窗口上 / TAKE 过滤 / 自动 C0D 关窗 /
  玩家自己开界面照常搬运 / AUTO CLOSE 关掉时全是垃圾也不关界面 / 字段名对不上时的兜底 /
  AUTO SWEEP 免按键开箱 / 创造物品栏不被接管 / 打开别的界面先关容器 /
  按格子顺序从上到下搬空 / S30 内容晚到仍会搬 / 过滤掉一切时容器仍会关掉 /
  人类化节拍（6 格总时长 ≥900ms、相邻两格 ≥150ms、仍照常搬完关箱）」
  共 100 项断言（含「9 格满箱 ≤16 tick 搬完并退出」的机械节奏速度基线、
  humanize 打开后的时长/间隔下限、连开箱子时新界面被接管而不是被 C0D 掉、
  过期窗口 S2E 被钩子拦下、S30 缺失但本地容器已有内容时直接开搬、
  `CLICK MS` 低于 80ms 被夹上来、刚搬空的箱子 1.5s 内不会被重新扫）。

### Inventory Manager 自动整理背包（穿盔甲 / 排快捷栏 / 丢多余）

- 配置路径 `player.inventorymanager`，默认关闭、无默认热键；参数在 ClickGUI 的
  **Player** 分类里展开，写入 `config/openmaver-inventory.properties`（`inventory.*`）。
- 实现分三层，只有最外层碰 MC：
  | 文件 | 职责 |
  |---|---|
  | `mc/InventoryRules.java` | 纯规则表：物品分类（盔甲 → 武器 → 工具 → 方块 → 食物 → 其它）、材质与附魔评分、盔甲槽判定。**不引用任何 `net.minecraft.*`** |
  | `mc/InventoryPlanner.java` | 纯计划器：吃一份 45 格玩家背包窗口快照，吐出「下一步做哪一件小事」以及它的点击序列。每做一步都重新读真实背包，所以中途被玩家插手也不会跑偏 |
  | `mc/InventoryManagerFeature.java` | MC 侧执行：把 `ItemStack` 读成纯数据、把动作翻成 `PlayerControllerMP.windowClick`，并按真人手速节拍发包 |
- 快捷栏布局（需求原文）：**1 武器 → 2 方块 → 3 食物（金苹果优先）→ 4 工具（镐优先）**，
  5~9 依次补方块 / 食物 / 工具的剩余堆叠；没有的类那一格空着，不会被后面的类顶上来。
  杂物（末影珍珠、桶等）挪回背包，不占快捷栏。
- 自动穿盔甲：按 头盔 / 胸甲 / 护腿 / 靴子 逐件比较，**评分 = 护甲点数 × 10 +
  保护类附魔 × 2 + 剩余耐久 × 0.5**（皮革 1/3/2/1、金 2/5/3/1、锁链 2/5/4/1、
  铁 2/6/5/2、钻石 3/8/6/3）。背包里更好的那件换到身上，旧的放回原格；
  更差的不会被换上（不会把钻石胸甲换成皮革胸甲）。
- 丢多余：剑超过 `KEEP WEAPONS` 把、某种盔甲备用件超过 `KEEP ARMOR` 件、方块总堆叠超过两组时，丢弃评分较低的多余物品（默认不留备用盔甲、武器只留 1 把）；
  勾上 `DROP TOOLS` 后同家族工具超过 `KEEP TOOLS` 把时，丢掉评分最低的那些。
  弓不参与「多余武器」统计，斧子按工具算。
- 点击语义（和玩家自己开背包点格子完全同一条路径，全部走 windowId 0）：
  | 动作 | 点击 |
  |---|---|
  | 换快捷栏 | `mode 2`（数字键动作），一次点击，等价于玩家按 1~9 |
  | 换盔甲 / 互换 / 挪位 | `mode 0`（拿起 → 放下 → 余数放回） |
  | 合并同物品的两摞 | `mode 0`（拿起小的 → 点大的 → 余数放回） |
  | 丢东西 | `mode 4`（丢弃键，整摞丢出） |
- 节拍：一步动作之间隔 `CLICK GAP` 个 tick；`HUMANIZE` 打开时改为
  `CLICK MS`~`CLICK MS+`（默认 90~200ms）随机，每 tick 最多 3 次点击。
  整理完（规划器返回空）后按 `IDLE`（默认 40 tick）再扫一遍背包，不会每 tick 重算。
- 边界：
  - 头颅、箱子（含陷阱箱 / 末影箱）、指南针、时钟、下界之星、纸 / 书等功能物品，
    以及带自定义名称或 Lore 说明的物品，始终保留原位：不移动、不合并、不自动穿上或丢弃，
    也不计入多余物资数量。快捷栏排序会避开它们占用的格子；固定分类格被占用时，
    对应普通物资使用 5~9 中空闲的补位格。普通改名装备同样受保护。
  - 合并物品时同时比较 NBT，避免把外观相同、服务端数据不同的物品当作同一摞。
  - 只在 `openContainer == inventoryContainer`（窗口号 0）时动手；开着箱子 / 漏斗 /
    创造物品栏时完全不碰，那段时间交给 Chest Stealer。背包界面开着时点的是同一个窗口；
    界面关掉时服务端的 `openContainer` 仍是背包，所以两种情况都会自动整理。
  - 光标上还拿着东西（玩家正在拖）时不动手，等玩家放完。
  - 聊天 / 设置 / 其它界面里不偷偷点格子。
  - 同一动作连续 8 次没生效（服务端拒绝、窗口错位）就停 1 秒再来，避免刷包。
- 发包时机：挂在 `TickEvent.Phase.START`，`windowClick`（C0E）排在本 tick 的 C03 之前，
  与 Chest Stealer 记录的 Grim `click window` 包序问题同一个处理方式。
- 回归测试（不需要 MC / Forge / Netty）：

  ```bash
  bash tools/inventory-test/run.sh
  ```

  用假容器按原版 `windowClick` 语义（mode 0 拿起/放下/交换/合并、mode 2 数字键、
  mode 4 丢弃）执行规划器给出的点击，跑到规划器说「已整理好」为止，再断言布局与稳定性：
  布局 / 盔甲不降级 / 保护附魔优先 / 保留把数 / 多余工具 / 已整理不再动 /
  满背包不死循环 / 固定四格占位 / 大厅功能物品保留 / 保护槽位避让 /
  300 组随机普通背包与 200 组混合功能物品背包收敛。
  构建项目后可运行 `bash tools/inventory-test/run.sh --feature`，额外检查自定义名称、Lore、
  NBT 合并身份与 MCP / SRG 运行期字段、方法读取。

### Target HUD 目标信息面板

- 配置路径：`render.targethud`，默认关闭，默认键位 **H**。
- 行为：**仅在准星命中生物时显示**（玩家、怪物、动物均可），准星移开立即隐藏；
  不再自动索敌，也不保留历史目标。
- 卡片内容（极简版）：
  - 准星右侧的小卡片，只保留圆角头像、名称、细血条三样信息。
  - 头像：玩家读取 `AbstractClientPlayer#getLocationSkin` 绘制真实皮肤头；
    非玩家生物或读取不到皮肤时统一使用 Steve 头像。
  - 血条：颜色随血量从绿（满血）过渡到橙、再到深红（低血），无刻度、无发光；
    深色纯色底 + 1px 描边，整体随 GUI 缩放设置缩放。
  - 血量动画：数字和血条一起平滑逼近真实血量（掉血约 140ms、回血约 80ms 的
    时间常数，与帧率无关）；切换目标时直接吸附到新目标，不会从上一个目标滑过来。
  - 头像采用圆角裁切；目标受击时整体染红，并随受击时间平滑恢复。
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
  在 `range` 内通过 `RenderManager.renderEntitySimple` 渲染完整玩家模型，
  因此可以穿墙看到玩家皮肤、装备和手持物品；模型外侧绘制 1.5px 描边。
- 穿墙但保持模型完整：透视 pass 不直接关闭深度测试，而是两遍渲染——第一遍
  `GL_ALWAYS` 只写模型自身深度（忽略墙体），第二遍 `GL_LEQUAL` 正常上色，
  这样墙体挡不住玩家，玩家模型内部（头/身体/四肢/帽子层）仍保持正确的遮挡关系。
  透视 pass 通过名称渲染事件屏蔽额外标签，避免名字重影。
  每次模型绘制后同时恢复 GL 状态、GlStateManager 缓存和光照坐标，
  避免影响手持物品、物品栏和下一位玩家的亮度或材质；异常路径同样恢复。
- 颜色：描边颜色 `COLOR = Health`（默认）时按目标血量在绿 / 琥珀 / 红之间切换；
  也可固定为 Orange / Cyan / Violet / Red。皮肤模型本身保持原版材质颜色。
- 设置项（ClickGUI 中展开 Player X-Ray）：
  - `RANGE`：`playerxray.range`，8~128 格，默认 `64`。
  - `COLOR`：`playerxray.color`，Health / Orange / Cyan / Violet / Red，默认 `Health`。
- 与既有 **Player ESP** 的区别：Player ESP 是通用生物线框（可包含怪物/动物）；
  Player X-Ray 只处理玩家，并额外提供穿墙皮肤模型与血量配色描边。
- 实现说明：`mc/OpenMaverFeatures.renderPlayerXray()`，开关模块为
  `java/module/render/PlayerXray.java`。

### Motion Blur 运动模糊

- 配置路径：`render.motionblur`，默认关闭，默认键位 **M**。
- 效果：转头、跑动时世界画面沿运动方向拉出方向性拖影（快速甩视角时边缘
  拖得更长），移动停下后立刻恢复清晰。只使用当前帧，**不做历史帧累积**，
  因此没有残影 / 双影 /“晕糊”的观感。HUD、ClickGUI、聊天栏不会被模糊。
- 实现（`mc/MotionBlur.java`）：
  - 把当前世界画面整帧拷贝到 **全分辨率纹理**，然后在一个全屏 pass 里沿
    运动方向做 **12 次采样**，用固定抖动打散条带，再按 alpha 叠回主帧缓冲。
  - 拖影由两部分合成：相机 yaw / pitch 角速度（大头靠近屏幕边缘的径向拖影，
    小头为全屏统一位移）和玩家水平 / 前后移动速度（平移拖影 + zoom 拖影）。
  - 角速度做 ~50ms 指数平滑，避免 20 TPS 下视角只在 tick 跳变造成的闪烁。
  - 玩家静止时整条管线直接跳过：不抓帧、不跑 shader、不占显存带宽。
  - 优先使用 `EXT_framebuffer_blit` 拷贝主帧缓冲；不支持时回退到
    `glCopyTexSubImage2D`，两者都不可用时静默停用。
- 设置项（ClickGUI 中展开 Motion Blur）：
  - `STRENGTH`：`motionblur.strength`，0.05~1.0，默认 `0.70`；同时影响拖影
    长度与模糊叠加强度，调低更清爽，调高更明显。
- 实现说明：渲染挂在 `RenderWorldLastEvent` 之后（`OpenMaverClientHandler`），
  开关模块为 `java/module/render/MotionBlur.java`，不依赖 Mixin，纯 Forge 事件可用。

### FastPlace 快速右键

- 配置路径：`player.fastplace`，默认关闭，默认键位 **F**。
- 参考上级 `LiquidBounce` 的冷却事件思路：只在原版 `rightClickMouse()` 设置冷却时，
  将冷却值替换为所选 Tick。输入、射线检查、交互与放置均由原版处理。
- ClickGUI 仅保留 **TICK**：`fastplace.delayTicks`，整数 **0–5t**，默认 **1t**。
  `0t` 不添加冷却，连续按住时仍由游戏 tick 处理；`4t` 为原版冷却，`5t` 比原版更慢。
  实际放置还取决于输入、命中与游戏状态，Tick 不是保证成功放置的次数。
- 固定仅作用于手持方块；其他物品和持续使用物品保留原版冷却。GUI、失焦、死亡、无世界，
  或 Scaffold / Telly / GodBridge / Scaffold2 启用时暂停；AutoClicker 的自动右键在生效时让位。
- 所有 bypass profile 均使用所选 Tick。旧配置保留 `fastplace.delayTicks`，移除其他
  `fastplace.*` 选项；旧版迁移也保留显式设置的 `4t` / `5t`。
- 普通 mod 通过 coremod 安装单个冷却钩子；运行期注入通过 JVMTI 安装同一钩子。
  HUD 显示所选 Tick 或暂停原因；注入钩子失败显示 `NO HOOK` 并保留原版冷却。
- 回归验证：`bash tools/fastplace-test/run.sh`、`python3 tools/fastplace-transform-test/run.py`。
  检测表现需结合具体服务端实测。

### Reach 攻击距离

- 配置路径：`combat.reach`，默认关闭，默认键位 **K**，位于 ClickGUI 的 **Combat** 分类。
- 行为：每次**新的攻击/挥击**按 `CHANCE` 掷一次随机数。命中几率时，本次挥击会在
  配置的距离内做自定义视线射线检测，找到最近且可见的生物后通过
  `PlayerControllerMP.attackEntity` 发送 `C02PacketUseEntity`；
  未命中几率时，本次挥击完全使用原版距离。
- 设置项（ClickGUI 中展开 Reach）：
  - `DISTANCE`：`reach.distance`，0.00~5.00 米，步长 `0.05`，默认 `3.00`。
    **这个值就是实际攻击上限**，没有任何隐藏常量再压一层；调到 0 等于关闭补刀距离。
    旧配置的 `reach.extra` 会自动迁移成 `3.0 + extra`（并按 0~5 米截断）。
  - `CHANCE`：`reach.chance`，1~100 %，默认 `100`。
  - `PING SPOOF`：`reach.pingSpoof`，0~400 ms，默认 `0`（关闭）。把事务确认压后发出，
    拉长服务端「目标可能位置」的窗口；`SPOOF JITTER`（`reach.pingJitter`，0~150 ms，
    默认 `40`）给每次延迟加随机量，避免固定间隔。只影响出站 C0F，不改任何位置包。
    仅在 Reach 启用时生效；到期回执在 tick 动作之前释放，传送/重生处理前先释放
    旧回执，处理期间暂停延迟，避免传送确认越过事务边界。
    开启后本地复核也会把目标最近 `1 + spoof/50` tick 的位置并进服务端盒子
    （`serverBoxFor`），否则本地 3.00 上限会先把这类攻击挡掉、spoof 等于没开。
    收益取决于目标位移：站着不动的目标并集不增长，收益为 0。
  - `GRIM CAP`（旧名 `GRIM CHECK`）：`reach.grimCheck`，默认 `false`，独立于全局
    bypass/profile。开启后实际距离取 `min(DISTANCE, 3.00)`——`3.00` 是 Grim 2.0
    `combat/Reach` 的 `ENTITY_INTERACTION_RANGE`，也就是原版自己的上限，
    所以开启后**不会再增加距离**，只是保证不越过 Grim 的判据。HUD 会显示 `3.00mG`、
    GUI 摘要会显示 `· GRIM`，不会再有“滑块调了却没反应”的静默状态。
    无论是否开启，攻击都要求当前准星射线与 `lastReportedPosX/Y/Z +
    lastReportedYaw/Pitch`（服务端结算这条 C02 时使用的上一条 tick 姿态）两条射线
    均命中同一目标、均满足距离；移动包被 Velocity 暂存时让位；
    上报字段存在但为 NaN / pitch 越界时跳过本次补刀；字段整体缺失时退回相机姿态
    并只提示一次，不会把 Reach 关死。
  - `DEBUG CHAT`：`reach.debug`，默认 `false`。开启后每次挥击的决策链直接写聊天框：
    - `SWING p=0 chance=100% dist=5.00 cap=5.00 BALANCED`：识别到一次新挥击，
      带本次的 CHANCE / DISTANCE / 实际上限 / profile；
    - `SKIP chance 42% >= 55%`：概率未命中，本次保持原版距离；
    - `SKIP vanilla-hit ENTITY`：原版射线已命中实体/方块，Reach 不补刀；
    - `SKIP hurt Steve`：目标还在受击无敌帧，服务端会丢弃这次 C02；
    - `SKIP attack slot used`：本 tick 已经有 Aura / AutoClicker 的实体攻击，让位；
    - `SKIP no target scan=37 cap=5.00`：扫描完实体表没有满足距离/可见性的目标；
    - `HIT Steve cam=4.87/5.00 srv=4.72/5.00 border=0.10 yaw=12.5 pitch=-4.2 anim=c0a swing=1`：
      本地 controller 攻击调用成功。`cam` 是本地准星射线距离 / `DISTANCE`，`srv` 是
      服务端结算这条 C02 时会量到的距离 / 服务端上限（`GRIM CAP` 下是 3.00，骑乘时
      等于 `DISTANCE`）；只有 `srv` 不超上限才可能不触发 Grim 的 Reach。每次补刀都先发
      `C0A`（`anim=c0a`），`swing=1` 表示原版挥击动画本来就在进行。
      `HIT` 不代表服务端已经接受攻击或结算伤害；controller 调用失败只记录 `SKIP attack failed`；
    - `SKIP no target scan=37 cap=4.20 dist=4.20 over-cap=1`：有目标在射线上但超过了
      实际上限；`over-cap` 单独计数，`grim-cap=3.00` 表示这是 GRIM CAP 削掉的；
    - `SKIP no target ... reported-miss=1 reported-range=0`：当前准星能命中，已上报射线没命中；
    - `SKIP reported pose invalid` / `SKIP movement queued`：上报姿态数值非法或移动尚在队列；
    - `1s swing=4 hit=3 skip=1`：每秒一行汇总。
    聊天框每秒最多 10 行（`HIT` 与关键跳过不受限），全部事件仍写入
    `logs/openmaver.log`（`util.Logger.debug`，模块名 `Reach`）。
    回归测试：`bash tools/reach-test/run.sh`（0~5 米边界、Grim 独立校验、MCP/SRG/obf
    上报视角、可见性、调试、包序、配置持久化与热重载、事务延迟队列）。
- 距离上限 **5.00 米**；开启 `GRIM CAP` 时为 **3.00 米**。均按眼睛到目标扩张箱的
  射线长度计算（原版 hitbox margin 为 `0.1`）。HUD / GUI 的 CAP 显示实际生效上限。
  这属于发送前几何校验：服务端 1.8 的 `processUseEntity` 对**可见**目标允许 6 格
  （`d0 = 36.0D`），只有不可见目标才收紧到 3 格，所以 4~5 米补刀在原版服务端是有效的；
  Grim 侧仍按 3.0 判据，超过就会被 `combat/Reach` 盯上，这是距离与检测面的取舍。
- 实现说明（已核对实际 1.8.9 字节码与 Grim 2.0 `combat/Reach`、`packetorder/PacketOrderB`、`misc/Post`）：
  - `EntityRenderer.getMouseOver` 在生存模式会把 `getBlockReachDistance()`（4.5）
    用作射线长度，但只要实体距离超过 3 格就会丢弃目标；这是原版实体瞄准的硬限制。
  - `PlayerControllerMP.getBlockReachDistance()` 在 1.8.9 是根据 GameType
    **动态返回** 4.5 / 5.0 的方法，并不存在可反射修改的 `blockReachDistance` 字段。
  - **包序**：Grim 1.8 的 `PacketOrderB` 在每条 tick 包（C03/C04/C05/C06）到达时
    清空 `sentAnimation`，因此 `C02` 前面必须有一条**同一 tick、且在 tick 包之后**
    的 `C0A`，否则报 `pre-attack` 并取消这条攻击。挥击动画有 6 tick，
    `isSwingInProgress` 在高 CPS 下恒为 true，旧实现“已经在挥就不补动画”正是
    `failed PacketOrderB (pre-attack)` 的来源。现在每次补刀都会调用
    `EntityPlayerSP.swingItem()`（该方法无条件发 `C0A`），顺序固定为
    `C0A -> C02 -> 本 tick C03`。
  - **阶段**：Reach 从 tick END 移到 `ClientTickEvent.START`（vanilla 发出 C03 之前）。
    既满足 `PacketOrderB`，也避开 Grim `Post`（移动包之后再发攻击/交互会被判
    `failed Post`）。C02 排在本 tick 的 C03 之前，服务端结算时用的是上一条 tick 包
    里的坐标/视角，因此每条补刀都用 `lastReported*` 姿态再复核一次（与 GRIM CAP
    开关无关）；Criticals JUMP 延迟补刀也重新经过同一条校验与挥击链路。
  - 每个 tick 共享一个实体攻击名额：Aura / AutoClicker / Velocity 已经攻击时
    Reach 直接让位，避免同 tick 两条 `C02`。
  - 使用物品或本 tick 已有右键动作时不做自定义攻击，避免 `MultiActionsA/F`。
  - 方块交互距离保持原版（生存 4.5 格），**本模块只扩展实体攻击/挥击距离**。

### Reach 与 Grim 的绕过面

Grim 的 `combat/Reach` 不是「你报多远就查多远」，它量的是另一套东西。按源码
（[Grim 2.0 `Reach.java`](https://git.omada.cafe/amethystia/AutoCope/raw/commit/98b4b8fecfbd5e0ddc4f9c7f24388b769fa4a2f5/src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java)）：

- 攻击包（`INTERACT_ENTITY`）只把 entityId **入队**；真正的测量发生在**下一条飞行包**，
  坐标用 `player.lastX/lastY/lastZ`（上一条移动包的坐标），视角用
  `lastXRot/xRot` + `lastYRot/yRot` 两组。
- 目标盒子取 `PacketEntity.getPossibleCollisionBoxes()`；事务没对齐时是
  `combineCollisionBox(oldPossible, newPossible)`，即「事务窗口内目标可能走过的并集」。
- 盒子扩张：1.8 客户端 `+0.1`（原版 hitbox margin）、`Reach.threshold +0.0005`、
  上一包不是位置包再 `+0.03`。
- 判据取所有「视角 × 眼高 × 可能盒子」组合的**最小**距离，`> 3.0` 才 flag；
  射线完全没进盒子报 `Missed hitbox`。
- 另一条实时分支 `isKnownInvalid`：攻击包到达时用「点到盒子」最短距离，
  `> 3 + (位置包 ? 0.03 : 0)` 就直接取消这条 C02（`Reach.block-impossible-hits`，默认 true）。
- 2.0 还有两个提前返回：`gamemode == CREATIVE` 和 `player.vehicle != null`。

所以能在 3.0 之外拿到距离的只有四件事，本仓库都接上了：

| 手段 | 实现 | 收益 | 代价 |
|---|---|---|---|
| 事务延迟（`PING SPOOF`） | 出站 `C0FPacketConfirmTransaction` 压后 100~400ms | 目标在「RTT+延迟」窗口里的位移，走位目标约 0.3~1.5 米 | RTT 抖动，Ping/Transaction 面能看到 |
| 相机/服务端两层上限 | 相机 ≤ `DISTANCE`，上报姿态 ≤ 3.00 | 后撤/侧移一步的差价，0.1~0.35 米 | 朝目标走时反向收紧 |
| 载具 | 检测 `ridingEntity` 后放开到 `DISTANCE` | 2.0 上整条 Reach 被跳过，上限回到服务端 6 格 | 3.x 已重构载具，需 `/grim verbose` 复测 |
| 0.03 不确定度 | 出刀前先发一条视角包（C04） | +0.03 米盒子 | 几乎没有 |

`PING SPOOF` 的原理就是第二行：`onFirstTransaction`（服务端发 S32）到
`onSecondTransaction`（收到我们的 C0F）之间，Grim 只能认为目标在这段里任意位置；
确认压得越久，并集越长，`minDistance` 越小。队列 FIFO、单包最多压 1.5 s、
断线立即清空，因此不会制造事务乱序。

### Aim Assist 平滑瞄准

- 设置路径：`config/openmaver.properties` 的 `aim.*`，ClickGUI 中位于 Combat。
- 选敌排除盔甲座（包括箱子上方浮空文字所用的隐形盔甲座）、对本地玩家不可见的
  实体和被方块遮挡视线的实体。锁定期间每 tick 重新检查，目标被遮挡、隐形或从
  世界实体列表移除后立即释放锁定；可见玩家和普通生物仍可作为目标。
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
- `JITTER`（`aim.jitter`，默认 `0.35`）：**落点抖动**。head 命中盒是 0.6×0.44，
  中心点就是 (8,8)，以前每次锁定都精确打在中心 —— 服务端看到的是一串零方差的
  完美瞄准，比“准”本身更显眼。现在每次建立锁定都给该目标滚一个**固定**落点：
  在命中盒半宽/半高换算出的角度圆盘内，半径取 35%~100%，方向随机，因此
  - 一定偏离正中（不会落回 8,8）；
  - 一定仍在命中盒内（打得中）；
  - 锁定期间不变，每 40 tick 才重滚一次（模拟视线漂移，而不是每帧抖）。
  设成 `0` 可退回老的精确瞄准。验证：`tools/aim-test/run.sh`（15 项断言，
  覆盖零强度、偏移非零、命中盒内、锁定期恒定、到期重滚、近距离等比收敛，
  以及下面这条 yaw 连续性的全部边界）。
- **yaw 必须连续累加，不能每 tick 取模**（`advanceYaw` / `continuousYaw`）。
  原版的 `rotationYaw` 是连续值（可以是 400、-720），只有 `prevRotationYaw`
  被来回调整；服务端拿到的也是连续值。老实现每次写 `wrapDegrees(oldYaw + step)`，
  跨过 ±180 时下一包就是 179 而不是 181，服务端算出的包间 delta 变成 **358°**：

  ```
  tick N   : yaw = -179.4
  tick N+1 : yaw = +179.6      -> deltaXRot = 359.0
  ```

  这正好命中 Grim `AimModulo360` 的判据（源码注释：*It works on clients who
  % 360 their rotation*）：

  ```java
  if (player.yaw < 360 && player.yaw > -360
          && Math.abs(rotationDelta.getDeltaXRot()) > 320
          && Math.abs(lastDeltaYaw) < 30) flagAndAlert();
  ```

  现在所有写 rotation 的地方（Aim、KillAura ROTATE、Scaffold 的临时转向与恢复）
  都走「离当前视角最近的连续角」，相邻两包 delta 恒等于实际转过的角度；
  yaw 自然越过 ±360 后反而进入该 check 的豁免区。pitch 统一 clamp 到 `±89.5`，
  避免顶在 `±90` 这种特征值上。

  > 同一类 bug 之前藏在 Scaffold 里：注释写「保持在 [-180,180)，Grim 会检查越界」，
  > 实际正好相反 —— 取模才是这个 check 抓的东西。

### UI 音效

- 模块**开启 / 关闭**（ClickGUI 圆点、`LMB` 折叠行、键位切换）播**经验球拾取音**
  `random.orb`，开启与关闭分别落在两个不重叠的音区：
  - 开启：`1.00~1.25`（清脆，就是经验球被吸进来的那一下）
  - 关闭：`0.55~0.75`（发闷）

  原版经验球的 pitch 本来就是 `0.5 * ((rand - rand) * 0.7 + 1.8)`（0.55~1.25），
  所以这同一段素材听感上天然有"亮 / 闷"两种；区间内保留随机，和真捡经验一致。
  集中入口是 `OpenMaverHud.notifyToggle()`，因此三条路径都会响。
  想换成别的素材（例如升级音 `random.levelup`）只改
  `OpenMaverFeatures.playXpPickup()` 里那一行音效 id。
- ClickGUI 打开 / 关闭：`1.15` / `0.90`。
- 主菜单按钮：开始类 `1.0`，退出 `0.85`。
- 实现：`OpenMaverFeatures.playUiClick(float)`，反射取 `PositionedSoundRecord.create`
  与 `SoundHandler.playSound`（`mcstubs` 不含 sound 包，编译期不依赖）；
  取不到就静默跳过，绝不影响界面逻辑。

### AutoClicker 自动点击

- 设置路径：`config/openmaver.properties` 的 `autoclicker.*`，ClickGUI 中位于 Combat。
- 默认 `6~10 CPS`，`HUMANIZE` 默认开启：每次点击有 ±12% 间隔抖动，
  2% 概率追加 120~350ms 停顿。
- `REQUIRE HOLD` 默认开启：只有按住对应鼠标键时才连点，模块开启本身不会
  在玩家没有输入时继续点击。v3 配置会自动把旧版被强制迁移成 `false` 的值恢复为 `true`。
- `WAIT FOR HURT` 默认开启：准星在实体上时，等目标的受击无敌帧结束再发送真正的
  `C02` 攻击。等待期间不再发送任何 `C0A`（1.19.2+ 服务端会用 swing 动画重置攻击
  蓄力，见 MC-255058），只把这次尝试计入 CPS HUD，所以 CPS 仍显示配置值，但不会
  为了动画把后续攻击的蓄力清空。1.8.9 中 `hurtResistantTime > maxHurtResistantTime / 2`
  的 C02 会被服务端忽略。节流按 **11 个客户端 tick（约 550ms）** 计算，而不是刚好
  500ms：客户端拿不到服务端 `hurtResistantTime`，恰好卡在 10 tick 边界上的攻击有
  大半概率落在无敌帧最后一 tick 里被直接丢弃。
- **按住左键时原版补发会被拦住**：1.8.9 的 `Minecraft.runTick` 只要左键按住且
  `leftClickCounter == 0` 就会自己再调一次 `clickMouse()`，而命中实体时不会重置该
  计数器，所以旧实现即使开了 `WAIT FOR HURT`，服务端仍会收到约 20 C02/s。
  现在 AutoClicker 开启且准星指着实体时，会把这个计数器压到 `2`，让原版的
  “按住连点”分支跳过；模块自己真正攻击前再清回 `0`。这只在准星命中实体时生效，
  挖方块 / 右键放置不受影响。
- `CLICK ONLY` 默认开启（`autoclicker.clickOnly`）：准星在方块上时只发 `C0A`
  挥手，不调用 `clickMouse()` —— 也就是不发 `C07PacketPlayerDigging(START_DESTROY_BLOCK)`，
  不会把方块挖掉。同时原版“按住左键”那条挖掘路径会被 `leftClickCounter = 2`
  拦住（这一条不受 `REQUIRE HOLD` 影响，模块开着就不会拆方块）。关掉后恢复旧行为：
  左键点击命中方块会正常开挖掘，10~20 CPS 下等于自动挖矿。
- 模块绑定键（默认 `R`）只在按下边沿触发一次；按住不会因为系统键盘重复而
  反复开关 AutoClicker。
- `MODE`（`autoclicker.attackMode`）决定 C02 节奏，三档：
  - `FULL CHARGE`（默认）：按 1.9+ 武器冷却估算等待。间隔为
    剑 13 tick、镐 17、铲 20、斧 20~25（按材质）、空手 11（受击无敌帧下限）。
  - `FASTEST`：只按 10 tick 受击无敌帧节流（约 2 次/秒），单次倍率较低，
    但近战对拼时命中更密、击退压力更大。适合斧/重武器或想打近战的场景。
  - `VANILLA`：旧行为，`C0A -> C02` 原版顺序 + 11 tick 节流。适合
    `/server legacy` 1.8 战斗（没有攻击冷却；Grim 1.8 的 `PacketOrderB`
    要求先挥手）。
  三个模式的实体攻击均使用 1.8.9 的 `C0A -> C02` 顺序，模式仅决定攻击间隔。
  `FULL CHARGE` / `FASTEST` 等待期间不发 C0A；`VANILLA` 保留原版等待挥手。
  跨版本服务端对挥手和攻击冷却的处理仍由服务端协议转换决定，满额伤害需实测。
- 在 CCBlueX 测试服的 `latest(26.3)` 上，1.8.9 客户端仍会受到服务端 1.9+
  攻击冷却影响。**如果伤害一直显示 0.2~0.4 倍**，按顺序检查：
  1. `MODE` 是否为 `FULL CHARGE`（默认）；
  2. 是否在 `latest` 这类 1.9+ 子服——`/server legacy` 才是 1.8 满伤害；
  3. 目标是否穿甲：假人同时显示两个数字时（例如 `1.00 / 0.07`），**较小的那个
     通常是护甲减免后的实际伤害**，全套保护 IV 只剩裸伤害的 7%，不是 C02 被吞。
- 注入点击前会清掉原版 `leftClickCounter`；右键在 `balanced/legit` 下遵守原版
  `rightClickDelayTimer`（4 tick），`aggressive` 才压到 0。否则 1.8.9 会在 MISS 后把
  点击吞掉 500ms，而高 CPS 右键会连续发 C08 触发 `block.fastplace`。
- 自动点击放在 `ClientTickEvent.START`（vanilla C03 移动包之前）注入，
  避免 Grim 的 `Post` 检查把 `C02/C08` 判成“移动包之后才发送”而拒绝；
  放到 tick END 会表现为“按住鼠标时正常、自动点击打不中”。
- 左上角 CPS HUD 同时统计 AutoClicker 注入的点击，不再只统计物理鼠标边沿。
- 点击节拍按“上一次计划时间 + 间隔”固定推进，而不是每次从当前时间重算。
  20 TPS 下 14~17 CPS 的间隔是 59~71ms，旧实现会把每次点击推到下一个 tick，
  实际只剩 6~9 CPS；现在会保留 tick 余量，1 秒窗口内可达到设置的 CPS。
- AutoClicker 不再被 `legit/balanced` 的 8/12 CPS 上限静默截断，使用 ClickGUI 中
  的 `CPS` **双滑块区间**（左柄 = MIN，右柄 = MAX，1~20；右上角按
  `MIN ↔ MAX` 显示，和区间的两个三角手柄一一对应）。`HUMANIZE` 的 2% 长停顿
  仍可能让个别 1 秒窗口略低于设置值，需要稳定数值时关闭 `HUMANIZE`。
- 同一 tick 左右键互斥；使用物品时完全停止；仅 `legit` 档连续 MISS 空挥超过
  6 次会暂停 250~500ms。打开 GUI/背包/聊天或死亡时重置计时器，不补发点击。

### Criticals 刀暴（默认 JUMP 真实起跳暴击）

- 配置路径：`combat.criticals`，默认关闭，默认不绑定按键（`key: ""`），位于 ClickGUI 的
  **Combat** 分类。
- 行为：AutoClicker 或 Reach 真正要发送 `C02` 之前，先让玩家满足原版暴击条件。默认
  `JUMP` 模式只做真实跳跃：站地面时调用 `jump()`，等进入下落阶段（`fallDistance > 0 &&
  !onGround`）再结算攻击，**不发送任何伪造的 `C03/C06` 移动包**，因此不会产生
  `GroundSpoof` / `Simulation` / `Timer` 类 flag。
- 设置项（ClickGUI 中展开 Criticals）：
  - `MODE`：`ALWAYS` / `NCP` / `FALLING` / `GRIM` / `JUMP` / `LOW` / `DOWN`，默认
    `JUMP`（写入 `criticals.mode=jump`）。`balanced`（默认档）下即使选择 packet 模式也会
    被降级为真实起跳；`legit` 档完全跳过刀暴；只有 `aggressive` 会真正发送伪造移动包。
  - `STOP SPRINT`：默认 `true`，写入 `criticals.stopSprint`。
  - v6 迁移：旧配置的 `criticals.mode=packet/ncp/...` 会迁移到 `jump`。
    FastPlace 的显式 Tick 设置独立保留。
- Packet 序列（`x/y/z/yaw/pitch` 均取发送前的客户端真实值，不修改客户端 position）：
  - `ALWAYS`（LiquidBounce Legacy packet 序列）：`C04(x, y+0.0625, z, true)` ->
    `C04(x, y, z, false)`。不再限制 `player.onGround = true`，因此站在地面、
    跳跃上升或已经下落时都能让服务端看到一小段下落距离。
  - `NCP`：`C04(x, y+0.11, z, false)` -> `C04(x, y+0.1100013579, z, false)` ->
    `C04(x, y+0.0000013579, z, false)`。
  - `FALLING`：`C04(x, y+0.0625, z, false)` -> `C04(x, y+0.0625013579, z, false)` ->
    `C04(x, y+0.0000013579, z, false)`。
  - `GRIM`：仅当玩家本来就在空中时发送一条 FULL 包
    `C06(x, y-0.000001, z, yaw, pitch, false)`；玩家站在地面上时不发送任何包，也不会
    回退到 `ALWAYS`，避免主动制造 `GroundSpoof`。
  - `JUMP`：地面攻击先调用真实跳跃，等玩家进入下落阶段（`fallDistance > 0`）后再发送
    `C02`；已经在下落时直接使用原版暴击判定。该模式同时适用于 AutoClicker 和 Reach，
    不修改客户端位置，也不伪造地面状态。
  - `LOW`（参考 LiquidBounce / 水影）：`C04(x, y+1e-9, z, false)` ->
    `C04(x, y, z, false)`。两次位移都小于 Grim
    `Simulation.threshold = 0.001`，同时让服务端得到 `fallDistance = 1e-9 > 0`，
    因此比 `ALWAYS` 的 0.0625 偏移更不容易触发 `Simulation`。这里用只带位置的
    `C04` 而不是 `C06`：两条 yaw/pitch 完全相同的 `C06` 会被 Grim 的
    `AimDuplicateLook` 当成重复 rotation 逐条记录，而伪造下落本身不需要再发视角。
  - `DOWN`（参考 LiquidBounce / 水影）：只发送
    `C04(x, y-1e-9, z, false)`，包数最少。如果客户端正好贴地、
    微型位移被地面碰撞吃掉导致不暴击，请改用 `LOW`。
  - `LOW` / `DOWN` 的来源说明：LiquidBounce 提交 `7ea699902` 写明 `LOW` 面向
    UpdatedNCP 地面、`DOWN` 面向关闭 `Motion (B)` 的 Vulcan，目标是“不检查重复包
    地面状态”的 AC。Grim 会检查 `GroundSpoof` / `NoFall`，所以这两个模式能压低
    `Simulation`，但不保证绕过 Grim 的地面状态检查；LiquidBounce 自己的 `GRIM`
    模式也只在 `!player.onGround()` 时补包。
- 生效条件：Criticals 模块开启；目标是 `EntityLivingBase` / `EntityPlayer` 且存活；玩家
  不在使用物品、梯子、水面、蜘蛛网或骑乘状态；目标已脱离受击无敌帧（复用 AutoClicker
  的 `entityReadyForDamage`）。`aggressive` 档下 `ALWAYS/NCP/FALLING/LOW/DOWN` 不检查
  地面状态，地面与空中都会补包；`GRIM` 仍要求玩家已经离地，`JUMP` 会在地面自动起跳并
  延迟攻击。`balanced` 会把所有 packet 模式降级为 `JUMP`，`legit` 直接跳过刀暴。
- 1.9+ 服务端：`Player.attack` 的暴击条件额外要求 `!isSprinting()`。AutoSprint 默认会
  保持冲刺，所以 `STOP SPRINT` 默认开启：攻击前发送 `C0B STOP_SPRINTING` 并
  `setSprinting(false)`，攻击后再发送 `C0B START_SPRINTING` 并恢复冲刺。
  **v5 配置迁移会把旧的 `criticals.stopSprint=false` 恢复为 `true`**：在 1.9+ 子服上
  冲刺状态下的伪造下落既不会暴击，又只会白送 `GroundSpoof` / `Post` / `Timer`。
  如果关闭 `STOP SPRINT`，客户端检测到玩家仍在冲刺时会**直接跳过这次刀暴**
  （按普通攻击结算），避免发送无效移动包；`/server legacy`（1.8）不检查冲刺，
  可以关闭它对比 1.8 行为。
- 注入点：只接在真正发送 `C02` 的路径上——AutoClicker 的 `clickMouse()` 前、Reach 与
  KillAura 的 `PlayerControllerMP.attackEntity()` 前。`WAIT FOR HURT` 的 swing-only 分支没有 `C02`，
  不会发送伪造移动包；手动点击暂未 hook，需要单独的事件/mixin，不在本模块范围内。
- 1.9+ 的攻击冷却不会被绕过：`attackStrengthScale` 不足时依然不是满伤害，需要配合
  `WAIT FOR HURT`、降低 CPS 或使用 legacy 服务端。
- 所有包类都通过 `Class.forName` 反射并缓存构造器；发送失败/映射差异只记录一次并安静
  跳过，不会抛异常或每 tick 刷屏。
- 已知限制：Grim 对 `ALWAYS` / `NCP` / `FALLING` 的快速上下位移可能报
  `BadPackets` / `InvalidPosition` / `Post` 类 flag。`LOW` / `DOWN` 把位移压到
  1e-9，通常可以避开 `Simulation`，但仍可能被 `GroundSpoof` / `NoFall` /
  `Timer` 记录。`GRIM` 不会在地面上伪造下落；需要地面暴击时优先尝试
  `LOW`，不行再用 `ALWAYS`。
- Matrix 实测（CCBlueX `/practice`）：`ALWAYS` 会在每次攻击后连续触发
  `KILLAURA: critical hit without correct jumping // ground spoof`、
  `// mini-jump (d:0.0625)`、`// modify (ver: 1.8 d: 0.0)`，以及
  `BADPACKETS: timer`（每次攻击额外补 2~3 条 C04）。这些 flag 与攻击次数直接相关，
  VL 会持续累积。`JUMP` 模式只使用真实跳跃/下落，没有额外移动包，因此不会出现
  上述三类 flag；代价是每次地面攻击都要先起跳。
- 实测 `latest(26.3)`（Grim 2.0）：
  - `ALWAYS` / `NCP` / `FALLING`：会触发 `Simulation`（0.0625 / 0.11 等偏移）、
    `GroundSpoof claimed false`、`NoFall`、`Timer`，以及偶发 `Post: attack v1.8`。
    这些偏移都大于 Grim `Simulation.threshold` 默认值 `0.001`，且每次攻击额外发送
    2~3 条 C03，`Timer` 会按每条 +50ms 计费。`ALWAYS` 的地面暴击依赖这些包，
    无法同时做到零 flag。
    - `Simulation (.062500)`：来自 `ALWAYS` 的 `y+0.0625` 伪造位移。
    - `GroundSpoof (claimed false)` / `NoFall`：来自 C02 前最后一条
      `onGround=false`，Grim 预测玩家仍在地面。
    - `Timer`：每条额外移动包都会按一个 50ms tick 计费，CPS 越高累计越快。
    - `Post (attack v1.8)`：C02 位于伪造移动包与真实 C03 之间的窗口，事务包
      若在该窗口到达就会被记录。
  - `GRIM`：地面状态不发送包；玩家已经下落时只发送 1 条
    `C06(y-0.000001, onGround=false)`，位移远低于 `0.001` 阈值，减少
    `Simulation` / `NoFall` / `Timer` 风险，但不能替代真实的地面起跳。
  - 结论：任何“在地面上伪造下落”的 packet 刀暴都无法完全避免 `GroundSpoof`；只要比
    原版多发送移动包，`Timer` 也无法完全归零。要零 flag，只能使用真实的下落/跳跃暴击，
    或在 `/server legacy` 等检测较弱的服务端对比测试。本模块保留多种模式就是
    为了记录这些 flag 差异；v6 起默认与 `balanced` 档都只走 `JUMP`，packet 模式仅在
    `aggressive` 档可选。

### KillAura 自动攻击（刀暴）

- 配置路径：`combat.killaura`，默认关闭，默认不绑定按键（`key: ""`），位于
  ClickGUI 的 **Combat** 分类。
- 行为参考 `OpenMyau-Fix` 的 `KillAura`，按 OpenMaver 的反射架构重写为稳定核心版：
  - 从 `loadedEntityList` 中按距离/血量选择目标，支持 Single / Switch；
  - FOV、攻击距离、穿墙、玩家/怪物/动物过滤；
  - 可选平滑转向（复用 Aim 的 profile 单 tick 步长上限）；
  - 按配置 CPS 攻击，但至少等待目标 10 tick 受击无敌帧；
  - 与 Criticals 联动：`JUMP` 模式先真实起跳，下一次 tick START 再补 `C02`；
  - KillAura 启用时 AutoClicker 自动让位，避免同一 tick 发出两条攻击包。
- 设置项（ClickGUI 中展开 KillAura）：
  - `RANGE`：`killaura.range`，3.0~6.0 格，默认 `3.0`；`legit/balanced` 实际生效
    距离仍限制在 Grim 3.0 扩张箱内，只有 `aggressive` 才允许更高配置值。
  - `CPS MIN` / `CPS MAX`：`killaura.cpsMin` / `killaura.cpsMax`，1~20，默认 `8~12`。
    受击无敌帧会进一步把实际伤害频率压到约 1.8 次/秒。
  - `FOV`：`killaura.fov`，30~360°，默认 `360`。
  - `TARGET`：`killaura.switch`，`Single` / `Switch`，默认 `Single`。
  - `SORT`：`killaura.sort`，`Distance` / `Health`，默认 `Distance`。
  - `SWITCH DELAY`：`killaura.switchDelay`，0~1000ms，默认 `150`。
  - `THROUGH WALLS`：`killaura.throughWalls`，默认 `true`。
  - `PLAYERS` / `MOBS` / `ANIMALS`：默认只攻击玩家。
  - `ROTATE` / `SMOOTH`：`killaura.rotate` / `killaura.smoothing`，默认开启、`0.45`。
    攻击使用“本 tick 转向之前”的视角判断，等服务端收到上一 tick 的 C03 后再结算，
    避免 RotationPlace / Aim 类误判。
  - `REQUIRE PRESS`：`killaura.requirePress`，默认 `false`；开启后只在按住左键时攻击。
- 未移植 Myau 的 AutoBlock / Blink / BackTrack / Teams 等重型逻辑，以稳定、可编译、
  不高频刷包为优先。

### BlockHit 真实自动格挡

- 位于 ClickGUI 的 **Combat → BlockHit**，配置路径 `combat.blockhit`，默认关闭、无默认热键。
- 手持剑攻击实体后跟踪该对手，仅在近战反击窗口通过 1.8.9 原版 `PlayerControllerMP.sendUseItem` 发送 `C08` 并进入真实物品使用状态；
  持续结束时通过 `onStoppedUsingItem` 发送 `C07 RELEASE_USE_ITEM`。同时维护使用键状态，保留原版格挡减速。
- 保护进攻连击：观察对手受伤计时/血量变化确认命中，连续两次命中且自己没受伤时不格挡，
  自己受到伤害后重新判断。对手仍有受击硬直、自己仍处于受伤无敌窗口时也不格挡；
  已确认一次命中后的新攻击额外保留 6 tick 等待确认，避免第二击尚未回包就开始减速。
- 对手必须在眼睛到本地碰撞箱 3 格内、朝向本地玩家（偏角不超过 55°）、视线可达，
  且刚开始挥击（进度 0~2）才尝试；同一次挥击最多触发一次。攻击后最多跟踪 20 tick。
  这是基于客户端可见状态的保守判断，不能提前知道服务端最终是否命中或准确预测网络延迟。
- 覆盖手动攻击、AutoClicker、KillAura 和 Reach 的 Forge 攻击事件。每次 tick START 收尾判断反击窗口，
  动作包在该 tick 的 `C03` 前发送；原版输入阶段的手动攻击从下一次 START 开始观察。
- 展开设置中的 **HOLD TICKS** 控制每次格挡持续 `1~5` tick，默认 `2`（正常 20 TPS 时约 `100ms`）；
  保存到 `config/openmaver.properties` 的 `blockhit.holdTicks`。这是持有上限，威胁消失会提前释放。
  格挡期间沿用原版的使用物品限制，结束后恢复攻击。
- 切槽、打开 GUI、失焦、死亡、离开世界或禁用模块时，下一次 tick START 清理自己持有的格挡和使用键。
  已在手动使用物品时不接管；格挡中按下实际使用键则交回手动控制。搭路模块启用时让位。
- 这是 1.8.9 的剑格挡流程。通过 ViaForge 连接较新版本服务器时，防御效果由协议转换和服务器支持决定。
  OpenZen 当前的 `Fake AutoBlock` / `OldHitting` 仅提供显示状态和动画，本模块补充原版真实物品使用流程。
- 检查：构建后运行 `bash tools/blockhit-test/run.sh`，覆盖反击窗口、确认连击/空点、无敌帧、
  单次挥击去重、真实物品使用、生命周期清理、手动接管及参数保存；测试不连接服务器。

### 击败嘲讽

- 位于 **Personalization → Taunt**，开启 **Kill taunt** 后，击败玩家会在聊天发送指定文字；
  默认关闭，文字默认 `gg`，点击输入框编辑、Enter 保存，支持中文及 `{target}`（对方名字）。
  按 1.8.9 聊天限制保留最多 100 个 UTF-16 字符，空白文字不发送。
- 根据服务器死亡消息确认本地击杀：支持原版 `death.attack.*` 翻译组件及明确的中英文击杀/虚空消息，
  核对击杀者是自己、受害者是近期世界/Tab 列表内的玩家。普通聊天、自己死亡、别人击杀、
  实体卸载或消失都不会触发。服务器关闭死亡消息或使用其他自定义格式时不会触发。
- 同一玩家 40 tick 内的重复死亡公告只发送一次；连续击败不同玩家会排队，每 20 tick 最多发送一条。
  切世界、重生、断线或关闭开关会清空待发送消息，避免下局补发。
- 文字和开关随配置保存至 `personalization.taunt.text` / `personalization.taunt.enabled`，支持 Config profile。
  构建后运行 `bash tools/taunt-test/run.sh` 检查归属、去重、连续击败、生命周期和 Unicode 保存；测试不连接服务器。

### MoveDisabler 背包/箱子界面移动

- 从上一级 `MoveDisabler/MoveDisabler.java` 移植，位于 ClickGUI 的 **Misc** 分类，
  显示为 **Move Disabler**；配置路径 `misc.movedisabler`，默认关闭、无默认热键。
- 开启后在背包、箱子等容器界面内按实际绑定的方向键移动，支持跳跃和潜行；
  默认是 WASD、Space、Shift。每 tick 在原版输入更新后读取物理按键，松键立即停止，
  不修改全局 KeyBinding 状态。聊天、菜单、ClickGUI，以及聚焦的容器搜索/重命名框
  不接管移动；窗口失焦时清空容器移动输入。保留原版容器鼠标点击与拖动物品行为。
- 输入钩子与网络钩子独立，复用 Scaffold / GodBridge / Velocity 的输入回调位置。
- 默认 Grim 路径中，玩家正在冲刺时，发送 `C0E`（点击窗口）或 `C0D`（关闭窗口）会依次发出
  `C0B STOP_SPRINTING → 原窗口包 → C0B START_SPRINTING`。保持本地冲刺状态，
  原窗口包只发送一次；步行时和其他包均原样发送。覆盖手动操作与 Chest Stealer。
- 选择并启用 Intave Bypass 时，抑制玩家背包的原生 C16 OPEN 和 window 0 本地关闭包。
  点击保留原包和事务号，拾取/放下或拖动结束、光标清空后成批提交；期间继续本地移动。
  每批在实际移动计算前撤销一帧 forward/strafe 并关闭本地冲刺，保留 motion、跳跃和潜行，
  以 `C16 OPEN → 这一帧真实惯性位置 → 本批原始 C0E` 发出。原生空 C03 仅替换为实际
  post-physics 坐标帧，C05 转头帧升级为保留 yaw/pitch 的 C06，不增加移动 tick。匹配本批所有 S32 后才发 `C0D 0` 并保序释放后续包；
  等待期间本地运动仍继续，出站暂存最多 128 包，确认期限 400ms，C0F/keepalive 穿透。
  相关库存同步、传送、换窗、断线或取消时清理过期预测；主动消费包正常完成 promise，
  避免被 Forge 当作传输失败而断线，真实下游写失败保留原因。箱子等非零窗口保留原包路径。
  这是按引用 Intave 源码做的候选策略；旧空 C03 策略已实测失败。新候选尚待目标环境
  验证 Physics、Bukkit 点击结果、光标和物品状态，仍显示为开发中。
  2026-11-26 修复运行期 C16 / C0D 字段映射（`field_149437_a` / `field_149556_a`）；
  原错误会让真实 Forge 客户端把应抑制的背包打开包照常发出。回归现在同时使用 MCP 字段和从 Forge
  映射生成的纯 SRG 字段，避免开发环境通过而运行期失效。
- 网络钩子独立于 Velocity，支持离开世界卸载、重连和钩子丢失后重装；Velocity
  暂存出站包时，窗口包先随队列保序回放，再执行冲刺包裹。该行为不保证特定反作弊版本的结果。
- 构建后运行 `bash tools/movedisabler-test/run.sh`，使用真实 Netty 4.0.23 验证包序、
  原包引用与 promise、禁用/重连清理、写入失败和两种模块安装顺序下的 Velocity 队列共存；
  另用物理按键替身驱动真实共享输入钩子，覆盖容器移动、改键、松键、窗口失焦、
  文本框聚焦、禁用、重生和普通游戏输入恢复。

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

> 按键没反应时先看系统临时目录里的 `openmaver-events.log`（`java.io.tmpdir`，可用
> `-Dopenmaver.events.log=路径` 覆盖）：里面有每次按键的键码、当前界面类名，
> 以及 `first key event: code=... name=...` 这一行。右 Shift 若被驱动 / 串流软件上报成
> `LSHIFT` / `RMENU`，把 Settings 里的 **GUI KEY** 切到对应档位即可；另外开关键只在
> **已进入世界且没有别的界面打开** 时生效（暂停菜单除外）。
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

目标进程的标准输出会打印 `[OpenMaver] Core initialized`、`版本 B2.2.0`、
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
[INFO] [OpenMaver] 版本 B2.2.0 启动完成
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
| 云端库版本 | 日志中打印 `版本 B2.2.0` 与 `getVersion()` 一致 |
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
| `module.movement.KeepSprint` | `movement.keepsprint` |
| `module.combat.AimAssist` | `combat.aimassist` |
| `module.combat.Reach` | `combat.reach` |
| `module.combat.Criticals` | `combat.criticals` |
| `module.render.Esp` | `render.esp` |
| `module.render.MotionBlur` | `render.motionblur` |
| `module.render.Projectiles` | `render.projectiles` |
| `module.render.NoHurtCam` | `render.nohurtcam` |
| `module.tools.Logger` | `tools.logger` |

需要自定义时用 `super("Name", "explicit.key")` 显式指定。

## 配置热重载

`ModuleManager.onTick()` 每 100 tick 调用一次 `Config.reloadIfChanged()`；
文件被外部修改时会自动重解析，并调用所有模块的 `syncFromConfig()` 同步开关状态。
该逻辑位于 `ModuleManager`，因此独立模式与 Forge 模式行为一致。

## 字符串混淆

`core/jni/bridge.cpp` 中的文案常量通过编译期简易 XOR 混淆，
避免明文（如版本号）直接出现在 `strings` 输出中。修改 `obf::kKey` 即可重新混淆。
