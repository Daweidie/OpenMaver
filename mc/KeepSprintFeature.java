import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.MovementInput;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Properties;
import java.util.UUID;

/**
 * KeepSprint：默认在输入更新后、移动物理前对齐移动方向。
 * 输入改成 forward=1 / strafe=0，由原版负责疾跑速度、跳跃和 C03。
 * ClientTick END（C03 之后）恢复相机；ROTATE 关闭时保留旧 OMNI 补偿。
 * 详见 design/KEEPSPRINT_HANDOFF.md。
 */
public final class KeepSprintFeature {

    // ==================================================================
    // 常量
    // ==================================================================

    /** 原版 “Sprinting speed boost”：operation 2（乘算），amount +0.3。 */
    static final double BOOST_AMOUNT = 0.30000001192092896D;
    static final int BOOST_OPERATION = 2;

    /**
     * 独立 UUID。不能用原版冲刺的
     * {@code 662A6B8D-DA3E-4C1C-8813-96EA6097278D}：原版每次
     * {@code setSprinting(false)} 都会按 UUID 把它删掉，我们就保不住侧移速度了。
     */
    static final UUID BOOST_UUID = UUID.fromString("6a0f3d9e-6d0e-4a4e-9f4a-0d8f7a4b1c21");

    // ==================================================================
    // 设置（ClickGUI / openmaver.properties 共用）
    // ==================================================================

    /** 玩家自己在潜行时不接管。 */
    private static boolean pauseSneak = true;
    /** 新旧配置缺省都启用移动朝向对齐。 */
    private static boolean rotate = true;
    /** 使用物品（举盾 / 吃东西 / 拉弓）时不接管。 */
    private static boolean pauseItem = true;
    /** 本地补 +30%；关掉后只剩纯发包（侧移按走路速度、服务端保持冲刺）。 */
    private static boolean speedBoost = true;
    /** 攻击后补发 C0B START_SPRINTING，恢复服务端冲刺状态。 */
    private static boolean attackResend = true;

    // ==================================================================
    // 运行状态
    // ==================================================================

    /** 本 tick 内本地玩家发生过攻击（AttackEntityEvent 记号）。 */
    private static boolean attackPending;
    /** 上一 tick 是否处于接管状态（HUD / 调试用）。 */
    private static boolean active;
    /** OFF / IDLE / ROTATE / YIELD / NO HOOK / RUN / PACKET。 */
    private static String status = "OFF";
    /** 反射失败只提示一次。 */
    private static boolean warned;
    private static Object rotationPlayer;
    private static float cameraYaw, previousCameraYaw, writtenYaw;
    private static boolean rotationDirty, rotatedThisTick;
    private static Object armedPlayer;
    private static long armedNanos;

    /** 属性 / modifier / C0B 的反射缓存。 */
    private static Object boostModifier;
    private static Object movementSpeedAttribute;
    private static boolean attributeLookupFailed;
    private static Constructor<?> sprintPacketConstructor;
    private static Object startSprintingAction;
    private static boolean packetLookupFailed;

    private KeepSprintFeature() {
    }

    // ==================================================================
    // 设置读写
    // ==================================================================

    public static void load(Properties values) {
        // KeepSprint now always uses its silent movement-yaw path. Ignore legacy toggles.
        rotate = true;
        pauseSneak = readBoolean(values, "keepSprint.pauseSneak", true);
        pauseItem = readBoolean(values, "keepSprint.pauseItem", true);
        speedBoost = readBoolean(values, "keepSprint.speed", true);
        attackResend = readBoolean(values, "keepSprint.attackResend", true);
    }

    /** 与其它 Feature 的 save() 拼接，末尾带换行。 */
    public static String save() {
        return "keepSprint.rotate=" + rotate
                + "\nkeepSprint.pauseSneak=" + pauseSneak
                + "\nkeepSprint.pauseItem=" + pauseItem
                + "\nkeepSprint.speed=" + speedBoost
                + "\nkeepSprint.attackResend=" + attackResend + "\n";
    }

    /** 关闭 / 切世界 / 批量停用时清掉每 tick 记号。 */
    public static void reset() {
        restoreCameraView();
        setOwnBoost(armedPlayer, false);
        armedPlayer = null;
        rotatedThisTick = false;
        attackPending = false;
        active = false;
        status = "OFF";
    }

