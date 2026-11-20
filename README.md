# OpenMaver B2.1.2

私人服务器反作弊对测研究用的客户端骨架，适配 **Minecraft 1.8.9**（Forge 11.15.1.2318）。

> 说明：本仓库包含**基础设施骨架**（JNI 桥接、配置、日志、模块基类、Forge 事件桥接）
> 与基础功能模块（Aim Assist、Player ESP、AutoSprint、AutoClicker、KillAura、Reach、Criticals、Scaffold、Scaffold2 教材搭路辅助、GodBridge 神桥、Velocity、FastPlace、FakePlayer、Target HUD、Player X-Ray、Motion Blur、Chest ESP、Chest Stealer 开箱搬运、FPS / CPS 统计）
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
│       ├── movement/Velocity.java # 防击退开关（MC 侧逻辑在 VelocityFeature）
│       ├── player/FastPlace.java  # 快速右键开关（MC 侧逻辑在 OpenMaverFeatures）
│       ├── player/ChestStealer.java # 开箱搬运 / 整理 / 存入开关（MC 侧逻辑在 mc/ChestFeature）
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
│   ├── ChestPacketHook.java       # 入站 S2D/S30/S2E 窗口包记账（Netty 管线）
│   ├── GuiOpenMaverClickGui.java  # 游戏内 ClickGUI（分类 / 搜索 / 展开设置）
│   ├── GuiMaverMainMenu.java      # MC 标题界面风格主菜单（灰色按钮 / 皮肤展示）
│   ├── MenuSkinPreview.java       # 无需世界实体的完整皮肤模型展示
│   ├── MaverWallpaper.java        # 主菜单壁纸（静态图片 / GIF / 内置动态背景）
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
| 打开界面（主菜单替换） | `net.minecraftforge.client.event.GuiOpenEvent` |
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

> **自带材质包**：Maver 内置 `2pimp v2 [16x]` 材质包
> （`mc/resources/openmaver/resourcepack/2pimp-v2-16x.zip`，已打进 jar）。
> 首次启动时 mod 会把它复制到实例的 `resourcepacks/` 并加入资源包列表，正常退出
> 游戏后下次启动自动启用；之后可在 **选项 → 资源包** 里自由开关，Maver 不会再强制打开。
> 使用 `./launch-openmaver.sh` 或 `./inject-prism.sh` 时，安装脚本会在启动前完成同样的
> 安装并立即启用。落地文件名使用纯 ASCII（`2pimp v2 [16x].zip`）：1.8.9 的 JVM 在部分
> 系统上以 ASCII 解码文件名，带 `§` 颜色代码的文件名会变成 `?` 导致永远匹配不上。

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
[2026-... ] [INFO] [OpenMaver] 版本 B2.1.2 启动完成
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

- **ModuleList** `mc/OpenMaverHud.java`：屏幕右侧垂直堆叠的**已启用功能**列表。
  每行是深色毛玻璃圆角卡片，右边缘严格对齐，宽度随文字长短变化；模块名使用白色，
  右侧以较淡的白色显示该功能的当前参数摘要（例如 AutoClicker 的 CPS 区间、Reach 的
  实际距离），最右侧是一枚白色竖条；不再显示键位提示。禁用功能不会出现在列表中。每行拥有独立
  动画状态：启用时从右侧平滑淡入，禁用时先淡出再移除，剩余行平滑上移换位。
  所有动画基于 `System.nanoTime()`，与帧率无关。ClickGUI 打开时隐藏该列表，
  避免被全屏毛玻璃糊成一层残影。
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
  使用明亮的全景图背景、Maver Logo 和黄色 `Maver!` 缩放标语。
  中央为灰色方框 **Singleplayer / Multiplayer / Settings** 按钮，底部提供
  **Client Settings / Wallpaper / Quit Game**；界面文字使用英文。
  右侧只展示当前账号的完整皮肤和用户名，无商店、Realms 或更衣室入口。
  皮肤从微软 / Mojang 官方服务异步获取，支持旧版 64×32 皮肤，失败时展示 Steve。
  Client Settings 打开 ClickGUI，Wallpaper 直接打开壁纸设置页。
  背景可以替换成静态图片、动态 GIF 或内置动态渐变，
  详见下面「主菜单壁纸」。
