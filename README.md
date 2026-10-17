# OpenMaver V2.0

私人服务器反作弊对测研究用的客户端骨架，适配 **Minecraft 1.8.9**（Forge 11.15.1.2318）。

> 说明：本仓库当前仅包含**基础设施骨架**（JNI 桥接、配置、日志、模块基类、Forge 事件桥接），
> 不包含任何具体功能模块的实现。请仅在你拥有或获得明确授权的服务器上进行测试。

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
│       └── movement/AutoSprint.java  # 示例模块（链路验证器）
├── inject/                        # 运行期注入器（C++，可选）
│   └── injector.cpp               # 自研注入器：--re-self / --re-jattach
├── mc/                            # Forge 1.8.9 集成（源码为 MCP 名，构建时 reobf 成 SRG）
│   ├── mcmod.info                 # Forge mod 元数据（打包进 mod jar）
│   ├── OpenMaverMod.java          # @Mod 入口
│   ├── OpenMaverClientHandler.java# 客户端 tick / 渲染事件桥接 + 进入世界启动
│   ├── OpenMaverHud.java          # 右下角「注入成功！！」提示（长方形 + 平滑动画）
│   └── GuiOpenMaverClickGui.java  # 游戏内 ClickGUI（RSHIFT 开关，点击模块）
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
4. 启动游戏 → 进入世界，右下角会出现「注入成功！！」，RSHIFT 打开 ClickGUI。

> 用 PrismLauncher 的 vanilla 实例直接实现不了——`mc/` 的事件（`TickEvent`、`RenderGameOverlayEvent` 等）
> 全部来自 Forge，必须先给该实例装上 Forge。

## 运行

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
| 首次进入世界（`ClientTickEvent`） | `OpenMaver.start()` + 右下角弹「注入成功！！」 |
| `TickEvent.ClientTickEvent` (END) | `ModuleManager.onTick()` |
| `RenderWorldLastEvent` | `ModuleManager.onRender()` |
| `RenderGameOverlayEvent.Post` | `OpenMaverHud` 绘制右下角提示 |
| RSHIFT（在游戏内） | 开关 `GuiOpenMaverClickGui` |

配置文件默认读取游戏工作目录下的 `config/default.jsonc`
（即 `.minecraft/config/default.jsonc`），可用系统属性覆盖：
`-Dopenmaver.config=/绝对路径/config.jsonc`。

> 本节描述的「把原生库放进 jar、首次运行解压再 `System.load`」已经实现（见
> `OpenMaver` 的 `loadBundledNativeCore`）：`build/openmaver-mod.jar` 里就带着
> `native/<os>/<arch>/libopenmaver_core.so`，开箱即用。

## 游戏内 UI（进入世界后）

按需求，OpenMaver 的核心**延迟到玩家真正进入世界**时才启动，并给出可视反馈：

- **右下角提示** `mc/OpenMaverHud.java`：一个长方形卡片，显示「注入成功！！」。
  动画为「**滑入 350ms（ease-out）→ 停留 2500ms → 滑出 350ms（ease-in）+ 淡出**」，
  从屏幕右侧滑入右下角；用 `System.nanoTime()` 计时，与帧率无关，因此是平滑的。
  绘制在 `RenderGameOverlayEvent.Post`（`type == ALL`）里，此时 GL 矩阵已是缩放坐标。
- **游戏内 ClickGUI** `mc/GuiOpenMaverClickGui.java`：游戏内按 **RSHIFT** 开关，
  中央面板列出所有模块，点击即可开关（`Module.setEnabled`）。

两者都在 `OpenMaverMod.init()` 里注册到 `MinecraftForge.EVENT_BUS`；
进入世界由 `OpenMaverClientHandler.onClientTick()` 首次检测到 `theWorld/thePlayer`
非空时触发：

```java
if (!worldEntered) {
    worldEntered = true;
    if (!OpenMaver.isStarted()) OpenMaver.start(); // 进入世界才启动核心
    OpenMaverHud.success("注入成功！！");            // 右下角弹提示
}
```

> 说明：HUD / ClickGUI 依赖 `net.minecraft.*`，由 Forge 的类加载器加载。
> 除了随 mod 加载，**纯运行期注入**也能显示（见下面「注入也能显示 UI」一节）。

## 运行期注入（不重启、不改启动参数）

`libopenmaver_core.so` 同时实现了 JVMTI agent（`core/jni/agent.cpp` 的 `Agent_OnAttach`）。
attach 时它会在目标进程内：
1. 用 `jar=` 参数把 `openmaver-core.jar` 加入 **bootstrap** 搜索路径，加载并启动核心；
2. 用 `mcjar=` 参数把 `openmaver-mc.jar`（SRG 类）加入**游戏类加载器**
   （`LaunchClassLoader.addURL`），并反射注册 HUD / ClickGUI 到 `MinecraftForge.EVENT_BUS`。

### 注入也能显示 UI（无需装 mod）

前提：目标是**装了 Forge 的 MC 1.8.9**（Java 8）。一条命令搞定：

```bash
cd /home/zty/OpenMaver
./build/bin/openmaver_injector --find                 # 嗅探，确认目标
./build/bin/openmaver_injector --re-self \
  --so    "$(pwd)/build/lib/libopenmaver_core.so" \
  --options "jar=$(pwd)/build/openmaver-core.jar" \
  --mcjar "$(pwd)/build/openmaver-mc.jar"             # ← 默认就是它，可省
# 省略 --pid 会自动选嗅探到的目标
```

成功后：目标进程控制台出现 `[OpenMaver] 已注册游戏内 UI 到 Forge 事件总线`，
**进入世界后右下角弹出「注入成功！！」，按 RSHIFT 打开 ClickGUI。**

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
