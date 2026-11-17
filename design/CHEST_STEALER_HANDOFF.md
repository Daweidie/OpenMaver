# Chest Stealer 速度 / Grim Post 修复接力说明

> 仅用于你拥有或明确授权的服务器对测。本文结论基于 2026-11-17 本机实测日志
> （`/tmp/openmaver-chest.log` + `mc.loyisa.cn` 的 Grim 2.0 环境）与
> 1.8.9 客户端字节码（`bda.a(IIIILwn;)Lzx;` = `PlayerControllerMP.windowClick`）。

## 1. 现象

一次 10 格的箱子，服务端统计 **6527ms / 间隔 652ms / 10 件**；客户端日志：

```
[13:30:17.150] visible open accepted (GuiChest)
[13:30:17.382] packet hook attached (openmaver_chest)
[13:30:18.078] loot: moved 1 stack(s) ... next in 40ms     ← 开箱后 0.93s 才点第一格
[13:30:18.125] loot: moved 1 stack(s) ... next in 566ms
[13:30:19.057] loot: moved 1 stack(s) ... next in 40ms     ← 566ms 的节拍，实际隔了 932ms
[13:30:19.755] ...
[13:30:22.941] loot: moved 1 stack(s) ...
[13:30:23.599] visible close after moving 10 stack(s)
```

同时每 1~2 条搬运后面跟一条
`[GrimAC] Mincx1203 failed Post (vl:0.0..13.0): click window v1.8`。

## 2. 根因 A：点击包发在 tick END（Grim `Post`）

Grim 2.0 `checks/impl/misc/Post.java` 的判据（原文逻辑）：

```java
if (isTickPacket(type)) { post.clear(); sentFlying = true; return; }   // C03
if (isTransaction(type) && lastTransactionPacketWasValid) {
    if (sentFlying && !post.isEmpty()) flag(post.getFirst() + " v" + version);  // ← flag
    post.clear(); sentFlying = false; return;
}
if (CLICK_WINDOW.equals(type) && clientVersion < V_1_13) post.add(type);
```

也就是说：**C03 之后、下一个 C03 之前，只要出现「action 包 + 合法 transaction ack」就 flag**。

1.8 客户端的包序里，ack（`C0F`）由 `handleConfirmTransaction` 在 tick 开头的
scheduled-task 阶段发出；我们原来在 `ClientTickEvent.END` 调 `ChestFeature.onTick`，
于是每一 tick 都是：

```
C03(t) → [END] C0E → [tick t+1 scheduled] C0F → C03(t+1)
```

`C0E` 永远跨在 tick 边界上，任何 ack 都会落进 `post` 里 —— 10 次点击打出 13 次 flag，
和日志完全对得上。

**修复**：把 `ChestFeature.onTick` 移到 `ClientTickEvent.START`（`OpenMaverClientHandler`）。
包序变成 `C0F → C0E → C03`，与玩家自己在 `GuiContainer` 里点鼠标完全一致
（原版在 `runTick` 的鼠标输入阶段发 `C0E`/`C08`，再在 `updateEntities` 发 `C03`）。
`C0E` 到达时 `sentFlying == false`，Grim 只把它忽略，不会进 `post`。

## 3. 根因 B：慢（4 处）

1. **包钩子最迟 2 秒才挂上**（`maintainPacketHook` 未挂时 40 tick 重试）。日志里
   `16.150 send open packet → 17.382 packet hook attached`：这一整场的 S30 记账丢失，
   `contentSynced()` 永远 false，主循环只能等 `WINDOW_SYNC_FALLBACK_TICKS = 12 tick`
   （≈600ms）兜底 → 开箱后 0.93s 才点第一格。
   → 未挂上时 **每 2 tick** 重试；并且本地容器已有箱子侧物品时直接放行
   （`chestSideHasItems`），兜底收到 4 tick。
2. **`CLICK MS` 配成 40ms 打出爆发节拍**：`40ms 连点 5 格 → 500~1100ms 空档`。
   密度全挤在 burst 头部（最像机器），而且 tick 粒度 + `postGuard` 把节拍拖散。
   → 间隔下限硬编码 `MIN_CLICK_INTERVAL_MS = 80`，默认 100~160ms；停顿改成
   「每 4 格 6% 概率 200~380ms」；删掉 `postGuardNanos`（改成 START 发包后不再需要，
   它只会白白多加 60~140ms）。