- **游戏内 ClickGUI** `mc/GuiOpenMaverClickGui.java`：游戏内按 **RSHIFT** 开关。
  纯灰色面板 + 中性灰描边，全部强调色统一为白色；左侧 Combat / Movement / Render /
  Player / Misc 分类栏，以及 SYSTEM → Configs / Statistics / Wallpaper / Settings 页面
  （配置概览、模块统计、主菜单壁纸与客户端设置），顶部搜索（`Ctrl+K`）、ALL / ENABLED /
  DISABLED 筛选与排序；点击行展开设置在
  `Gui.drawRect` 自绘的滑块 / 下拉 / 复选框中直接调参。分类、SYSTEM 入口、筛选页签、
  排序、模块行、搜索框、开关与全部设置控件都有统一的悬停 / 激活 / 按压过渡，
  滚动条与列表内容共用同一条平滑滚动值。模块列表来自
  `ModuleManager`，Aim Assist、Player ESP、Grim Bypass、AutoSprint、AutoClicker、Reach、
  Criticals、FastPlace、Target HUD 与 Player X-Ray 的设置直接绑定 `OpenMaverFeatures`，参数写入
  `config/openmaver.properties`（拖拽结束与关闭 GUI 时落盘）。
- **主菜单壁纸** `mc/MaverWallpaper.java`：ClickGUI 的 **SYSTEM → Wallpaper**
  （或主菜单底部的 **Wallpaper**）可以切换开始菜单背景：

  | 模式 | 效果 |
  |---|---|
  | `PANORAMA` | 原版旋转全景图 + 毛玻璃（默认） |
  | `STATIC` | 静态 PNG / JPG / BMP 图片；GIF 只显示第一帧 |
  | `DYNAMIC` | 动态 GIF 循环播放；普通图片做缓慢平移 / 缩放；未选文件时使用内置动态极光渐变 |

  把图片放到 `config/openmaver/wallpapers/`（支持 `.png` / `.jpg` / `.jpeg` / `.bmp` / `.gif`），
  然后在 Wallpaper 页选择文件即可。`FIT` 可设为 `COVER`（裁切铺满）、`CONTAIN`（完整显示、留黑边）
  或 `STRETCH`（拉伸铺满）。GIF 会在后台线程解码并限制到 720px 边长 / 64 帧，避免大文件拖慢主菜单。
  主菜单的模糊度在 ClickGUI 的 **SYSTEM → Wallpaper → BLUR** 进度条中调整
  （0% 完全清晰，100% 最模糊）；自定义壁纸同样生效，拖动结束后自动保存。
  配置写入 `config/openmaver.properties`：

  ```properties
  menu.wallpaper.mode=static          # panorama | static | dynamic
  menu.wallpaper.file=my-wallpaper.png
  menu.wallpaper.fit=cover            # cover | contain | stretch
  menu.wallpaper.blur=35              # 背景模糊度 0 ~ 100
  ```

  `menu.wallpaper.file` 也支持绝对路径和 `~/` 开头的路径；相对路径优先在
  `config/openmaver/wallpapers/` 下查找。壁纸解码失败或文件被删除时会自动回退到内置渐变背景，
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

| profile | Reach 实际上限 | Aim 单 tick 上限 | AutoClicker / KillAura | FastPlace | Placement rotation |
|---|---|---|---|---|---|
| `legit` | 3.00（Grim 扩张箱） | yaw 18° / pitch 12° | 使用配置 CPS（1~20），抖动+停顿 | 完全不改 `rightClickDelayTimer` | 仅真实准星 |
| `balanced`（默认） | 3.00（Grim 扩张箱） | yaw 35° / pitch 20° | 使用配置 CPS（1~20），抖动+停顿 | FastPlace / 右键点击保持原版 4 tick | Scaffold 直接走 onPlayerRightClick |
| `aggressive` | ≤3.30（仅对比测试） | 上限放宽 | 使用配置 CPS（1~20） | 允许压到 0 | 同左（Scaffold 始终直接发包） |

