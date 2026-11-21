# Grim 对测与低检测面 profile（接力说明）

> 仅用于你拥有或明确授权的服务器对测。本文所有结论基于 Grim 2.0 开源分支
> commit `f9b801a4d02ce0f56bddecc26a2afecb7721658d`（2026-11-13 拉取）。
> **实际服务器使用的 Grim 版本/commit 与 `checks.yml` 仍需在目标服确认**；
> 若服务器使用其它分支或改过阈值，请以 `/grim verbose` 的实际数值为准重新校准。

## 1. 本轮审计结论

### 1.1 Reach / Hitboxes

Grim `combat/Reach` 对 1.8 客户端：

- `maxReach = ENTITY_INTERACTION_RANGE = 3.0`。
- 目标 AABB 扩张：
  - `Reach.threshold` 默认 `0.0005`
  - 1.8 hitbox margin `+0.1`
  - 若需要 movement threshold，再 `+0.03`
- 判据：`minDistance > maxReach` 时 flag。也就是“眼睛到扩张后 AABB 的距离 >
  3.0”。折算回真实命中箱约 **3.13 格**。
- `Hitboxes` 在射线完全没命中 AABB 时由 Reach 触发。

因此旧实现的 5.9 格上限必然被 flag/取消。安全实现应使用 **0.1 扩张箱（1.8 原版
hitbox margin）度量并把眼距压在 3.0 以下**：我方射线就是服务端已收到的 rotation，
Grim 只会取当前/上一 tick look 的最小值，所以 ≤3.0 即必然不超线。当前实现
`balanced=2.97 / aggressive=2.99`（换算真实命中箱约 3.07~3.09 格）。

> Round 2 修正：`aggressive=3.30` 实测在 3.12~3.17 的命中就是“真实 3.02~3.07 格”，
> 一旦 `PacketOrderB` 不再取消攻击，`Reach` 会立刻接手 flag。

### 1.2 Aim

- `AimDuplicateLook`：连续两次 rotation 完全相同（yaw/pitch 都不变）即 flag。
- `AimModulo360`：`|deltaYaw| > 320` 且上次 `< 30` 即 flag。
- `AimProcessor`：统计 rotation delta 的 GCD，可反推鼠标灵敏度。
  固定非 GCD 步长、每 tick 相同 delta 都是明显特征。
- 额外 `C05PacketPlayerLook` 会被 `Timer` 当成额外 tick。

本项目策略：

- rotation 在 `ClientTickEvent.START` 应用，早于 vanilla 发送 C03。
- 单 tick 限速 + GCD 量化 + yaw wrap。
- 不额外发送 C05。
- 没有实际变化不写 rotation 字段。

### 1.3 PacketOrder / MultiActions / Sprint / NoSlow

- `PacketOrderB`：1.8 必须 `ANIMATION -> INTERACT_ENTITY`。**关键细节**：Grim 在每条
  tick 包（C03/C04/C05/C06）到达时把 `sentAnimation` 清空，所以 C02 前面必须有一条
  **同一 tick、且在该 tick 包之后**的 C0A。挥击动画有 6 tick，`isSwingInProgress`
  在高 CPS 下恒为 true，不能用它判断“动画已经发过”；1.8.9 的
  `EntityPlayerSP.swingItem()` 无条件发 `C0APacketAnimation`，每次补刀都应调用。
  另外攻击必须早于本 tick 的 C03，否则 `Post` 会把 C02 当成“移动包之后的攻击”。
- `PacketOrderC/I/N`：交互/攻击/使用/放置/挖掘同 tick 顺序敏感。
- `MultiActionsA/E`：使用物品时攻击/挥击会 flag。
- `MultiActionsC/D`：移动中开/关背包会 flag。
- `MultiActionsF`：同 tick 方块交互 + 实体交互会 flag。
- `SprintA/C`：低饥饿/使用物品时疾跑会 flag。
- `NoSlow`：使用物品时移动速度未按原版减速会 flag。

