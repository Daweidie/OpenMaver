# 接力：NoXZ 已移除挂起，待服务器实测验收

工作目录：`/home/zty/OpenMaver`。项目为 MC 1.8.9 Forge 客户端，C++ core + Java 集成，`mc/` 使用 MCP 名，构建时 reobf 到 SRG。

## 本轮已完成

根据旧版空中挂起导致 1.82 / 2.01 / 2.99 格偏移、Simulation / AntiKB / Hitboxes 及 FLUSH 包簇的证据，选择原交接的 **A 路线**：

- NoXZ 的 S12 在主线程收到后立即处理，地面/空中一律不取消，不做额外 tick 延迟或速度缩放。
- 后续入站事务、实体/动画包立即处理，出站移动/动作/回执不再缓存；NoXZ 不调用 holdMoves / flushMoves，不会在落地一次性回放。
- NoXZ 不再因受击额外强制跳跃；保留地面攻击及空中受击后的落地补刀。空中最多等待 12 tick，只保存攻击意图，不保存击退包；落地使用当前运动速度，绝不重新施加旧 S12。
- `Instant Attack` 保留最多 +3 次补刀（等待落地每 2 tick +1），分摊到正常 tick，与其他模块共享每 tick 的实体攻击名额。Timer 保持玩家原值。
- 新本地击退（含非正 Y）、校正、忽略环境、禁用、切模式与换世界会清理旧攻击意图。无目标落地不再强制关闭疾跑。
- README、详细交接与测试已同步。NoXZ 调试应出现 `KB in PASS AIR / LANDING WAIT`、`PASS READY`、`PASS NOT SPRINTING`、`PASS NO TARGET`；`LANDING WAIT` 仅指补刀，不代表挂起包。正常路径不再产生 `SUSPEND` / `OUT HOLD` / `FLUSH` / `KB release`，因此也消除了旧挂起期间逐包刷屏。

**Jump Reset / Mix 仍保留原延迟机制，未改为直通；不要把切到 Jump Reset 当作已验证的 Grim 兼容方案。**

## 验证与产物

```sh
cmake --build build -j2
tools/attackreduce-test/run.sh
git diff --check
```

已通过：`ZenAntiKBTest: 4385 passed, 0 failed`；两份 SRG jar 的 runtime-fields 均 PASS（各 11 处 MovementInput 字段引用）。构建成功。日志：

- `/tmp/openmaver-noxz-immediate-build.log`
- `/tmp/openmaver-noxz-immediate-tests.log`

产物：`build/openmaver-mod.jar`、`build/openmaver-mc.jar`。本地测试使用真实 Netty 4.0.23.Final EmbeddedChannel 与 Minecraft 替身，验证立即应用、逐 tick 出站、落地不重放旧速度、事务边界、连续击退、补刀上限及生命周期清理。不能据此宣称服务器零告警或真实移动间隔 40–60ms。

本轮修复前的工作树文件与两份 jar：`/tmp/openmaver-noxz-immediate-backup/`，保留仓库相对路径。历史实测数据与更早备份见 `design/ATTACKREDUCE_HANDOFF.md`。

## 游戏实例与后续实测

- 目标：`mc.loyisa.cn:25565`，Grim 2.x；历史实测玩家 Mincx1203。
- 实例：`~/.local/share/PrismLauncher/instances/1.8.9(1)/minecraft`。
- 安装目标：实例 `mods/openmaver-mod.jar`，不要照旧脚本写为 `OpenMaver.jar`。
- 本轮已安装新 jar 并校验 SHA256：`d893b1573fc140439c75188ca9f63057f48d68fa7c3fd66b11e143434903c45b`；`bypass.profile` 已由 aggressive 改回 balanced，其他配置保留。旧实例 jar 和配置备份在 `/tmp/openmaver-noxz-immediate-backup/prism/`。
- 模块参数：`config/openmaver.properties`；模块开关/热键：`config/default.jsonc`。
- 游戏日志：`logs/latest.log`（GrimAC）、`logs/openmaver.log`（Velocity DEBUG）。
- 当前配置：NoXZ、attackAmount=6、instantAttack=true、sprintStateCheck=true、debug=true、`bypass.profile=balanced`，避免 aggressive 的 Reach 3.30 干扰归因。
- 本轮尚无真实服务器测试回执。需在游戏中验证空中受击立即产生正常击退，落地有合法目标时仍补刀；站桩仍为 `PASS NOT SPRINTING`；检查没有挂起/回放事件。

服务端验收仍为：Simulation / AntiKB / Timer / GroundSpoof 无告警（原标准允许最多 1 条 <0.05 的瞬时 Simulation），移动包约 20/s、间隔 40–60ms，无原挂起造成的 2–5ms 包簇。实际网络与客户端帧率可能导致间隔波动，必须以新日志为准。

## 代码地图与注意事项

| 文件 | 职责 |
| --- | --- |
| `mc/antikb/NoXZMode.java` | 立即直通、受击输入、落地补刀意图及清理 |
| `mc/AttackReduceFeature.java` | 主线程入站调度、Session、共享适配队列、生命周期 |
| `mc/AttackReducePacketHook.java` | Netty 入站/出站截获与观测 |
| `mc/VelocityDebug.java` | DEBUG CHAT、移动包日志、间隔异常与每秒统计 |
| `tools/attackreduce-test/ZenAntiKBTest.java` | 三模式、直通、落地、输入、Timer、生命周期回归 |
| `tools/attackreduce-test/Protocol18Test.java` | 1.8 攻击/事务顺序、连续击退、适配队列与声音回归 |

- Netty 不访问玩家状态；入站仍经 `mc.addScheduledTask` 回主线程。“立即”指不再额外挂起，保留原版主线程调度。
- 新增 MC 成员调用必须检查 `tools/McpReobf.java` 映射，或使用 MCP/SRG 双名反射。
- 共享入站回放仍服务 Jump Reset / Mix；不要为了 NoXZ 直通删除其他模式的包序/生命周期保护。共享出站 FIFO 的测试改为显式启用 adapter hold，正常 NoXZ 不使用该队列。
- 配置版本仍是 20，本轮没有新增键。
- 工作树存在多项此前未提交修改；只做增量，不要 reset/checkout，也不要自动 commit。