> `bypass.enabled=false` 会恢复旧的对比行为（Reach 最高 5.9），**不要**在正式
> 对测中关闭。`aggressive` 会主动越过 Grim 的 Reach 阈值，只用于记录 flag 差异。

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

### Scaffold 自动搭路 / 搭桥（OpenZen 移植）

- 配置路径：`movement.scaffold`，默认关闭，默认键位 **B**。
- **实现来源**：完全按 `../OpenZen` 的 `shit.zen.modules.impl.movement.Scaffold`
  重写（不再是 Myau 的移植）。旧的 `rotations/moveFix/sprint/tower/keepY/...`
  设置与 `OpenMaverFeatures` 里那条旧放置路径已删除。
- 模式（`scaffold.mode`）：
  - `NORMAL`：`snap` 直接对齐支撑面；`eagle` 控制“贴边才潜行”。
  - `TELLY BRIDGE`：OpenZen 的起步/空中旋转表（groundTicks 1/2、俯角 75.5°、
    空中第一 tick 90° / 其余 50° 的 yaw 限速），起身时把 1.8.9 的 `jumpTicks`
    设成 2（对应 OpenZen 的 `setJumpDelay(2)`）。
  - `OLD TELLY`：单 tick 180° 直接转向，起步时用相机 yaw。
  - `KEEP Y`：移动时按住空格，保持桥面高度。
- 目标高度：`targetYLevel = floor(y) - 1`，只在“落地 / 停下 / 按住空格 /
  `NORMAL`”时刷新；其余时间锁住，这是 telly 能连续向上铺的前提。
- 目标格搜索：从 `targetYLevel` 开始，半径 1..6 的菱形向下扩；目标格必须是空气，
  六个邻居里要有实心支撑面，且面中心到眼睛 ≤ 4.5 格、法线朝向眼睛（`dot >= 0`）。
  放置点记成“支撑方块 + 指向目标格的那一面”。
- 点击点（aim）：`Placement` 在选定支撑面时取一次**面内随机点**
  （`randomFacePoint`：法线方向精确落在面平面上，切向 ±0.3 格），aim 指向这个点，
  C08 的 `hitVec` 也用同一个点（能取到 aim 射线与面平面的交点时优先用交点，
  telly 被限速夹住时也不会让 vec 落在射线外面）。同一个目标在命中期间复用同一个点，
  换目标才重掷。这样每一下都点在面上某个随机位置，而不是永远停在方块几何中心。
- 放置节奏：`pending → flush`。tick N 把 aim 写进 C03；tick N+1 开头用
  **服务端已经收到的那个角度**在当前眼睛位置重做 ray trace，命中才发 C08；
  打不到就重试（上限 2 次）。放置成功的那一 tick 把 aim 冻结成刚校验过的角度
  （`holdPlaceAim`），保证 C08 之后那条飞包携带同一个角度（Grim `RotationPlace`）。
- `CLUTCH`：用两 tick 垂直模拟判断“再掉下去目标就够不着了”，够不着时按住 aim
  不放（`rotationDelay` 上限 8）；收到自己的 S12 且水平速度 ≥ 1.5 时
  `velocityDelay=60`，期间同样走这个分支。
- `SNEAK`：OpenZen 的 18→21 tick 潜行节拍（第 18 tick 停冲刺 + 按潜行，第 21 tick
  松开）；`EAGLE` 是 `NORMAL` 下“脚下半格收缩一圈后没有碰撞面”才潜行。
