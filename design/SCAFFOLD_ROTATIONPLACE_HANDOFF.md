# Scaffold / RotationPlace 接力文档

> 交接对象：下一个接手这个仓库的 AI。
> 目标：让 **Scaffold 在 GrimAC 上不再触发 `RotationPlace`**，同时不引入 `Timer` /
> `MultiPlace` / `AirLiquidBreak` / `AimDuplicateLook`。
> 本文只写「已确认的事实 + 可执行方案」，不含猜测性的调参玄学。
>
> **2026-11-17 更新**：Scaffold 已整体重写为 `../OpenZen` 的搭桥模块
> （见 `design/SCAFFOLD_HANDOFF.md`）。本文描述的是 **Grim 的判定链**，它依然成立，
> 新实现用 `pending → flush` + `holdPlaceAim` 同时满足：
> `ScaffoldFeature.place()` 只由 `flushPending()` 调用，发 C08 的那一 tick 的 aim
> 被冻结成刚校验过的角度。下文里出现的 `flushPendingPlace` / `pendingAction` 等
> 名字属于重写前的实现，读的时候按下表映射（行号也已经过期，以函数名为准）：
>
> | 旧名（本文/旧实现） | 新实现 |
> |---|---|
> | `flushPendingPlace` | `flushPending` |
> | `pendingAction` | `pendingDecision` |
> | `planPlacement` / `queuePlacement` | `tick()` 里生成 `Pending` |
> | `applyHoldAim` / `holdPlaceAim` | `aimForThisTick()` + `holdPlaceAim` |
> | `place()` | `place()`（不变，唯一 `onPlayerRightClick` 调用点） |

---

## 0. 一句话结论

> **2026-11-16 17:35 修正（实机验证后）**：本节的初版结论是错的，别按初版做。
> 真正判据见 §2 的 Grim 源码引用：**C08 由「它之后的那条飞包」的 rotation 判定**，
> 而不是「之前那条」。正确修法是 `holdPlaceAim`：**C08 发出去之后，本 tick 的
> 飞包必须携带同一个角度**（§11）。

初版（错误）结论，留作记录：以为 `RotationPlace` 判的是「上一 tick C03 里的 aim」，
于是把放置推迟一 tick、并清掉两处「用新角度立即发 C08」的路径（§4.1 / §4.2）。
§4.1 / §4.2 的改动本身是对的（不该拿没校验过的角度发包），但它**不足以**消 flag：
推迟一 tick 之后，Grim 用的仍然是**再下一条**飞包的角度。

现在的结论：

1. `RotationPlace`（post 分支）在 **C08 之后的下一条飞包**到达时才执行：
   `CheckManagerListener.handleQueuedPlaces` 对 1.8 客户端无条件
   `player.yaw = yaw; player.pitch = pitch;`（即这条飞包的值），
   位置用 `lastClaimedPosition`（C08 之前那条飞包的位置 = 发包时的当前位置）。
2. `didRayTraceHit` 的 pitch **只有这一个来源**；yaw 允许用 `lastYaw`，
   pitch 不允许 —— 1.8 没有 lastPitch 回退。
3. 因此唯一稳定的写法是：**用「本 tick 会发出去的角度」做 ray trace，发 C08，
   然后把这条飞包锁成同一个角度**，本 tick 不再规划别的目标。

## 1. 现场证据（2026-11-16 17:18 那局）

```
17:18:21 [Scaffold] 模块已禁用
17:18:21 [GrimAC] failed RotationPlace (vl:0.0): post-flying     ← 主判据分支，连续 8 次
...
17:18:22 [GrimAC] failed RotationPlace (vl:7.0): post-flying
17:18:23 [GrimAC] failed RotationPlace (vl:8.0): pre-flying      ← flagBuffer>0 后的连带
17:18:23 [GrimAC] failed RotationPlace (vl:9.0): pre-flying
...
17:18:23 [GrimAC] failed AirLiquidBreak (vl:0.0): block=air, type=START_DIGGING
17:18:23 [GrimAC] failed AimDuplicateLook (vl:0.0)
17:18:23 [GrimAC] failed Timer (vl:0.0)
```

读法：