3. **客户端掉帧时无法追赶**：关闭箱子日志里 `wait 2 tick(s)` 实际用了 658ms
   （≈200ms/tick），说明客户端 tick 曾严重掉帧。原来「一 tick 最多一格」，
   tick 一慢整箱就按 tick 数翻倍。
   → 用「距上次点击过了几个节拍」算配额，最多 `MAX_CLICKS_PER_TICK = 2` 格/tick。
4. **异常退避 20 tick（1 秒）**、**关箱 0.66s 后又重开同一个空箱**：
   → 退避收到 4 tick；`SWEEP_REVISIT_NANOS = 1.5s` 内不复扫刚搬空的坐标。

## 4. 改动位置

| 文件 | 改动 |
|---|---|
| `mc/OpenMaverClientHandler.java` | `ChestFeature.onTick` 移到 `ClientTickEvent.START`；END 分支删除 |
| `mc/ChestFeature.java` | `clicksDueNow()` 节拍/追赶；`MAX_CLICKS_PER_TICK`、`MIN_CLICK_INTERVAL_MS`；`chestSideHasItems()` 内容捷径 + 4 tick 兜底；`maintainPacketHook` 2 tick 重试；`SWEEP_REVISIT_NANOS`；`stealerErrorTicks` 4；`debug()` 复用日志流；`session done` 速度小结 |
| `config/openmaver-chest.properties`（实例内） | `CLICK MS 100/160`、`FIRST MS 80`、`PAUSE 6% 200~380` |
| `tools/chest-test/ChestStealerTest.java` | 新增 3 个用例（本地内容捷径 / CLICK MS 下限 / 不复扫空箱），共 100 项断言 |
| `README.md` | 同步节拍、START 发包、内容门、扫箱复扫、诊断日志说明 |

## 5. 预期数字

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 开箱 → 第一格 | 0.9~1.9s | ≈0.1~0.3s（只受开箱 RTT 影响） |
| 每格间隔 | 40ms 连点 + 500~1100ms 空档 | 100~160ms 稳定随机，掉帧时补到 2 格/tick |
| 10 格整箱 | 6.3~6.5s | ≈1.0~1.6s |
| `failed Post: click window` | 每 1~2 次点击一条 | 0（包序与真人一致） |
| 搬空后复扫同一箱子 | 0.66s 后重开空箱 | 1.5s 内不重开 |

回归测试里的速度基线：9 格满箱（机械模式、一格一 tick）≤16 tick；humanize 6 格
（200ms 间隔）≈1.0s、总时长 ≥900ms、相邻 ≥150ms。

## 6. 上服验收

1. `cmake --build build -j && tools/chest-test/run.sh` → `OK (100 项断言)`。
2. 开箱 10 格，看 `/tmp/openmaver-chest.log`：
   - `open→first` 应在 300ms 内（超过说明开箱往返 / 内容门还在拖）；
   - `first→last` ≈ 格子数 × 100~160ms；
   - 不应再出现 `content gate: ... skip S30 wait` 连续刷屏（偶尔一条正常）。
3. `/grim verbose` 只留 `Mincx1203` 的 join/leave 之类，不应再有
   `Post ... click window v1.8`。
4. 服务端自己的开箱统计（`CCBlueX ... 耗时/间隔/数量`）间隔应从 ~652ms 降到
   ~100~200ms；如果服务器另有「每格 ≥250ms」的私有阈值，把 `CLICK MS/CLICK MS+`
   调到 250/300 再测一轮。

## 7. 剩余风险

1. **开箱往返本身就是 RTT**：650ms 的 ping 下「扫到箱子 → 界面真开」改变不了；
   本修复只消掉点击侧的额外等待。
2. **客户端 tick 掉帧**（日志里 200ms/tick）只能靠「一 tick 最多 2 格」缓解；
   如果实测 tick 更慢，可把 `MAX_CLICKS_PER_TICK` 提到 3~4（包序仍安全，
   但一 tick 多包会变成可观测的 burst，需要对着服务端统计再调）。
3. **服务端自定义点击限速**：Grim `Post` 之外，若服务器另有 inventory-click 速率
   限制（`BadPackets`/插件），只能靠降低 `CLICK MS` 频率解决，不是包序问题。
4. **静默模式**（`SHOW CHEST GUI=off`）依旧是最容易被盯上的形态：界面被收起后
   仍在点窗口，只建议在必须隐藏界面时开。
5. 两条 C0E 落在同一 tick 时，第二个包的 `transactionID` 仍由原版
   `Container.getNextTransactionID()` 递增，服务端按序 ack，无额外风险。