- `SLOT`（`scaffold.slotMode`）决定“手里的方块从哪来”：
  - `HOTBAR`（默认）：真的换到方块槽 —— 只改客户端 `currentItem`，C09 由原版发
    （1.8.9 每 tick 的 `playerController.updateController()` 和每次右键的第一步都是
    `syncCurrentPlayItem()`，它按内部缓存补一条 C09）。**模块自己不碰 C09 包**：
    手发一条之后原版还会补一条相同 slot 的，服务端连着收到两条一样的
    `C09PacketHeldItemChange` 就是 Grim `BadPacketsA`（`Sent duplicate slot id`，
    日志里 `BadPacketsA: slot=1/0`）。C08 用 `getHeldItem()` 那一组物品，
    选中槽 / C08 物品 / C09 顺序三者自洽；换手那一 tick 不放置，下一 tick 的 C08
    一定排在原版那条 C09 之后。手上的不是方块时直接不放置，绝不伪造 C08。
    方块搭完会正常换回开启模块时的槽位。
  - `SILENT`：OpenZen 的旧行为，tick 末尾把客户端槽位切回开启时的槽（不发 C09），
    手部渲染看到旧物品、服务端认为手持方块（`ITEM SPOOF`）。槽位本身不一致，属于
    可判定特征，只在明确需要时开启。
- 快捷栏：1.8.9 不会在改 `inventory.currentItem` 时立即同步，但原版每 tick 都会
  `syncCurrentPlayItem()` 自查一次，所以切槽只改客户端字段即可，C09 交给原版。
  关闭模块会切回原槽，同样由原版补包。
- HUD：OpenZen 的“图标 + 计数条 + `N blocks` / `x.xxb/s`”，进度条颜色 `#9900FF`，
  换方块时弹簧过渡；`scaffold.hud` 控制显示。
- 世界渲染：目标空气格画成蓝色半透明方块（`RenderWorldLastEvent`）。
- 与 OpenZen 的落地差异（OpenMaver 没有 Mixin / EventManager）：
  - 静默转向在 `ScaffoldMovementInput`（vanilla `updatePlayerMoveState()` 之后、
    `moveFlying()` 之前）写 `rotationYaw/Pitch`，`ClientTickEvent.END` 恢复相机；
    第一人称不动，F5 身体保留服务端最后收到的 aim。
  - MoveFix 在同一个 hook 里把输入吸附到相对 aim 的 8 个整数方向，并把
    `forward` 等比放大到 0.8，避免原版随后关掉冲刺（否则 Grim `Simulation` 会看到
    任意角度的位移）。
  - `delayPackets`（OpenZen 在 clutch 分支拆 tick）没有等价注入点，只保留语义：
    hold aim + `rotationDelay` 计数 + 到期重试，不再额外发 C05。
  - 空闲时（没有目标格）aim 回落到相机角度，避免 OpenZen 那份 `(0,0)` 让服务端
    视角在没有目标时漂到 yaw 0。
- 实现位置：`mc/ScaffoldFeature.java`（完整行为）、`mc/ScaffoldMovementInput.java`
  （静默 aim + MoveFix hook）、`mc/ScaffoldHud.java`（HUD）；`java/module/movement/Scaffold.java`
  只负责模块开关；`OpenMaverFeatures.onTickStart()` / `onTick()` 每 tick 驱动。
- 回归测试：`tools/scaffold-test/run.sh`（70 项：决策表、旋转数学、支撑面映射、
  clutch 窗口、HUD/GUI 与源码结构断言）。
- 实现说明：`design/SCAFFOLD_HANDOFF.md` 记录状态机、包序与反射/SRG 名称；
  `design/SCAFFOLD_ROTATIONPLACE_HANDOFF.md` 保留 Grim `RotationPlace` 的判定链与
  对测记录（新实现仍然遵守它）。

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

### Velocity 防击退（SPRINT SLAP / Blink Burst）