| 行 | 归属 | 说明 |
| --- | --- | --- |
| `RotationPlace: post-flying` | **Scaffold（主要）** | C08 在 flying 包之后到达 —— 这是 1.8 的正常形态，Grim 用**当前** rotation 做 ray trace，失败即 flag |
| `RotationPlace: pre-flying` | Scaffold（连带） | `onBlockPlace` 只在 `flagBuffer > 0` 时才 flag，所以是上面失败后的二次记账 |
| `AirLiquidBreak: block=air, type=START_DIGGING` | **Velocity 的 ghost-block farm（已修）** | 旧 farm 会发 `C07 START_DESTROY_BLOCK` 打空气 |
| `AimDuplicateLook` | Scaffold（已修，见 §4.3） | 连续几 tick 保持同一个 aim，包里的 yaw/pitch 完全相同 |
| `Timer` | Velocity blink flush（见 §4.4） | 一次 flush 补发多条 C03 |

---

## 2. Grim 源码判据（`checks/impl/scaffolding/RotationPlace.java`）

来源：<https://github.com/GrimAnticheat/Grim/blob/2.0/common/src/main/java/ac/grim/grimac/checks/impl/scaffolding/RotationPlace.java>

```java
@Override
public void onPostFlyingBlockPlace(BlockPlace place) {   // C08 在 flying 之后
    if (place.material == StateTypes.SCAFFOLDING) return;
    if (!player.cameraEntity.isSelf()) return;
    if (player.inVehicle()) return;
    if (ignorePost) { ignorePost = false; return; }
    boolean hit = didRayTraceHit(place);
    if (!hit) {
        flagBuffer = 1;
        if (!ignorePost) flag(V.write(verbose()).bool(false));   // ← post-flying
    } else {
        flagBuffer = Math.max(0, flagBuffer - 0.1);
    }
    if (ignorePost) ignorePost = false;
}

@Override
public void onBlockPlace(final BlockPlace place) {       // C08 在 flying 之前
    ...
    if (flagBuffer > 0 && !didRayTraceHit(place)) {       // ← pre-flying 只在已经失败过时
        ignorePost = true;
        if (flag(...)) place.resync();
    }
}
```

`didRayTraceHit` 的关键细节（**必须按这个来设计修复**）：

```java
List<Vector3f> possibleLookDirs = Arrays.asList(
        new Vector3f(player.yaw,     player.pitch, 0),   // 当前 yaw + 当前 pitch
        new Vector3f(player.lastYaw, player.pitch, 0)    // 上一 tick yaw + 当前 pitch
);
// 1.9+ 才额外允许 (lastYaw, lastPitch)；1.8 没有这一项
```

由此得到三条硬约束：

1. **pitch 只有「当前值」一个来源** —— 没有 `lastPitch` 回退。所以 aim 的 pitch
   必须在**被检查的那个 flying 包**里就已经发出去了。
2. yaw 允许「上一 tick」，pitch 不允许 —— 两者不对称。任何「只改 yaw 不改 pitch」
   或「pitch 滞后一 tick」的写法都会命中失败。
3. 眼睛位置用 `player.getPossibleEyeHeights()` 并 `expand(movementThreshold)`，
   有一定的容差；所以 0.005° 量级的抖动完全安全（见 §4.3）。

另外 `onBlockPlace` 的 `place.resync()` 说明：一旦 `flagBuffer > 0`，Grim 会**取消**你的放置。

### 2.1【2026-11-16 17:35 补】post 分支用的到底是哪条包的 rotation

上面三条硬约束里，「pitch 只有当前值」是对的，但**「当前值」指的是下一条飞包**，
不是 C08 之前的那条。证据在 `CheckManagerListener`（Grim 2.0，与
`RotationPlace.java` 同一 commit）：

```java
// C08 到达时：先进队列，再立刻跑一次 pre 分支
player.checkManager.onBlockPlace(blockPlace);
...
} else { // Legit place
    player.placeUseItemPackets.add(new BlockPlaceSnapshot(packet, player.isSneaking));
}

// 下一条飞包到达时：handleFlying(...) →
CheckManagerListener.handleQueuedPlaces(player, hasLook, pitch, yaw, now);

public static void handleQueuedPlaces(GrimPlayer player, boolean hasLook,
        float pitch, float yaw, long now) {
    while ((snapshot = player.placeUseItemPackets.poll()) != null) {
        player.x = player.packetStateData.lastClaimedPosition.getX();   // ← C08 之前的位置
        player.y = ...; player.z = ...;

        // 1.8 客户端：无条件用**这一条**飞包的 rotation 覆盖
        if ((now - player.lastBlockPlaceUseItem < 15
                || player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) && hasLook) {
            player.yaw = yaw;
            player.pitch = pitch;
        }
        player.checkManager.onPostFlyingBlockPlace(blockPlace);          // ← 这里才判定
    }
}
```

