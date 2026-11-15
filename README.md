# OpenMaver B2.1.2

私人服务器反作弊对测研究用的客户端骨架，适配 **Minecraft 1.8.9**（Forge 11.15.1.2318）。

> 说明：本仓库包含**基础设施骨架**（JNI 桥接、配置、日志、模块基类、Forge 事件桥接）
> 与基础功能模块（Aim Assist、Player ESP、AutoSprint、AutoClicker、KillAura、Reach、Criticals、Scaffold、FastPlace、FakePlayer、Target HUD、Player X-Ray、Motion Blur、FPS / CPS 统计）
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
│       ├── movement/Scaffold.java # 自动搭路/神桥开关（MC 侧逻辑在 ScaffoldFeature）
│       ├── player/FastPlace.java  # 快速右键开关（MC 侧逻辑在 OpenMaverFeatures）
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
│   ├── GuiOpenMaverClickGui.java  # 游戏内 ClickGUI（分类 / 搜索 / 展开设置）
│   ├── GuiMaverMainMenu.java      # Badlion 风格主菜单（Logo / 按钮 / 账号 / 快捷菜单）
│   ├── MaverWallpaper.java        # 主菜单壁纸（静态图片 / GIF / 内置动态背景）
│   └── MaverLogo.java             # 打包的 Maver Logo 纹理绘制
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
  使用原版全景图 + 高斯模糊背景，正上方六边形 Maver Logo、`Maver` 标题、
  `A Open Src Cheat Client` 副标题、
  Singleplayer / Multiplayer / Client Settings / Configs / Options / Quit Game 按钮，
  右上角账号胶囊（从微软 / Mojang 官方皮肤服务获取的账号头像 + 用户名）与左上角快捷菜单。
  Client Settings / Configs 会直接打开对应页面的 ClickGUI；快捷菜单里的 **Wallpaper**
  会直接打开壁纸设置页。背景可以替换成静态图片、动态 GIF 或内置动态渐变，
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
  （或主菜单左上角快捷菜单的 **Wallpaper**）可以切换开始菜单背景：

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
- `aim/AimModulo360`：`|deltaYaw| > 320` 会 flag；所有自动 yaw 都 wrap 到
  `[-180, 180)`，并且单 tick 限速。
- `scaffolding/RotationPlace`：C08 使用服务端已收到的 yaw/pitch 做 ray trace，
  而 1.8 的 C08 在 C03 之前到达服务端，因此校验时只有**上一 tick**的 rotation。
  Scaffold 现在把 aim 写进本 tick 正常的 C03，放置延迟到下一 tick，用同一个
  aim 重新做 ray trace，不再补发 C05。
- `timer/Timer`：每个 tick 包固定按 50ms 计费；Scaffold 不再额外发
  `C05PacketPlayerLook`，每 tick 只有一条 C03 族包。
- `multiactions/*`：使用物品时攻击/挥击、同 tick 方块+实体交互、移动中开背包都会
  flag；AutoClicker/Reach 已加入互斥与使用物品保护。

**观测**：`debug.enabled=true` 时记录 aim rotation 变化、C02 攻击、
C08 放置、点击间隔、被抑制的 C05，以及每 100 tick 的统计。默认关闭，
IO 失败只记录一次并自动停用 debug。

完整审计、Grim commit、checks.yml 对照项与对测记录模板见
`design/GRIM_BYPASS_HANDOFF.md`。

### Scaffold 自动搭路（Myau 全量行为移植）

- 配置路径：`movement.scaffold`，默认关闭，默认键位 **B**。
- 由原 `GodBridge` 重命名而来；当前实现按 `OpenMyau-Fix` 的 `Scaffold` **逐项移植**：
  - 7 种旋转模式：`NONE / DEFAULT / BACKWARDS / SIDEWAYS / OFFSET / SNAP / HYPIXEL`
  - `MoveFix`、`Sprint`、`Ground/Air/Speed Motion`
  - `Tower`：`VANILLA / EXTRA / HYPIXEL`，以及 `KeepY`：`VANILLA / EXTRA / TELLY / EXTRATELLY`
  - Telly 起步/常规旋转速度、`AngleDiff`、`Candiffplace`
  - `MultiPlace`、`SafeWalk`、`Swing`、`ItemSpoof`、`BlockCounter`
  - 自动切换快捷栏方块、方块数量提示
  - Scaffold 开启期间接管左/右键和挖方块，避免原版抢占放置