> **2026-11-16 结论修正**：Grim 上真正能用的只有 `SPRINT SLAP`。它一个字节的
> 运动都不改，靠 1.8 原版「冲刺攻击把自己的水平速度 ×0.6」削击退 —— 这条 0.6
> 在 Grim 的 `PredictionEngine.addAttackSlowToPossibilities` 里本来就是一条
> 预测分支（每个攻击包一条，上限 5），所以既不欠位移也不吃 AntiKB。
> 其余模式（`UNCERTAINTY` / `BLINK BURST` / `REVERSAL` / 低比例 `MODIFY`）在
> Grim 上必然产生 flag，`GRIM GUARD` 现在一律把它们升级成 `SPRINT SLAP`。
> 实机证据与逐条归因见 `design/VELOCITY_HANDOFF.md` 顶部。
>
> **2026-11-16 19:1x（18:53 那局 vl 涨到 83 之后，按 Grim 2.0 源码重做）**：
> `SPRINT SLAP` 的思路没错，错在 0.6 分支的成立条件。Grim 的
> `PacketPlayerAttack` 里，没拿击退附魔的 1.8 客户端只有
> `player.lastSprinting`（**上一 tick 结束时的冲刺状态**）为真时才会生成
> `(0.6, 1, 0.6)` 预测分支，而且每 tick 只给一条
> （`maxAttackSlow = minAttackSlow = 1`）。原实现攻击前
> `setSprinting(true)`、原版攻击里 `setSprinting(false)`，tick 结束时冲刺是关的
> → 下一发 0.6 一个分支都没有 → offset 恰好 `0.4 × v`：
> `.143976 = 0.4×0.36`、`.046243 = 0.4×0.1156`、`.006153 = 0.4×0.0154`，
> 和日志逐条对应。现在：
> 1. 原版乘完**立刻把冲刺打开**，tick 结束保持冲刺（下一发才有 `lastSprinting`）；
> 2. 出手闸门 `isSprinting && sprintAtLastTickEnd`，本 tick 已有别的 C02 也不打
>    （KillAura 双刀 = 0.36 对 0.6）；
> 3. 窗口从 `kbArriveTick + 1` 开始（Grim 的击退由两个 transaction 夹住 S12，
>    同批事务下一 tick 才确认；`AntiKB` 的 `offsetToFlag` 默认只有 0.001）；
> 4. 原版没乘 0.6（目标无敌帧）时**不补乘**；距离卡 2.9 格；窗口上限 5。

- 配置路径：`movement.velocity`，默认关闭，默认键位留空（自行绑定）。
- 逻辑在 `mc/VelocityFeature.java`，Netty 管线处理器在 `mc/VelocityPacketHook.java`。
- **数据源**：入站 `S12PacketEntityVelocity`。本模块把 handler 插在 1.8.9 管线的
  `packet_handler` 之前（即 `encoder` 之后），在同一位置拿到入站、出站两个方向的
  `Packet` 对象：入站直接吞掉自己的击退包，出站按 blink 需要扣住 C03 移动包。
  - 为什么不是插在 `encoder` 之前：出站方向从 tail 往 head 走，`packet_handler` 先于
    `encoder` 执行；排在 `encoder` 之前的 handler 只能拿到序列化后的 ByteBuf，
    blink 会完全失效。管线名与顺序取自真实 1.8.9 jar 的 `NetworkManager`。
  - 注入失败（例如别的 mod 重写了管线）时自动退化为运动特征嗅探：水平速度 ≥ 0.33
    且与上一 tick 的快照有明显突变、垂直非负（冲刺跳约 0.28、下落为负，都不会误触）。