读法（1.8 只有这一条路）：

| 项 | 值 | 说明 |
| --- | --- | --- |
| 判定时机 | **C08 之后的下一条飞包**到达时 | 我们的 C08 在 tick 开头发，紧接着就是本 tick 的 C03/C04/C05 |
| rotation | **那条飞包**的 yaw/pitch | 所以 aim 必须跟着**同一条 C08** 进本 tick 的飞包 |
| 位置 | `lastClaimedPosition` | C08 之前那条飞包的位置 = 发包时的当前位置（不是下一 tick 的预测位置） |
| 方块 | `place.position` | 被点击的方块（我们的 `supportPos`），`SimpleCollisionBox(position)` 纯几何 |

补充两点细节：

- 如果那条飞包没有 look（C03/C00），`hasLook == false`，Grim 就不覆盖 —— 用的是
  **上一条**飞包的 rotation。所以「多给一条 look」并不改变结论：本 tick 的包要么
  携带同一个角度，要么不携带。
- `possibleLookDirs` 里的 `(lastYaw, pitch)` 也共享这条飞包的 pitch，所以即使
  yaw 落后一 tick，pitch 也必须对。

---

## 3. 当前实现的时序（已核对代码）

```
ClientTickEvent.START  →  ScaffoldFeature.onTickStart
                           ├─ rotationDirty ? restoreCameraView()      （兜底恢复相机）
                           ├─ cameraYaw/Pitch = 玩家真实视角
                           └─ tick(mc)  → planPlacement / queuePlacement / flushPendingPlace
ClientTickEvent 中间   →  vanilla EntityPlayerSP.onUpdate → onLivingUpdate
                           ├─ movementInput.updatePlayerMoveState()
                           │    └─ ScaffoldMovementInput：写 aim 到 rotationYaw/Pitch
                           │       （pendingPlace != null 时置 sent = true）
                           └─ onUpdateWalkingPlayer：**C03 在这里发出**（带 aim）
ClientTickEvent.END    →  ScaffoldFeature.onTickEnd → restoreCameraView（只影响渲染）
```

也就是：

- `pending.sent = true` 的含义是「本 tick 的 C03 真的带上了这个 aim」。
- 下一 tick 的 `flushPendingPlace()` 用 `pending.yaw/pitch` 重算 ray 并发 C08 —— 此时
  服务端 rotation 恰好是上一 tick C03 的 aim，**理论上完全匹配**。

`flushPendingPlace()` 的现有实现（`mc/ScaffoldFeature.java`，约 1700 行起）：

```java
AimHit hit = rayAtBlock(mc, player, world, pending.supportPos, pending.facing,
        pending.yaw, pending.pitch);
if (hit == null && !grimStrict()) {          // ← 问题 1
    ... 用当前眼睛到 hitVec 重算 rots ...
    hit = rayAtBlock(..., rots[0], rots[1]);
    if (hit == null) { ... 兜底直接用 pending.hit ... }
}
...
place(mc, player, world, pending.supportPos, pending.facing, hit);   // ← C08 在这里发
```

---

## 4. 问题清单与修复方案（按优先级）

### 4.1 【核心】【已修】`hit == null` 时不得用「未发送过的角度」发 C08

**现象**：`post-flying` 连续 flag（vl 0→7）。

**原因**：`rayAtBlock(pending.yaw, pending.pitch)` 失败后，走「用当前眼睛到
`pending.hit.hitVec` 重算角度」的分支，然后**立刻** `place(...)`。这个新角度：

- 服务端没见过（本 tick 的 C03 早就用上一 tick 的 aim 发完了）；
- 而 `didRayTraceHit` 用的正是服务端那份 rotation，pitch 还只有当前值。

→ 必然不匹配。

**修复（推荐）**：把重算分支的「立即放置」改成「重新排队」：

