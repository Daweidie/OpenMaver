# KillAura（自动攻击 / 刀暴）实现说明

> 行为参考 `OpenMyau-Fix` 的 `KillAura`，按 OpenMaver 的反射架构重写为稳定核心版。

## 当前状态

- 模块类：`java/module/combat/KillAura.java`，配置路径 `combat.killaura`。
- 注册：`java/OpenMaver.registerModules()` 注册 `new KillAura()`。
- 默认配置（`config/default.jsonc`）：

```jsonc
"killaura": {
  "enabled": false,
  "key": ""
}
```

- MC 侧实现：`mc/OpenMaverFeatures.updateKillAura()`，在 `onTickStart`（vanilla C03 之前）
  执行。
- 与 AutoClicker 互斥：KillAura 启用时 AutoClicker 直接让位，避免同 tick 双 C02。

## 目标选择

- 遍历 `world.loadedEntityList`，只接受 `EntityLivingBase` 且存活、可碰撞。
- 过滤：
  - `killaura.players`：玩家（包括其他玩家的客户端实体）；
  - `killaura.mobs`：非玩家、非动物生物；
  - `killaura.animals`：`EntityAnimal` / `EntityVillager` / `EntityBat` / `EntitySquid`。
- 距离：眼睛到实体碰撞箱的最近距离，必须 ≤ 实际生效距离。
  - `legit/balanced` 上限 3.00（Grim 扩张箱）；
  - `aggressive` 上限 3.30；
  - 关闭 bypass 时使用配置值（3.0~6.0）。
- `killaura.throughWalls=false` 时额外要求 `canEntityBeSeen`。
- `killaura.fov` 限制目标与当前 yaw 的夹角。
- Single 模式保持当前目标直到失效；Switch 模式在 `switchDelay` 后优先换下一个目标。
- 排序：距离或血量。

## 攻击流程

1. `processPendingCriticalJump()`：先完成 Criticals JUMP 模式上一 tick 延后的攻击。
2. 选择目标。
3. 计算“转向之前”的视角误差；>15° 时本 tick 只转向、不攻击，等下一 tick 服务端
   收到 C03 后再结算，避免 RotationPlace / Aim 类误判。
4. 平滑转向：复用 `effectiveAimMaxStep()` 的 profile 单 tick 上限；
   `killaura.smoothing` 控制每 tick 比例。
5. 检查 `killauraNextAttackNanos`。
6. 检查目标 `hurtResistantTime`，仍在无敌帧内则等待。
7. 调用 `applyCriticals(..., CRITICALS_ATTACK_KILLAURA)`：
   - `JUMP` 模式会真实起跳并把攻击延迟到下落阶段；
   - 其他模式按 Criticals 配置补包。
8. `attackEntityFirst()`：先发 C02、再补 C0A（与 AutoClicker FULL CHARGE 一致）。
9. 攻击后恢复冲刺，更新 CPS 计时。

## CPS 与无敌帧

- `killaura.cpsMin` / `cpsMax` 决定目标 CPS（1~20，默认 8~12）。
- 实际间隔取 `max(1s / CPS, 11 tick)`，因为 1.8 服务端有 10 tick 受击无敌帧；
  低于 11 tick 的 C02 不会结算伤害。
- 目标 `entityReadyForDamage()` 为 false 时不发包。

## 设置项

- `killaura.range`：3.0~6.0，默认 3.0。
- `killaura.cpsMin` / `cpsMax`：1~20，默认 8 / 12。
- `killaura.fov`：30~360，默认 360。
- `killaura.throughWalls`：默认 true。
- `killaura.players` / `mobs` / `animals`：默认 true / false / false。
- `killaura.switch`：默认 false（Single）。
- `killaura.sort`：0 = Distance，1 = Health。
- `killaura.switchDelay`：0~1000ms，默认 150。
- `killaura.rotate` / `smoothing`：默认 true / 0.45。
- `killaura.requirePress`：默认 false。

## 未移植项

- AutoBlock（Vanilla / Hypixel / Legit / Fake / Hypixel Custom）
- Blink / BackTrack / Teams / BotCheck / InventoryCheck
- 多目标循环攻击与每 tick 多次攻击

这些逻辑依赖 Myau 的 RotationState、EventManager、PlayerStateManager 等基础设施，
当前版本优先保证稳定与可编译，不引入高频刷包。

## 相关文件

- `java/module/combat/KillAura.java`
- `java/OpenMaver.java`
- `config/default.jsonc`
- `mc/OpenMaverFeatures.java`
- `mc/GuiOpenMaverClickGui.java`
- `mc/OpenMaverHud.java`
- `README.md`