- **模式**（`velocity.mode`）：
  - `MODIFY`：水平/垂直击退按比例缩放，0% = 完全无视，100% = 原版。
  - `REVERSAL`：先吃掉击退，延迟 `REVERSAL DELAY` 毫秒后把反向速度打出去，把自己
    推向对手。
  - `STRAFE`：把向后的击退转成当前输入方向上的速度；没有输入时沿击退法线侧移
    （绕对手走位）。
  - `JUMP RESET`：击退到达的瞬间（可延迟 0~3 tick）起跳，用跳跃覆盖击退水平分量。
    触发点是击退事件本身，不依赖 `hurtTime` 之类 SRG 名相近的受击字段。
  - `NOXZ`（**GRIM 预设默认**，OpenZen `AntiKB/NoXZ` 移植）：空中挨打时吞掉 S12、
    冻结出站 C03、扣住 `S32`（transaction 回执）→ 落地再放位置包、补给客户端击退、
    连打 `NOXZ ATTACKS` 次（每次 ×0.6），下一 tick 才回执。核心是**骗 Grim 的
    transaction 时序**：回执没回去，它只能把这段击退当成「可能还没到」，预测里
    「没吃击退」也是合法分支。
  - `SPRINT SLAP`（不依赖 transaction 时序）：S12 **原样放行**（客户端真的吃这次击退），
    记录 `|kb|` 并开一个窗口（上限 5 tick = Grim 的 attack-slow 分支数，
    0.6^5 ≈ 7.8%）；窗口**从收到击退的下一 tick 开始**，每 tick 打一次**准星里
    2.9 格内**的实体，由原版冲刺攻击把 `motionX/Z` 乘 0.6，冲刺状态用
    `C0BPacketEntityAction` 在 C03 之前同步。不发 C08、不憋包、不写 motion，
    所以 `AntiKB / Simulation / Timer / FabricatedPlace / Hitboxes /
    GroundSpoof / BadPacketsN` 一个都不会出现；原版这一下没乘 0.6（目标无敌帧 /
    够不着）时整 tick 放弃，绝不自己补乘。
  - `BLINK BURST`：收到击退后憋住 `BLINK TICKS`（默认 5）个 tick 的位置包，窗口
    结束时**先一口气补发 `BURST ATTACKS`（默认 5）条攻击包**（C0A -> C02 交替，
    服务端此时仍用憋包前的旧位置结算），**再补发缓存的位置包**。
    `FLUSH POSITION` 关闭时改为直接丢弃位置包（表现成瞬移）。
    仅用于对测，Grim 上会吃 `Timer / Simulation / BadPacketsN`。
- **服务器预设**（`velocity.server`）：`CUSTOM / VANILLA / HYPIXEL / GRIM / INTAVE`，
  只是把模式与参数配成一组针对该服务端反作弊观感的组合，之后仍可继续微调：
  - `VANILLA`：`MODIFY 0/0%`。
  - `HYPIXEL`：`MODIFY 12/0%` + 起跳延迟 1 tick（保留少量水平更接近人手）。
  - `GRIM`：`NOXZ x3`（`velocity.horizontal/vertical = 100%`，即完全不改击退包），
    守护把任何归零类模式（REVERSAL / UNCERTAINTY / BLINK / 低比例 MODIFY）升级成
    `SPRINT SLAP`。
  - `INTAVE`：`REVERSAL 100ms`、垂直 35%（击退判定有时间窗）。
- 与 bypass profile 的联动：`LEGIT` 档下 blink 降级为 `JUMP RESET`，水平/垂直
  保留比例至少 45% / 65%，避免一次抹平被运动预测直接判出；GUI 会显示 `OFF (LEGIT)`。
- 主要设置（`config/openmaver.properties` 的 `velocity.*`）：
  `velocity.mode / server / horizontal / vertical / reversalDelay / reversalStrength /
  strafeFactor / jumpDelay / blinkTicks / burstAttacks / burstRange / blinkFlush /
  slapAim / slapMaxTicks / slapSprintSync / noxzAttacks / noxzTx`。
- 包层测试：`tools/velocity-test/run.sh`（真实 netty 4.0.23.Final；91 + 16 项断言，
  其中 `VelocityInstallTest` 用真实注册通道覆盖「注入 / 换连接重挂 / 被摘除自愈」）。
- 换连接（单人 → 服务器、切服、重连）会自动重挂管线；若钩子没生效，
  ClickGUI 摘要末尾的 `KB n` 与 `logs/openmaver.log` 的 `[Velocity]` 行就是判据。

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
  透视 pass 会临时清空自定义名，避免与原版名字标签重复绘制。
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
- 通过限制 `Minecraft.rightClickDelayTimer` 加快右键放置/使用物品；
  关闭模块、打开 GUI、玩家死亡或切换到非方块物品时恢复原版延迟。