```java
AimHit hit = rayAtBlock(mc, player, world, pending.supportPos, pending.facing,
        pending.yaw, pending.pitch);
if (hit == null) {
    // 这次的角度对不上：让新角度随本 tick 的 C03 发出去，下一 tick 再放。
    // 绝不能拿服务端没见过的角度直接发 C08（RotationPlace）。
    float[] rots = pending.hit == null || pending.hit.hitVec == null
            ? null
            : rotationsTo(eyeToHitVec(player, pending.hit.hitVec), pending.yaw, pending.pitch);
    if (rots == null) {
        return;                       // 目标格没了/超距：直接放弃这次放置
    }
    pendingPlace = pending;
    setAim(rots[0], rots[1]);         // 下一 tick 的 C03 会带上它
    return;
}
place(mc, player, world, pending.supportPos, pending.facing, hit);
```

配套要点：

- 重试要有上限，避免「永远对不上 → 反复排队」把 `pendingPlace` 钉死。
  建议在 `PendingPlace` 上加 `int retries`，超过 2 次就丢弃并
  `OpenMaverDebug.noticeOnce("scaffold.pending.retry", ...)`。
- 保留 `grimStrict()` 的语义：strict 下本来就只走第一条路径，修复后两者行为一致，
  `!grimStrict()` 不再是「放宽」而是「多一次重试机会」。

### 4.2 【核心】【已修】`placement.immediate` 的直接放置分支

`mc/ScaffoldFeature.java` 约 565 行：

```java
if (placement.immediate && !grimStrict()) {
    setAim(hit.yaw, hit.pitch);
    if (!placedThisTick()) {
        place(mc, player, world, placement.supportPos, placement.facing, hit);  // ← C08 先于 C03
    } else { queuePlacement(placement); }
}
```

`tick()` 跑在 `ClientTickEvent.START`，此时本 tick 的 C03 还没发，所以这条 C08 是
`pre-flying`。虽然 `onBlockPlace` 只在 `flagBuffer > 0` 时记账，但**一旦别处失败过，
这里就会跟着 flag**（日志 vl 8→11 就是这一串）。

**修复**：`immediate` 只保留「下一秒就要掉下去」的意图，不要再抢在 C03 之前发包。
把它也改成 `queuePlacement(placement)`；若担心掉下去，就在 `onTickEnd` 之前用
一条**只带 rotation 的 C05** 先把 aim 发给服务端，再放置 —— 但这样会多一个 flying 包，
`Timer` 风险上升，**建议先只做排队**，实测掉下去的损失远小于被 flag。

### 4.3 【已修·接力时补漏】`AimDuplicateLook`

`setAim()` 写进包的 `tickAimYaw/tickAimPitch` 现在带 ±0.005° 确定性微噪声
（`aimMicroNoise()`），而 `lastAimYaw/lastAimPitch`（给 `rayAtBlock` 用的基准）保留原值。
Grim 的判据是「相邻两包 rotation 完全相等」，微噪声即可破解，且远小于
`movementThreshold`/AABB 角度尺度。

**注意**：`rayAtBlock(pending.yaw, pending.pitch)` 用的是**未加噪声**的角度，
所以 C03 里的角度与 C08 的重算角度差 0.005°，在 Grim 的容差内，不要「修」掉这个差异。

**接力时发现的漏洞（已修）**：`ScaffoldMovementInput` 在 `pendingPlace != null` 时用
`tickAimYaw = pendingPlace.yaw` **覆盖**掉了 `setAim()` 写进去的噪声，所以「同一个
pending 跨多个 tick」时包里的 rotation 仍然完全相等 —— §4.3 的噪声在真正需要它的
那条路径上失效了。现在的做法：

1. 覆盖时改成 `pendingPlace.yaw + aimMicroNoise()`（pitch 同样），跨 tick 不再重样；
2. 同时把**真正写进 C03 的两个值**记进 `PendingPlace.sentYaw/sentPitch`（含噪声、
   含 `continuousYaw` 的 360° 平移），flush 用它们做 ray trace —— C08 的角度不再
   是「差 0.005°」，而是**与服务端持有的 rotation 逐位相同**。比容忍误差更稳。

### 4.4 【待定】`Timer`

`VelocityFeature.finishBlink()` 会一次性 `releaseHeld()` 多条 C03（当前
`uncertaintyTicks = 2`，即 2 条）。连续受击时会出现「短时间内多组批量 C03」，
Grim 的 `Timer` 可能记账。

**可选方案**（按风险从低到高）：