### 1.4 Placement

- `RotationPlace`：用服务端已收到的 `player.yaw`、`lastYaw`、pitch、眼睛高度和
  4.5 格 ray trace 校验 C08 目标箱。**只改客户端 rotation 不发包必然失败。**
- `FarPlace`：`4.5 + hypot(0.03,0.03)`；必须使用游戏自身射线/真实 reach。
- `PositionPlace`：不能点击被遮挡的隐藏面。
- `FabricatedPlace`：cursor 必须在 `[0,1]`（特殊方块 1.5）。
- `MultiPlace`：同 tick 多次放置不同位置才 flag；每 tick 一次本身不触发。
- `DuplicateRotPlace`：连续放置时重复完全相同的 yaw delta（>2°）会 flag。
- `InvalidPlaceCursor/Face`：NaN/越界 cursor、非法 face id 会 flag。

本项目策略：

- 旧的 `OpenMaverFeatures.tryPlaceBlock` 路径在 `legit/balanced` 下只使用真实
  准星，自动旋转/C05 被抑制。
- `ScaffoldFeature` 使用 **tick 内换视角**：aim 写进本 tick 正常的 C03，
  C08 延迟到下一 tick、用服务端已收到的同一个 aim 重新 ray trace。每 tick
  只有一条 C03 族包，没有额外 C05，因此不再触发 `Timer`/`Post`。
- 每 tick 最多一次放置；`MultiPlace` 在新旋转模式下不产生额外 C08。
- C08 使用 `PlayerControllerMP.onPlayerRightClick`，cursor/face/reach 由
  vanilla 生成；放置结束不需要恢复临时 rotation（rotation 在 tick END 恢复）。

### 1.5 Timer / Movement

- `Timer`：每个 tick 包固定按 50ms 计费；额外 C05 会触发。
- `GroundSpoof/NoFall`：伪造 onGround 会被预测引擎抓。
- 本轮不实现 Timer/Speed/Fly/NoFall/GroundSpoof，不伪造 ground。

## 2. Profile 语义

配置：`config/openmaver.properties`

```properties
bypass.enabled=true
bypass.profile=balanced     # legit | balanced | aggressive
debug.enabled=false
```

| 项目 | legit | balanced（默认） | aggressive |
|---|---|---|---|
| Reach 扩张箱上限 | 2.97 | 2.97 | 2.99 |
| Aim yaw/pitch 单 tick 上限 | 18°/12° | 35°/20° | 配置值/≥60° |
| AutoClicker 上限 | 8 CPS | 12 CPS | 20 CPS |
| 最小点击间隔 | 115ms | 75ms | 45ms |
| FastPlace | 不改 timer | 最快 2 tick + 2/3 tick 抖动 | 按配置，可 0 |
| Scaffold 放置 | C03 携带 aim + 延迟 1 tick | 同左 | 同左（Myau 全量） |
| Placement 自动旋转 | 强制 legit（旧路径） | 强制 legit（旧路径） | 允许 |
| Scaffold C05 | 不再发送 | 不再发送 | 不再发送 |
| Placement C05（旧路径） | 强制抑制 | 强制抑制 | 允许（实验） |
| packetRotation 默认 | 关闭 | 关闭 | 仍默认关闭 |

`bypass.enabled=false` 恢复旧的对比行为（Reach 5.9、旧 FastPlace/放置逻辑），
仅用于历史对比，不建议正式对测使用。

## 3. 关键实现位置

- `mc/OpenMaverFeatures.java`
  - `onTickStart`：autoclicker / aim / sprint（均需在 vanilla C03 之前）
  - `onTick`：fastplace / scaffold / reach
  - `updateReach` / `attackNearestEntityInReach`：Grim 扩张箱 ray trace
  - `aimAtNearest` / `stepAim` / `mouseGcd`：GCD + 限速
  - `updateAutoClicker`：CPS 上限、抖动、停顿、同 tick 互斥、MISS 保护
  - `tryPlaceBlock` / `tryPlaceWithCurrentAim`：placement 安全模式