- profile 行为：
  - `legit`：完全不修改 `rightClickDelayTimer`。
  - `balanced`：不低于原版 **4 tick** 冷却。Matrix / Grim 的 `block.fastplace`
    按 C08 间隔判定，2~3 tick 的连续放置会稳定吃 flag，因此默认档位不再加速。
    与 Scaffold 同时启用时仍会让位（不修改 timer）。
  - `aggressive` / 关闭 bypass：按 `DELAY` 设置，允许压到 0。
- 设置项（ClickGUI 中展开 FastPlace）：
  - `DELAY`：`fastplace.delayTicks`，0~4 tick，默认 `4`。
  - `ONLY BLOCKS`：`fastplace.onlyBlocks`，默认 `true`，仅在手持方块时生效。
- AutoClicker 的 `RIGHT CLICK` 在 `balanced/legit` 下也会等待这个原版冷却归零，
  否则 10~20 CPS 的右键会以同样速度连续发 C08，单独触发 `block.fastplace`。

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
  - `FULL CHARGE`（默认）：按 1.9+ 武器满蓄力等待，单次 1.0 倍。间隔为
    剑 13 tick、镐 17、铲 20、斧 20~25（按材质）、空手 11（受击无敌帧下限）。
  - `FASTEST`：只按 10 tick 受击无敌帧节流（约 2 次/秒），单次倍率较低，
    但近战对拼时命中更密、击退压力更大。适合斧/重武器或想打近战的场景。
  - `VANILLA`：旧行为，`C0A -> C02` 原版顺序 + 11 tick 节流。适合
    `/server legacy` 1.8 战斗（没有攻击冷却；Grim 1.8 的 `PacketOrderB`
    要求先挥手）。
  三个模式都会在等待期间**不发 C0A**：1.9.2+ 服务端会把任何 swing 动画当成挥击
  并清空蓄力（MC-255058）；`FULL CHARGE` / `FASTEST` 还会把 C02 放在 C0A 之前，
  保证攻击吃到完整蓄力。`VANILLA` 保留原版顺序，只用于 1.8。
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
  设置的 `CPS MIN ~ CPS MAX`（上限 20）。`HUMANIZE` 的 2% 长停顿仍可能让个别
  1 秒窗口略低于设置值，需要稳定数值时关闭 `HUMANIZE`。
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
  - v6 迁移：旧配置的 `criticals.mode=packet/ncp/...` 会迁移到 `jump`，同时
    `fastplace.delayTicks` 提升到 `4`，避免升级后继续对着 Matrix 吃
    `ka.critical.*` / `bp.timer` / `block.fastplace`。
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

目标进程的标准输出会打印 `[OpenMaver] Core initialized`、`版本 B2.1.2`、
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
[INFO] [OpenMaver] 版本 B2.1.2 启动完成
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
| 云端库版本 | 日志中打印 `版本 B2.1.2` 与 `getVersion()` 一致 |
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
| `module.combat.Criticals` | `combat.criticals` |
| `module.render.Esp` | `render.esp` |
| `module.render.MotionBlur` | `render.motionblur` |
| `module.tools.Logger` | `tools.logger` |

需要自定义时用 `super("Name", "explicit.key")` 显式指定。

## 配置热重载

`ModuleManager.onTick()` 每 100 tick 调用一次 `Config.reloadIfChanged()`；
文件被外部修改时会自动重解析，并调用所有模块的 `syncFromConfig()` 同步开关状态。
该逻辑位于 `ModuleManager`，因此独立模式与 Forge 模式行为一致。

## 字符串混淆

`core/jni/bridge.cpp` 中的文案常量通过编译期简易 XOR 混淆，
避免明文（如版本号）直接出现在 `strings` 输出中。修改 `obf::kKey` 即可重新混淆。