1. `uncertaintyTicks` 保持 2，观察是否只在连续受击时出现 —— 先确认归因；
2. flush 时把多条 C03 **合并成一条**（保留最后一条，前面几条丢弃）—— 位移会少，
   `Simulation` 会多一段 offset，需要一起评估；
3. flush 分散到后续 tick（每 tick 一条）—— 位置滞后更久，但包速率自然。

**结论未定，需要实测**：先只打开 `Velocity` 打一场，确认 `Timer` 是否单独出现。

---

## 5. 验证方法

### 5.1 包层（不启动游戏）

```bash
cmake --build build -j                 # 必须
tools/velocity-test/run.sh             # 63 + 16 项
tools/aim-test/run.sh                  # 15 项（含 AimModulo360 的 delta 判据）
```

这两套测试**不覆盖 Scaffold**。接力时按下面的思路补了第三套（见 §9.5）：

```bash
cmake --build build -j
tools/scaffold-test/run.sh            # 35 项：决策表 + C03→C08 时序模拟 + 结构断言
```

- 判据不变：「C08 之前一定存在一条带同角度 C03」，且「任何 C08 使用的角度都出现过
  在 C03 里」—— 这一条就能把 §4.1 / §4.2 的分支锁死；
- 但 `ScaffoldFeature` 需要真实 `World.rayTraceBlocks`，走 `EmbeddedChannel` 还要连带
  补 `PlayerControllerMP` 等 stub；所以把 flush 的决策抽成纯函数
  `ScaffoldFeature.pendingAction(boolean sent, boolean rayHit, int retries)`（包可见），
  测试直接反射断言这张表，再用它驱动一个最小的 pending 生命周期模拟。

### 5.2 游戏内

1. 只开 Scaffold（关 Velocity / AutoClicker / KillAura），搭 3~4 格桥；
2. 看 `logs/openmaver.log` 里 `OpenMaverDebug.place(...)` 的 `aim=` 与
   `serverView=` 是否一致（一致 = 这次放置用的是服务端已知角度）；
3. 打开 `debug.enabled=true` 会打出 `SCAFFOLD tick: ... aim=... serverView=...`，
   `aim` 与 `serverView` 不一致的那一 tick 就是 flag 的那一 tick。

判据：**`RotationPlace` 的 vl 不再增长**。`pre-flying` 只在 `flagBuffer > 0` 时出现，
所以只要 `post-flying` 为 0，`pre-flying` 也会消失。

---

## 6. 不要做的事

1. **不要为了「绕过」去伪造 `player.pitch` 的 last 值**：1.8 客户端根本不发 lastPitch，
   Grim 也没有这条回退，伪造只会制造 `AimDuplicateLook` / `AimModulo360`。
2. **不要把 C08 改成 pre-flying 形态**（抢在 C03 之前发）：`onBlockPlace` 分支在
   `flagBuffer > 0` 时会 `place.resync()` **取消放置**，等于自废。
3. **不要给 Scaffold 加 `C05PacketPlayerLook`**：`C05` 也是 flying 包，会增加
   `Timer` 的包计数，而且 `C05` 只更新 rotation、不更新位置，`RotationPlace` 依然按
   `player.yaw/pitch` 判 —— 收益为 0（代码里 `sendPlacementLook` 已有这条路径，
   `legit` profile 下是被主动关掉的，别打开）。
4. **不要动 `wrapDegrees` 的语义**：Aim / KillAura / Scaffold 的写包路径已经统一成
   「连续累加不取模」（`advanceYaw` / `continuousYaw`），这是过 `AimModulo360` 的前提。
   任何「顺手 wrap 一下」的改动都会让那个 check 重新开始刷 vl 0.975/次。

---

## 7. 相关文件与行号（本次交接时）

（行号是 **2026-11-16 17:30 接力后**的位置，会随改动漂移，以符号名为准。）