- `mc/OpenMaverDebug.java`：默认关闭的日志/统计
- `mc/OpenMaverClientHandler.java`：`ClientTickEvent.START` 分支
- `mc/GuiOpenMaverClickGui.java`：Grim Bypass、GCD、MAX STEP、HUMANIZE
- `config/default.jsonc`：注释说明 profile 在 properties 中

## 4. 验收 / 对测记录模板

### 4.1 构建

```bash
cmake --build build -j
```

应产出：

- `build/openmaver-mod.jar`
- `build/openmaver-mc.jar`

### 4.2 基线

在目标服记录未开模块时的 `/grim verbose`，确认服务器实际加载的 checks 与阈值。

### 4.3 测试矩阵

| 场景 | 记录 |
|---|---|
| Reach 3.0 内攻击 | `/grim verbose` 是否出现 `Reach` / `Hitboxes` |
| Reach 3.1~3.2 攻击（aggressive） | `Reach` verbose 距离数值 |
| Aim Assist 平滑 | `AimDuplicateLook` / `AimModulo360` / `AimProcessor` |
| AutoClicker 30s | 点击间隔、CPS、是否有 `PacketOrder` / `MultiActions` |
| Scaffold 连续放置 | `RotationPlace` / `FarPlace` / `PositionPlace` / `MultiPlace` |
| KillAura 连续攻击 | `RotationPlace` / `Aim` / `PacketOrder` |
| FastPlace | 右键频率、`PacketOrderI/N` |
| 打开 GUI/聊天/死亡 | 是否残留 rotation、timer、点击 |

### 4.4 判定

- `legit/balanced` 目标：Reach/Aim/Place 相关 check 不产生 alert。
- `aggressive` 目标：只用于确认“修改后确实越过了哪个阈值”，不是可用配置。
- 若服务器 `/grim verbose` 的距离与本文不同，优先以服务器为准，并调整
  `REACH_SAFE_CAP` / `REACH_GRIM_BOX_MARGIN`。

## 5. 已知风险 / 未验证

0. **上服前必须确认**：`/grim version`（或服务器插件列表/启动日志）拿到实际
   Grim commit；再对照 `plugins/GrimAC/checks.yml` 的以下项：
   - `Reach.block-impossible-hits`（默认 true）
   - `Reach.threshold`（默认 0.0005）
   - `TimerA.drift`（默认 120 ms）
   - `NoSlow.threshold`（默认 0.001）
   若这些值被改过，本文的 3.0/3.13 结论需要按实际值重算。
1. **未确认目标服 Grim commit 与 `checks.yml`**：本文按 2.0 分支默认值实现。
2. 1.8.9 客户端实体位置与服务端补偿位置可能有一 tick 差；Reach 已用 3.0 扩张箱
   留出约 0.03~0.13 的余量，但高延迟/实体插值极端情况下仍建议观察 verbose。
3. `aggressive` 的 placement auto-rotate 在 END 阶段改 rotation，不会进入本 tick
   的 C03；它主要配合 `packetRotation` 做对比，仍会触发 `Timer`/Aim 类检查。
4. `AimProcessor` 的 GCD 反推依赖鼠标灵敏度；如果用户使用系统鼠标加速/自定义
   驱动，量化步长可能与服务端统计不完全一致。
5. Scaffold 在 `legit/balanced` 下不再压缩右键冷却，桥速会明显低于旧版本；
   这是有意为之，优先降低 `PacketOrder`/`Timer`/`RotationPlace` 检测面。
6. `OpenMaverDebug` 不注入网络层，C03/C05 只能记录本项目主动产生或按 tick
   节奏推断，不等同于抓包。