- 与 Myau 的差异：OpenMaver 没有 Mixin/EventManager，旋转、Strafe、SafeWalk、
  输入拦截用 ClientTickEvent + 反射近似。
  - 静默旋转采用 **tick 内换视角**：`ScaffoldMovementInput` 在原版
    `updatePlayerMoveState()` 之后把 aim 写进 `rotationYaw/Pitch`，本 tick 的 C03
    自然携带；ClientTickEvent.END（渲染之前）再恢复相机角度。第一人称视角不变，
    F5 身体/头保留服务端最后收到的 aim。
  - `MoveFix` 在同一个 hook 里按 Myau 的 `fixStrafe` 把 `movementInput` 吸附到
    相对 aim 的 8 个整数方向之一，WASD 的世界方向仍然跟相机。**不能连续旋转**，
    否则客户端会以任意角度移动，Grim 的 `Simulation` 只能尝试 8 个整数方向，
    会产生 `.002 ~ .03` 的持续位移误差。
  - 放置目标选择：支撑方块必须与目标空气格直接相邻，并对每个候选面做真实
    `rayTrace` 校验；被脚下方块挡住的侧面会自动换下一个支撑面（以前只取“最近
    的支撑方块”，平桥上前方一格经常因为侧面被顶面遮挡而放置失败）。
  - 放置节奏：玩家还站在实心方块上时，用**下一 tick 的预测位置**提前把 aim 写进
    C03，下一 tick 开头再用同一角度 flush，因此走/跑/转头都能跟上。预测优先使用
    当前实际速度（惯性），快速转头时不会再按新按键方向把目标格算到错误一侧；
    瞄准点优先取面中心，flush 失败时会用当前眼睛重算 aim 并重排一次。
  - 在方块边缘原地转头时，如果距离相机方向的边缘 ≤0.55 格，会把相机前方一格
    也作为目标，因此在边缘停下转身也能继续铺路。
  - 对 Grim：开启 bypass 时不即时放置、不在 flush 时换角度/换 hitVec（否则会触发
    `PositionPlace`/`RotationPlace`），只保留“预测 aim → 下一 tick 同一角度放置”；
    玩家已经踏空时也排队一 tick。
  - `SafeWalk` 改为原版潜行防边缘的收缩步长，不再在离边缘 0.45 格时清空
    `motionX/Z`。**开启 bypass 时 SafeWalk 会自动禁用**，因为它直接改 motion，
    是 `Simulation` 的主要来源。
- 使用方式：
  1. 手持可放置方块，正常行走、后退或起塔；
  2. 默认 `rotations=DEFAULT` 会自动转向对角线/桥面方向；
  3. `tower` 设为 `VANILLA/EXTRA/HYPIXEL` 后按住空格起塔；
  4. `keepY` 用于在跳跃/起塔期间保持桥面高度；
  5. 打开 GUI/聊天、死亡、空手时停止；关闭模块会恢复原快捷栏槽位。
- 主要设置（ClickGUI 中展开 Scaffold，写入 `config/openmaver.properties`）：
  - `ROTATIONS`：`scaffold.rotations`，默认 `DEFAULT`。
  - `MOVE FIX`：`scaffold.moveFix`，默认 `SILENT`。
  - `SPRINT`：`scaffold.sprint`，默认 `VANILLA`（保留冲刺；需要 Grim 对测可改回 `NONE`）。
  - `GROUND/AIR/SPEED MOTION`：`scaffold.groundMotion/airMotion/speedMotion`，0~100%，默认 100。
  - `KEEP Y ON PRESS`：`scaffold.keepYonPress`，默认 false。
  - `MULTI PLACE` / `SAFE WALK` / `SWING` / `ITEM SPOOF` / `BLOCK COUNTER` / `CANDIFFPLACE`。
  - `DELAY TICKS`：`scaffold.towerFlatTicks`，0~20 tick，默认 4。
  - `ANGLE DIFF`：`scaffold.angleDiff`，0~180°，默认 50。
  - `TELLY START/NORMAL MIN/MAX`：`scaffold.tellyStartRotMin/Max`、`scaffold.tellyNormalRotMin/Max`。
  - `ROTATION SPEED`：`scaffold.rotationSpeed`，默认 `NORMAL`。
  - `TOWER`：`scaffold.tower`，默认 `NONE`。
  - `KEEP Y`：`scaffold.keepY`，默认 `NONE`。
- 实现位置：`mc/ScaffoldFeature.java`（完整行为），`java/module/movement/Scaffold.java`
  只负责模块开关；`OpenMaverFeatures.onTickStart()` 每 tick 驱动。
- 实现说明：`design/SCAFFOLD_HANDOFF.md` 记录了状态机、旋转模式、Tower/KeepY 与反射/SRG 名称。

### FPS / CPS 统计 HUD

- 配置路径：`render.fps` / `render.cps`，**默认开启**，位置固定在左上角。
- 布局：CPS 小正方形在最左，FPS 圆角胶囊在它右边。
- 视觉：灰色调局部毛玻璃（与右下角提示同一套 `GuiBlur` 参数）+ 白色文字。
- FPS：统计每秒 `RenderGameOverlayEvent.Post` 的帧数，满 1 秒结算一次；
  首个统计窗口完成前显示 `FPS --`。
- CPS：每帧轮询鼠标左 / 右键的按下边沿，分别显示 `L`（左键）/ `R`（右键）
  最近 1 秒内的点击次数，数值会随时间自然回落。
- 实现说明：绘制在 `mc/StatsHud.java`，由 `OpenMaverHud` 在
  `RenderGameOverlayEvent.Post` 中调用；ClickGUI 打开时随其他 HUD 一起隐藏。

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