| 文件 | 位置 | 内容 |
| --- | --- | --- |
| `mc/ScaffoldFeature.java` | 167 | `MAX_PENDING_RETRIES = 2` |
| 同上 | 201 | `onTickStart`（写 aim 之前的恢复与相机采样） |
| 同上 | 257 | `onTickEnd` / `restoreCameraView` |
| 同上 | 400 | `tick()`：放置决策（一律 `queuePlacement`） |
| 同上 | 575 | §4.2 修复点：`immediate` 不再直接放置 |
| 同上 | 1445 | `place()` 的 `placedThisTick()` 守卫（一 tick 一个 C08） |
| 同上 | 1671 | `ScaffoldMovementInput` 记录 `sentYaw/sentPitch` 并置 `sent = true` |
| 同上 | 1682 | `queuePlacement` |
| 同上 | 1706 | `setAim`（含 `aimMicroNoise`） |
| 同上 | 1744 | `flushPendingPlace`（§4.1） |
| 同上 | 1812 | `reaimPending`（重算角度，只进 C03） |
| 同上 | 1847 | `pendingAction` 决策表（测试直接断言它） |
| 同上 | ~3190 | `PendingPlace`（`retries` / `sent` / `sentYaw` / `sentPitch`） |
| `mc/VelocityFeature.java` | ~604 | `farmUncertainty`（已从 ghost-block 改成真实视线 C08） |
| `mc/OpenMaverFeatures.java` | `advanceYaw` / `continuousYaw` | 连续 yaw，过 `AimModulo360` |
| `tools/scaffold-test/` | — | 放置时序回归测试（35 项，`run.sh`） |
| `design/VELOCITY_HANDOFF.md` | — | Velocity 侧的设计与坑 |
| `README.md` | 「Grim 已知检查」一节 | 每个 check 的判据速查 |

---

## 8. 一条给接手的经验

这个仓库踩过的 Grim flag，**没有一个是「参数不够狠」造成的**，全部是「时序 / 语义
不匹配」：

- `AimModulo360` → 我们每 tick 把 yaw 取模 360（原版是连续累加）；
- `RotationPlace` → 我们用了一个服务端还没收到的角度去发 C08；
- `AirLiquidBreak` → 我们挖了一个服务端认为是空气的格子；
- `AntiKB` → 我们让位移比预测少（服务端知道它发过 S12）。

所以修复的正确姿势永远是：**先读 Grim 那个 check 的源码，确认它拿哪个字段、在哪个
包序位置上判定，再让我们的发包顺序与那个字段的来源对齐**。调参只是最后一步。

---

## 9. 本次接力的改动（2026-11-16 17:30）

> 环境：`cmake --build build -j` 通过；`tools/velocity-test/run.sh`、
> `tools/aim-test/run.sh`（15 项）、`tools/scaffold-test/run.sh`（35 项）全绿。
> **尚未实机验证**，§10 是实机清单。

### 9.1 §4.1 `flushPendingPlace` 不再「重算角度后立即放置」

- 删掉 `hit == null && !grimStrict()` 那条「当前眼睛重算 → 立即 `place()`」的分支，
  以及它的 hitVec 兜底（那条兜底既换角度又换 hitVec，是 `RotationPlace` +
  `PositionPlace` 的双料来源）；
- 换成**重新排队**：`reaimPending()` 先按**预测眼睛位置**（`pos + motion`）重选支撑面 /
  重采样（预测位置才是这条 C03 发出时的真实眼睛），找不到再用「当前眼睛 →
  `pending.hitVec`」重算，两者都必须过一遍真实 `rayTrace`；结果只交给 `setAim()`
  写进本 tick 的 C03，`pendingPlace` 保留，下一 tick 才 `place()`；
- 重试上限 `MAX_PENDING_RETRIES = 2`（`PendingPlace.retries`）。到顶就丢弃并
  `noticeOnce("scaffold.pending.ray")`，防止一个打不到的面把 `pendingPlace` 钉死
  （那会表现成「站在原地不放方块」）。

### 9.2 §4.2 `placement.immediate` 一律排队

`tick()` 里现在只有 `queuePlacement(placement)`，`immediate` 退化成「优先级/日志」
标记（`describePlacement` 打 `urgent(queued)`）。理由就是 §4.2：`tick()` 跑在
`ClientTickEvent.START`，那里的 C08 是 `pre-flying`，`flagBuffer > 0` 时
`onBlockPlace` 会 `place.resync()` 取消放置 —— 又掉下去又记账。

### 9.3 角度账本：`sentYaw/sentPitch`

`PendingPlace` 多存一份「真正写进 C03 的角度」，flush 用它做 ray trace：覆盖掉 §4.3
的噪声漏洞；`continuousYaw` 可能把 yaw 平移 360°，`sentYaw` 记的是**平移后**的值，
所以 C08 与服务端 rotation 逐位一致；万一 miss，再退回未加噪声的基准值重试一次。

### 9.4 附带的守卫