    public static boolean isRotate() { return rotate; }
    public static void setRotate(boolean value) {
        // Retain the legacy API while keeping the current silent behavior fixed.
        rotate = true;
    }

    public static boolean isPauseSneak() { return pauseSneak; }
    public static void setPauseSneak(boolean value) {
        pauseSneak = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isPauseItem() { return pauseItem; }
    public static void setPauseItem(boolean value) {
        pauseItem = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isSpeedBoost() { return speedBoost; }
    public static void setSpeedBoost(boolean value) {
        speedBoost = value;
        OpenMaverFeatures.markDirty();
    }

    public static boolean isAttackResend() { return attackResend; }
    public static void setAttackResend(boolean value) {
        attackResend = value;
        OpenMaverFeatures.markDirty();
    }

    public static String getStatus() { return status; }

    /** ClickGUI / HUD 用的一行摘要。 */
    public static String getSummary() {
        Module module = ModuleManager.getModule("KeepSprint");
        if (module == null || !module.isEnabled()) return "OFF";
        if (!active) return status;
        if (rotate) return "SILENT  ·  VANILLA";
        return "OMNI  ·  " + (speedBoost ? "1.3x" : "PKT");
    }

    // ==================================================================
    // 事件入口
    // ==================================================================

    /**
     * Forge {@code AttackEntityEvent}：本地玩家攻击时记一个记号。
     *
     * <p>攻击发生在 {@code attackTargetEntityWithCurrentItem()} 开头，早于原版把
     * 冲刺置 false；这里不改任何状态，只等同一个 tick 的 END 补包。
     */
    public static void onAttack(Object player) {
        if (player == null) return;
        Module module = ModuleManager.getModule("KeepSprint");
        if (module == null || !module.isEnabled()) return;
        attackPending = true;
    }

    /**
     * Forge {@code PlayerTickEvent.START}：玩家 tick / 移动物理之前。
     *
     * <p>ROTATE 准备输入回调、摘掉旧补偿；OMNI 按物理键预判本 tick 的速度补偿。
     */
    public static void onPlayerTickStart(Minecraft mc, Object player) {
        // END 缺失、切世界或配置切换时也归还上一份相机状态。
        restoreCameraView();
        if (armedPlayer != player) setOwnBoost(armedPlayer, false);
        armedPlayer = null;
        rotatedThisTick = false;
        if (rotate) {
            setOwnBoost(player, false);
            active = false;
            status = enabled() ? "IDLE" : "OFF";
            if (!enabled() || mc == null || player == null) return;
            if (rotationBusy()) { status = "YIELD"; return; }
            if (OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null) return;
            if (!ensureMovementHook(mc, player)) { status = "NO HOOK"; return; }
            armedPlayer = player;
            armedNanos = System.nanoTime();
            return;
        }
        Module module = ModuleManager.getModule("KeepSprint");
        if (module == null || !module.isEnabled()) {
            setOwnBoost(player, false);
            return;
        }
        if (mc == null || player == null) {
            return;
        }

        // 用键位预判本 tick 的输入；MovementInput 里的值还是上一 tick 的。
        double[] predicted = predictedInput(mc);
        double forward = predicted[0];
        double strafe = predicted[1];
        boolean sneaking = sneakPressed(mc);
        boolean usingItem = Boolean.TRUE.equals(
                OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null));
        if (usingItem) {
            // EntityPlayerSP.onLivingUpdate 在使用物品时把输入压到 0.2。
            forward *= 0.2D;
            strafe *= 0.2D;
        }
        if (sneaking) {
            // MovementInputFromOptions 在潜行时把输入压到 0.3。
            forward *= 0.3D;
            strafe *= 0.3D;
        }

        boolean riding = Boolean.TRUE.equals(
                OpenMaverFeatures.call(player, "isRiding", "func_70115_ae", null));
        boolean flying = isFlying(player);
        boolean alive = OpenMaverFeatures.alive(player);
        boolean collided = collidedHorizontally(player);
        boolean foodOk = foodAllowsSprint(player);

        Plan plan = plan(true, forward, strafe, sneaking, pauseSneak, usingItem, pauseItem,
                riding, flying, alive, collided, foodOk, speedBoost, false, attackResend);
        setOwnBoost(player, plan.active && plan.ownBoost);
    }

    /**
     * Forge {@code PlayerTickEvent.END}（必须只对本地玩家调用）。
     *
     * <p>时序：{@code EntityPlayer.onUpdate()} 末尾 → 本方法 → {@code EntityPlayerSP.onUpdate()}
     * 调用 {@code onUpdateWalkingPlayer()} 发 C0B / C03。
     */
    public static void onPlayerTickEnd(Object player) {
        if (rotate) {
            setOwnBoost(player, false);
            active = enabled() && rotatedThisTick && rotationPlayer == player;
            if (active && attackPending && attackResend && canSprint(player)
                    && Boolean.TRUE.equals(OpenMaverFeatures.call(player,
                            "isSprinting", "func_70051_ag", null))) {
                sendStartSprinting(player);
            }
            attackPending = false;
            if (!enabled()) status = "OFF";
            else if (active) status = "SILENT";
            // C03 尚未发送，此处保留 yaw，ClientTick END 才恢复。
            return;
        }
        Module module = ModuleManager.getModule("KeepSprint");
        if (module == null || !module.isEnabled()) {
            attackPending = false;
            active = false;
            status = "OFF";
            setOwnBoost(player, false);
            return;
        }
        if (player == null) {
            attackPending = false;
            active = false;
            status = "IDLE";
            return;
        }

        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input == null) {
            attackPending = false;
            active = false;
            status = "IDLE";
            setOwnBoost(player, false);
            return;
        }

        double forward = OpenMaverFeatures.number(input, "moveForward", "field_78900_b");
        double strafe = OpenMaverFeatures.number(input, "moveStrafe", "field_78902_a");
        boolean sneaking = Boolean.TRUE.equals(
                OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class));
        boolean usingItem = Boolean.TRUE.equals(
                OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null));
        boolean riding = Boolean.TRUE.equals(
                OpenMaverFeatures.call(player, "isRiding", "func_70115_ae", null));
        boolean flying = isFlying(player);
        boolean alive = OpenMaverFeatures.alive(player);
        boolean collided = collidedHorizontally(player);
        boolean foodOk = foodAllowsSprint(player);
        boolean sprinting = Boolean.TRUE.equals(
                OpenMaverFeatures.call(player, "isSprinting", "func_70051_ag", null));

        Plan plan = plan(true, forward, strafe, sneaking, pauseSneak, usingItem, pauseItem,
                riding, flying, alive, collided, foodOk, speedBoost, attackPending, attackResend);
        attackPending = false;

        if (!plan.active) {
            active = false;
            status = "IDLE";
            setOwnBoost(player, false);
            return;
        }

        active = true;
        // 本 tick 的移动已经结束；这里校正的是下一 tick 的状态。
        setOwnBoost(player, plan.ownBoost);
        if (plan.ownBoost) {
            boostJumpMovementFactor(player);
        }
        if (!sprinting) {
            OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b", null, Boolean.TRUE);
        }
        if (plan.resendSprint) {
            sendStartSprinting(player);
        }
        status = plan.ownBoost ? "RUN" : "PACKET";
    }

    // ==================================================================
    // 移动朝向：输入 hook -> 原版 sprint / physics / C03 -> ClientTick END
    // ==================================================================

    private static boolean enabled() {
        Module module = ModuleManager.getModule("KeepSprint");
        return module != null && module.isEnabled();
    }

    /** 这些模块拥有自己的 aim / 输入，不与其争用 yaw 或强制 sprint。 */
    private static boolean rotationBusy() {
        String[] owners = {"Scaffold", "Scaffold2", "Telly", "GodBridge", "KillAura"};
        for (String name : owners) {
            Module module = ModuleManager.getModule(name);
            if (module != null && module.isEnabled()) return true;
        }
        return false;
    }

    private static boolean canSprint(Object player) {
        return player != null && OpenMaverFeatures.alive(player) && !isFlying(player)
                && !Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isRiding", "func_70115_ae", null))
                && !Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null))
                // blindness 的 1.8.9 potion ID 为 15；原版不在失明时启动冲刺。
                && !Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isPotionActive", "func_82165_m", null, 15))
                && !collidedHorizontally(player) && foodAllowsSprint(player);
    }

    /** Camera-relative input becomes a single vanilla forward key at movement yaw. */
    static float movementYaw(float viewYaw, double forward, double strafe) {
        return viewYaw - (float) Math.toDegrees(Math.atan2(strafe, forward));
    }

    static void afterMovementInput(MovementInput input) {
        if (!rotate || !enabled() || armedPlayer == null || rotatedThisTick
                || rotationBusy() || System.nanoTime() - armedNanos > 500_000_000L) return;
        Minecraft mc = Minecraft.getMinecraft();
        Object player = armedPlayer;
        if (mc == null || OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null) != player
                || OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null) != input
                || OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null) return;
        // Velocity 回调在本方法之前完成；仅在它本次确实写过输入/朝向时让位。
        if (AttackReduceFeature.ownsMovementInput(player, input)) {
            status = "YIELD VELOCITY";
            return;
        }
        // ROTATE 遵守原版的缩放/停止条件，不用自定义 modifier 强行续跑。
        if (input.sneak || !canSprint(player)) return;
        float forward = input.moveForward;
        float strafe = input.moveStrafe;
        if (!Float.isFinite(forward) || !Float.isFinite(strafe)
                || Math.max(Math.abs(forward), Math.abs(strafe)) < 0.8F) return;
        float viewYaw = (float) OpenMaverFeatures.number(player, "rotationYaw", "field_70177_z");
        if (!Float.isFinite(viewYaw)) return;
        float aimYaw = movementYaw(viewYaw, forward, strafe);
        previousCameraYaw = (float) OpenMaverFeatures.number(player, "prevRotationYaw", "field_70126_B");
        cameraYaw = viewYaw;
        writtenYaw = aimYaw;
        rotationPlayer = player;
        OpenMaverFeatures.setNumber(player, "rotationYaw", "field_70177_z", aimYaw);
        rotationDirty = true;
        rotatedThisTick = true;
        input.moveForward = 1.0F;
        input.moveStrafe = 0.0F;
        // 在原版 sprint 检查 / jump / moveFlying 之前启动，首 tick 不借 END 补速度。
        OpenMaverFeatures.callVoid(player, "setSprinting", "func_70031_b", null, Boolean.TRUE);
    }

    public static void onTickEnd(Minecraft mc) {
        restoreCameraView();
        armedPlayer = null;
        if (!enabled()) { active = false; status = "OFF"; attackPending = false; }
    }

    private static void restoreCameraView() {
        if (!rotationDirty || rotationPlayer == null) return;
        Object player = rotationPlayer;
        // 保留 hook 之后发生的相机增量，避免丢掉后续相对视角更新。
        float delta = OpenMaverFeatures.wrapDegrees((float) OpenMaverFeatures.number(
                player, "rotationYaw", "field_70177_z") - writtenYaw);
        float viewYaw = cameraYaw + delta;
        OpenMaverFeatures.setNumber(player, "rotationYaw", "field_70177_z", viewYaw);
        OpenMaverFeatures.setNumber(player, "prevRotationYaw", "field_70126_B", previousCameraYaw + delta);
        OpenMaverFeatures.setNumber(player, "renderArmYaw", "field_71154_f", viewYaw);
        OpenMaverFeatures.setNumber(player, "prevRenderArmYaw", "field_71163_h", viewYaw);
        OpenMaverFeatures.setNumber(player, "renderYawOffset", "field_70761_aq", viewYaw);
        OpenMaverFeatures.setNumber(player, "rotationYawHead", "field_70759_as", viewYaw);
        rotationDirty = false;
        rotationPlayer = null;
    }

    private static boolean ensureMovementHook(Minecraft mc, Object player) {
        Object input = OpenMaverFeatures.read(player, "movementInput", "field_71158_b", null);
        if (input instanceof ScaffoldMovementInput) return true;
        // 尊重其它 mod 的自定义输入；失败时不回退到 OMNI 加速。
        if (input == null || !"net.minecraft.util.MovementInputFromOptions".equals(input.getClass().getName()))
            return false;
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        if (!(settings instanceof GameSettings)) return false;
        ScaffoldMovementInput hook = new ScaffoldMovementInput((GameSettings) settings);
        hook.moveForward = (float) OpenMaverFeatures.number(input, "moveForward", "field_78900_b");
        hook.moveStrafe = (float) OpenMaverFeatures.number(input, "moveStrafe", "field_78902_a");
        hook.jump = Boolean.TRUE.equals(OpenMaverFeatures.read(input, "jump", "field_78901_c", Boolean.class));
        hook.sneak = Boolean.TRUE.equals(OpenMaverFeatures.read(input, "sneak", "field_78899_d", Boolean.class));
        return OpenMaverFeatures.writeObjectField(player, "movementInput", "field_71158_b", hook);
    }

    // ==================================================================
    // 决策（纯函数，便于回归测试）
    // ==================================================================

    /** 单 tick 的接管计划。 */
    static final class Plan {
        final boolean active;
        /** 是否需要挂自己的 +30% 速度 modifier。 */
        final boolean ownBoost;
        /** 是否需要补 C0B START_SPRINTING。 */
        final boolean resendSprint;

        Plan(boolean active, boolean ownBoost, boolean resendSprint) {
            this.active = active;
            this.ownBoost = ownBoost;
            this.resendSprint = resendSprint;
        }
    }

    /**
     * 决策表。
     *
     * @param enabled      模块开关
     * @param forward      本 tick / 预判的 moveForward
     * @param strafe       本 tick / 预判的 moveStrafe
     * @param sneaking     潜行输入
     * @param pauseSneak   潜行时让位
     * @param usingItem    正在使用物品
     * @param pauseItem    使用物品时让位
     * @param riding       骑乘中
     * @param flying       创造飞行 / 旁观飞行
     * @param alive        玩家存活
     * @param collided     水平撞墙
     * @param foodOk       饱食度 &gt; 6 或允许飞行（原版冲刺条件 flag3）
     * @param speedBoost   是否补本地 +30%
     * @param attacked     本 tick 是否攻击过
     * @param attackResend 攻击后是否补 C0B START_SPRINTING
     */
    static Plan plan(boolean enabled, double forward, double strafe,
            boolean sneaking, boolean pauseSneak, boolean usingItem, boolean pauseItem,
            boolean riding, boolean flying, boolean alive, boolean collided, boolean foodOk,
            boolean speedBoost, boolean attacked, boolean attackResend) {
        boolean moving = forward != 0.0D || strafe != 0.0D;
        boolean active = enabled && alive && moving && !riding && !flying
                && !(pauseSneak && sneaking)
                && !(pauseItem && usingItem);
        if (!active) {
            return new Plan(false, false, false);
        }
        boolean vanillaHolds = vanillaHoldsSprint(forward, collided, foodOk);
        return new Plan(true, speedBoost && !vanillaHolds, attacked && attackResend);
    }

    /**
     * 原版的 +30% 会不会延续到下一 tick 的移动：
     * 只有“前进（moveForward ≥ 0.8）+ 没撞墙 + flag3”时，
     * {@code EntityPlayerSP.onLivingUpdate()} 才不取消冲刺。
     */
    static boolean vanillaHoldsSprint(double forward, boolean collided, boolean foodOk) {
        return forward >= 0.8D && !collided && foodOk;
    }

    /** 本地速度补偿就是原版 sprint modifier 的 +30%（operation 2 乘算）。 */
    static double boostedSpeed(double base) {
        return base * (1.0D + BOOST_AMOUNT);
    }

    // ==================================================================
    // 应用：速度
    // ==================================================================

    /**
     * 挂 / 摘自己的 +30% modifier。
     *
     * <p>start 阶段与 end 阶段都会调用；幂等，只在状态变化时真正写属性。
     */
    private static void setOwnBoost(Object player, boolean wanted) {
        if (player == null) return;
        Object instance = movementSpeedInstance(player);
        if (instance == null) return;
        Object current = OpenMaverFeatures.call(instance, "getModifier", "func_111127_a",
                null, BOOST_UUID);
        if (wanted) {
            if (current != null) return;
            Object modifier = boostModifier();
            if (modifier == null) return;
            OpenMaverFeatures.callVoid(instance, "applyModifier", "func_111121_a",
                    null, modifier);
        } else {
            if (current == null) return;
            OpenMaverFeatures.callVoid(instance, "removeModifier", "func_111124_b",
                    null, current);
        }
    }

    private static Object movementSpeedInstance(Object player) {
        if (attributeLookupFailed) return null;
        Object attribute = movementSpeedAttribute();
        if (attribute == null) return null;
        return OpenMaverFeatures.call(player, "getEntityAttribute", "func_110148_a",
                null, attribute);
    }

    private static Object movementSpeedAttribute() {
        if (movementSpeedAttribute != null || attributeLookupFailed) {
            return movementSpeedAttribute;
        }
        try {
            Class<?> shared = Class.forName("net.minecraft.entity.SharedMonsterAttributes");
            movementSpeedAttribute = staticField(shared, "field_111263_d", "movementSpeed");
            if (movementSpeedAttribute == null) {
                // 映射兜底：按 unlocalized name 找 “generic.movementSpeed”。
                for (Field field : shared.getFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    Object value = field.get(null);
                    if (value == null) continue;
                    Object name = OpenMaverFeatures.call(value,
                            "getAttributeUnlocalizedName", "func_111108_a", null);
                    if ("generic.movementSpeed".equals(name)) {
                        movementSpeedAttribute = value;
                        break;
                    }
                }
            }
            if (movementSpeedAttribute == null) {
                attributeLookupFailed = true;
            }
        } catch (Throwable error) {
            attributeLookupFailed = true;
            warn(error);
        }
        return movementSpeedAttribute;
    }

    private static Object staticField(Class<?> type, String... names) {
        for (int i = 0; i < names.length; i++) {
            try {
                return type.getField(names[i]).get(null);
            } catch (ReflectiveOperationException ignored) {
                // Try the next mapping.
            }
        }
        return null;
    }

    private static Object boostModifier() {
        if (boostModifier != null || attributeLookupFailed) return boostModifier;
        try {
            Class<?> type = Class.forName("net.minecraft.entity.ai.attributes.AttributeModifier");
            boostModifier = type.getConstructor(UUID.class, String.class, double.class, int.class)
                    .newInstance(BOOST_UUID, "OpenMaver KeepSprint", BOOST_AMOUNT, BOOST_OPERATION);
        } catch (Throwable error) {
            attributeLookupFailed = true;
            warn(error);
        }
        return boostModifier;
    }

    /**
     * 空中速度：原版冲刺在 {@code EntityPlayer.onLivingUpdate()} 里执行
     * {@code jumpMovementFactor += speedInAir * 0.3}。侧移 / 后退时冲刺在移动前
     * 已被取消，这个字段是基础值，这里补同样的 30%。
     */
    private static void boostJumpMovementFactor(Object player) {
        float jump = (float) OpenMaverFeatures.number(player,
                "jumpMovementFactor", "field_70747_aH");
        if (jump > 0.0F) {
            OpenMaverFeatures.setNumber(player, "jumpMovementFactor", "field_70747_aH",
                    jump * (1.0F + (float) BOOST_AMOUNT));
        }
    }

    // ==================================================================
    // 应用：状态 / 包
    // ==================================================================

    private static boolean isFlying(Object player) {
        Object capabilities = OpenMaverFeatures.read(player, "capabilities", "field_71075_bZ", null);
        if (capabilities == null) return false;
        return Boolean.TRUE.equals(OpenMaverFeatures.read(capabilities,
                "isFlying", "field_75100_b", Boolean.class));
    }

    private static boolean collidedHorizontally(Object player) {
        return Boolean.TRUE.equals(OpenMaverFeatures.read(player,
                "isCollidedHorizontally", "field_70123_F", Boolean.class));
    }

    /**
     * 原版 {@code flag3 = foodLevel > 6 || capabilities.allowFlying}。
     *
     * <p>读不到时按 true 处理：宁可少补一次速度，也不要在真正冲刺时叠出双份 +30%。
     */
    private static boolean foodAllowsSprint(Object player) {
        Object capabilities = OpenMaverFeatures.read(player, "capabilities", "field_71075_bZ", null);
        if (Boolean.TRUE.equals(OpenMaverFeatures.read(capabilities,
                "allowFlying", "field_75101_c", Boolean.class))) {
            return true;
        }
        Object stats = OpenMaverFeatures.call(player, "getFoodStats", "func_71024_bL", null);
        Object level = OpenMaverFeatures.call(stats, "getFoodLevel", "func_75116_a", null);
        if (level instanceof Number) {
            return ((Number) level).intValue() > 6;
        }
        return true;
    }

    /**
     * 攻击后补一条 C0B START_SPRINTING。
     *
     * <p>攻击包（C02）先入队，本包在 PlayerTickEvent.END 入队，C03 最后入队，
     * 服务端看到的是 C02 → START_SPRINTING → C03，和玩家自己重新按下冲刺一致。
     */
    private static void sendStartSprinting(Object player) {
        resolvePacket();
        if (sprintPacketConstructor == null || startSprintingAction == null) return;
        try {
            Object packet = sprintPacketConstructor.newInstance(player, startSprintingAction);
            Object queue = OpenMaverFeatures.read(player, "sendQueue", "field_71174_a", null);
            if (queue == null) return;
            OpenMaverFeatures.callVoid(queue, "addToSendQueue", "func_147297_a", null, packet);
        } catch (Throwable error) {
            warn(error);
        }
    }

    private static void resolvePacket() {
        if (sprintPacketConstructor != null || packetLookupFailed) return;
        try {
            Class<?> action = Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction$Action");
            Class<?> packet = Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction");
            Class<?> entity = Class.forName("net.minecraft.entity.Entity");
            // 1.8.9 协议顺序：START_SNEAKING 0, STOP_SNEAKING 1, STOP_SLEEPING 2,
            // START_SPRINTING 3, STOP_SPRINTING 4, RIDING_JUMP 5, OPEN_INVENTORY 6。
            startSprintingAction = enumConstant(action, "START_SPRINTING", 3);
            if (startSprintingAction == null) {
                packetLookupFailed = true;
                return;
            }
            sprintPacketConstructor = packet.getConstructor(entity, action);
        } catch (Throwable error) {
            packetLookupFailed = true;
            warn(error);
        }
    }

    private static Object enumConstant(Class<?> type, String name, int ordinal) {
        Object[] constants = type.getEnumConstants();
        if (constants == null || constants.length == 0) return null;
        for (int i = 0; i < constants.length; i++) {
            if (constants[i] instanceof Enum
                    && name.equals(((Enum<?>) constants[i]).name())) {
                return constants[i];
            }
        }
        return (ordinal >= 0 && ordinal < constants.length) ? constants[ordinal] : null;
    }

    // ==================================================================
    // 输入预判（START 阶段）
    // ==================================================================

    /**
     * 与 {@code MovementInputFromOptions.updatePlayerMoveState()} 相同：
     * W/S 与 A/D 各取 ±1，保留原始分量。斜向标准化发生在后续 moveFlying() 中，
     * 提前标准化会让斜前向误走“原版取消冲刺”的分支，叠出两份 +30%。
     *
     * @return {@code [moveForward, moveStrafe]}，尚未乘潜行 / 使用物品的缩放
     */
    private static double[] predictedInput(Minecraft mc) {
        double forward = 0.0D;
        double strafe = 0.0D;
        if (keyDown(mc, "keyBindForward", "field_74351_w")) forward += 1.0D;
        if (keyDown(mc, "keyBindBack", "field_74368_y")) forward -= 1.0D;
        if (keyDown(mc, "keyBindLeft", "field_74370_x")) strafe += 1.0D;
        if (keyDown(mc, "keyBindRight", "field_74366_z")) strafe -= 1.0D;
        return new double[] {forward, strafe};
    }

    /**
     * 潜行状态按 KeyBinding 的 {@code pressed} 读：SafeWalk / Scaffold / GodBridge
     * 可能在这一 tick 之前替玩家按下 Shift，而 {@code MovementInputFromOptions}
     * 读的正是这个字段。
     */
    private static boolean sneakPressed(Minecraft mc) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, "keyBindSneak", "field_74311_E", null);
        return Boolean.TRUE.equals(OpenMaverFeatures.read(binding,
                "pressed", "field_74513_e", Boolean.class));
    }

    /** 与 {@code MovementInputFromOptions} 相同的物理键位读取（支持鼠标侧键绑定）。 */
    private static boolean keyDown(Minecraft mc, String mcp, String srg) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object binding = OpenMaverFeatures.read(settings, mcp, srg, null);
        Object value = OpenMaverFeatures.call(binding, "getKeyCode", "func_151463_i", null);
        if (!(value instanceof Number)) return false;
        int code = ((Number) value).intValue();
        if (code < 0) return code >= -100 && Mouse.isButtonDown(code + 100);
        return code > 0 && code < 256 && Keyboard.isKeyDown(code);
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private static void warn(Throwable error) {
        if (warned) return;
        warned = true;
        System.err.println("[OpenMaver] KeepSprint unavailable: " + error);
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        if (values == null) return fallback;
        return Boolean.parseBoolean(values.getProperty(key, String.valueOf(fallback)).trim());
    }
}