- `place()` 开头 `placedThisTick()`：一 tick 只允许一个 C08（`MultiPlace`）；
- `aimHoldTicks` 的保持路径也加 `aimMicroNoise()`（原本那是第二处「连续 tick 完全相同
  rotation」的来源）。

### 9.5 测试：`tools/scaffold-test/ScaffoldPlaceOrderTest.java`

1. **决策表**：反射调用 `pendingAction`，断言「未 sent 永远不 PLACE」、
   「miss 只能 REQUEUE 或到顶 DROP」；
2. **时序模拟**：用这张表跑 pending 生命周期，断言 C08 的角度一定更早出现在 C03 里、
   重试有上限、一 tick 最多一个 C08、`dropC03` 时绝不放；
3. **结构断言**：把 `flushPendingPlace` / `tick()` / `place()` 的源码切出来，断言
   `flushPendingPlace` 只有一个 `place(...)` 调用点、没有 `!grimStrict()` 分支、
   `tick()` 里没有直接 `place(...)` —— 防止修复被「顺手改回去」。

---

## 10. 实机验证清单（下一步）

1. `bypass.enabled=true`、`debug.enabled=true`，只开 Scaffold 搭 3~4 格桥；
2. 看 `logs/openmaver.log` 里 `OpenMaverDebug.place(...)` 的 `aim=` 与
   `serverView=`：现在应当**逐位相等**（同一个 `sentYaw/sentPitch`）；
3. 预期 `RotationPlace` 的 vl 不再增长（`post-flying` 消失 → `pre-flying` 一并消失）；
4. 若 `scaffold.pending.requeue` 刷得很频繁、且「放得慢半拍」明显，把
   `MAX_PENDING_RETRIES` 调到 3 再测（只影响掉块，不影响 flag）；
5. `Timer`：按 §4.4 方案 1，先只开 `Velocity` 打一场确认归因，再决定 2/3 方案。

---

## 11. 第二版修复（2026-11-16 17:35，实机 flag 之后）

> 触发原因：17:31:58 那局用**已经带 §4.1 / §4.2 修复**的包（jar 时间 17:31:11、
> 启动 17:31:23）搭桥，仍然 `RotationPlace: post-flying` vl 0→8 连续 flag。
> 结论：§4.1 / §4.2 修的是「别拿没校验过的角度发包」，但漏了真正的时序。

### 11.1 根因

`flushPendingPlace` 用「上一 tick 发出去的角度」做 ray trace，然后 `place()`；
但 Grim 用的是**本 tick 飞包**的 rotation。本 tick 的飞包当时被 `queuePlacement`
换成了**下一个目标**的角度（或相机角度），于是每次放置都在用 A 的几何、B 的 rotation
判定 —— 100% 失败，vl 每次都 +1，与日志完全一致（2 次/秒、连续、Scaffold 一关就停）。

### 11.2 改法：`holdPlaceAim`

新增状态「本 tick 已经发过 C08」，三处配合：

1. `place()` 成功发包后记下 `holdPlaceAim = true; holdPlaceYaw/Pitch = aim.yaw/pitch`；
2. `tick()` 在 flush 之后立刻 `applyHoldAim()`（写 `tickAim*`、`aimReady`、
   `aimHoldTicks`），并跳过本 tick 的所有规划：`planPlacement`、`targetFacing`、
   keepY 三处都加了 `!holdPlaceAim` / `holdPlaceAim ||` 守卫；
3. 写包 hook（`ScaffoldMovementInput.updatePlayerMoveState`）优先认 hold，
   **不加噪声、逐位写入**；`setAim` 的 ±0.005° 噪声只在「规划下一目标」和
   「上一 tick 发出去的 aim」两条路径上起作用，保证相邻两条飞包不完全相同
   （`AimDuplicateLook`）。

### 11.3 校验角度 = 发包角度

`flushPendingPlace` 现在是一条候选阶梯，**每个候选都用自己去做 rayTrace**：

1. `sentYaw/sentPitch + aimMicroNoise()`（与上一 tick 的包差 0.005°，避开
   `AimDuplicateLook`，也让 Grim 的射线与上一 tick 几乎一致）；
2. `sentYaw/sentPitch`（噪声把射线挤出命中面时回退）；
3. `pending.yaw/pitch`（连续角被 `continuousYaw` 平移过 360° 时用基准值）。

命中谁就发谁，并把**同一个值**交给 hold —— Grim 拿到的 rotation 与本地校验逐位相同。

### 11.4 附带修正

- `reaimPending` 改成用**当前眼睛**（`pos`，不是 `pos + motion`）重新选面，并返回
  整条 `Placement`：以前它返回 `AimHit`，新算出的角度是针对另一个 support/face 的，
  但 `pending` 还留着旧的 support/face，下一 tick 必然再次失败（表现为「一直重试 →
  掉块」）。现在 support/face/hit 一起换。
- `resetState()` 清 `holdPlaceAim`；`tick()` 里 flush 之后的提前 return（例如刚放完
  最后一个方块）也先 `applyHoldAim()`，避免飞包退回相机角度。

### 11.5 代价

放置节奏从「每 tick 一块」变成「每 2 tick 一块」：放置那一 tick 的飞包被占用，
下一 tick 才能规划新目标。10 块/秒，远高于走路搭桥需求（约 4~6 块/秒）。

### 11.6 测试

`tools/scaffold-test/run.sh` 扩到 **43 项**，新增锁点：

- 模拟器改成按**包序**建模：每条 C08 之后必须有**同 tick、同角度**的飞包；
- `tick()` 在 hold 时不得规划新目标（`holdPlaceAim || pendingPlace != null`）；
- `applyHoldAim()` 逐位写入且不含 `aimMicroNoise`；
- 写包 hook 里 `holdPlaceAim` 分支必须排在 `pendingPlace` 之前。

### 11.7 还没做的

- ~~**`VelocityFeature.farmUncertainty` 的 C08** 走的是同一条判定链……照 §11.2
  给它加同样的「同 tick 飞包携带同一角度」。~~
  **2026-11-16 18:00：这条不用做了。** 17:49 那局证明 farm 的 C08 是三头挨打：
  - `FabricatedPlace`：cursor 用了**世界坐标**（协议要 `[0,1]` 相对坐标，日志
    `cursor=6.3125, -2.0, -4.6875` 反解出来就是 `62.0 / 246.3125 / 187.3125`）；
  - `RotationPlace` 的隐患也没消：C08 用的是相机角度，而本 tick 的飞包被
    Scaffold 的 hold-aim 占着；
  - 它与 Scaffold 的 C08 撞同一个 tick → `MultiPlace`。

  现在 GRIM 预设改走 `SPRINT SLAP`（Velocity 不再发任何 C08），farm 只在手动
  选 `UNCERTAINTY` 时才跑，并且 cursor 已改成相对坐标。

### 11.8 与 Velocity 的两个互斥闸门（2026-11-16 18:00 新增）

1. **`flushPendingPlace` 在 `VelocityFeature.isBlinking()` 时直接返回**，pending 原样
   留到下一 tick。原因：blink 窗口里位置包被扣住，Grim 的 `lastClaimedPosition`
   比客户端老 1~2 tick，而我们用「当前眼睛」算 hitVec/角度 —— 两边几何不是同一个
   位置，`PositionPlace / FarPlace / AirLiquidPlace` 就是从这来的（日志里它们和
   blink 的 `blink #N` 时间戳完全对齐）。
2. **`OpenMaverFeatures.claimPlacement()`**：本 tick 的 C08 名额。Scaffold 的
   `place()` 和 Velocity 的 `farmUncertainty` 都先领名额；tick 顺序是
   `ScaffoldFeature.onTickStart` → `VelocityFeature.onTickStart`，两边各放一个
   就是同 tick 两条 C08（`MultiPlace`）。

另外 `ScaffoldFeature.isAimingThisTick()` 是给**别人**用的信号：本 tick 的
`rotationYaw/Pitch` 会在 `updatePlayerMoveState()` 里被静默 aim 覆盖，任何
「按当前 rotation 做射线判定」的模块（Velocity 的 sprint-slap）必须在那一 tick
让位，否则它判定的角度和服务端最终收到的不是同一个。
- 一个可以继续压榨的方向：既然判定用的是**本 tick**的包，理论上可以「本 tick 发 C08
  + 本 tick 的包携带该角度」，即每 tick 一块。做法是把 `planPlacement` 的眼睛改成
  **当前**位置（而不是预测位置），直接把 `immediate` 变成「本 tick 放 + hold」。
  现在没做，是因为先把 flag 收干净、再看掉块率。
