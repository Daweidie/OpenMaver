import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Properties;

/**
 * Velocity 包层功能测试（不依赖 Minecraft 本体）。
 *
 * <p>用 netty-all 4.0.23.Final（MC 1.8.9 自带版本）的 EmbeddedChannel 真实驱动
 * {@link VelocityPacketHook}：插入位置、入站 S12 是否被吞掉、出站 C03 是否被憋住
 * 与补发，以及各模式对 motion 的写入结果。
 */
public final class VelocityHookTest {

    /** 测试替身玩家：字段名用运行时 SRG 名，与 OpenMaverFeatures.setNumber 的查找方式一致。 */
    public static final class FakePlayer {
        public double field_70159_w;   // motionX
        public double field_70181_x;   // motionY
        public double field_70179_y;   // motionZ
        public float field_70177_z;    // rotationYaw
        public float field_70125_A;    // rotationPitch
        public double field_70165_t;   // posX
        public double field_70163_u;   // posY
        public double field_70161_v;   // posZ
        public boolean field_70122_E;  // onGround
        public Object field_71158_b;   // movementInput
        public int field_70737_aN;     // hurtTime
    }

    /** 只用来验证 burst 的 reach 基准点（服务端停住的那一格）。 */
    public static final class FakeTarget {
        public double field_70165_t;
        public double field_70163_u;
        public double field_70161_v;
    }

    /** 模拟 1.8.9 管线里的 encoder：把 Packet 序列化成 ByteBuf 语义的字符串。 */
    public static final class Encoder extends ChannelDuplexHandler {
        int encoded;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            encoded++;
            ctx.write("ENC:" + msg, promise);
        }
    }

    public static final class Tail extends ChannelDuplexHandler {
    }

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // ==============================================================
        // A. 插入位置：必须是 packet_handler（= encoder 之后）
        // ==============================================================
        String base = VelocityFeature.pickBaseName(Arrays.asList(
                "timeout", "splitter", "decoder", "prepender", "encoder", "packet_handler"));
        check("insert before packet_handler", "packet_handler".equals(base));
        check("no base name when pipeline unknown",
                VelocityFeature.pickBaseName(Arrays.asList("a", "b")) == null);

        // ==============================================================
        // B. 出站可见性：hook 排在 encoder 之后才能拿到未序列化的 Packet
        // ==============================================================
        VelocityFeature.load(new Properties());
        setStatic("active", Boolean.TRUE);
        setStatic("localEntityId", Integer.valueOf(7));
        VelocityFeature.setMode(VelocityFeature.MODE_BLINK);

        Encoder encoder = new Encoder();
        VelocityPacketHook hook = new VelocityPacketHook();
        EmbeddedChannel channel = new EmbeddedChannel(encoder, hook, new Tail());
        invoke("startBlink", new Class<?>[0]);
        Object move = newMove(1.0D);
        channel.writeOutbound(move);
        check("packet held before encoder runs", encoder.encoded == 0
                && VelocityFeature.getHeldCount() == 1);
        invoke("releaseHeld", new Class<?>[] {ChannelHandlerContext.class}, new Object[] {null});
        Object delivered = channel.readOutbound();
        check("encoder runs after release", encoder.encoded == 1);
        check("release re-encodes the same packet", "ENC:C03(1.0)".equals(String.valueOf(delivered)));
        setStatic("active", Boolean.FALSE);

        // ==============================================================
        // C. 主行为
        // ==============================================================
        VelocityFeature.load(new Properties());
        // 这一段验证的是各模式本身的数学，不是守护逻辑：先把守护关掉，
        // 否则 MODIFY 0% / REVERSAL 会被守护改写成 BLINK（守护的断言在 7c）。
        VelocityFeature.setGrimGuard(false);
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setHorizontal(0.0F);
        VelocityFeature.setVertical(0.0F);
        setStatic("active", Boolean.TRUE);
        setStatic("localEntityId", Integer.valueOf(7));

        // netty 4.0 的 EmbeddedChannel 会把入站终点 handler 拍在构造函数末尾，
        // 之后再 addLast 的 handler 收不到入站消息，因此这里直接构造进管线。
        VelocityPacketHook hook2 = new VelocityPacketHook();
        EmbeddedChannel channel2 = new EmbeddedChannel(hook2, new Tail());
        System.out.println("pipeline=" + channel2.pipeline().names());

        // ---- 1. 入站 S12：本地玩家的击退包必须被吞掉，并解析出向量 ----
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        check("inbound S12 swallowed", channel2.readInbound() == null);
        check("kb vector x parsed", Math.abs(getDouble("pendingKbX") - 0.4D) < 1e-3D);
        check("kb vector y parsed", Math.abs(getDouble("pendingKbY") - 0.3608D) < 1e-3D);
        check("kb vector z parsed", Math.abs(getDouble("pendingKbZ") + 0.4D) < 1e-3D);

        // ---- 2. 别人的击退包必须放行 ----
        Object otherS12 = newS12(99, 0.4D, 0.4D, 0.4D);
        channel2.writeInbound(otherS12);
        check("foreign S12 passes through", channel2.readInbound() == otherS12);

        // ---- 3. MODIFY 0%：motion 归零 ----
        FakePlayer player = new FakePlayer();
        applyKnockback(player);
        check("modify 0% kills horizontal", player.field_70159_w == 0.0D && player.field_70179_y == 0.0D);
        check("modify 0% kills vertical", player.field_70181_x == 0.0D);

        // ---- 4. MODIFY 50% / 垂直 100% ----
        VelocityFeature.setHorizontal(0.5F);
        VelocityFeature.setVertical(1.0F);
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("modify 50% horizontal halved", Math.abs(player.field_70159_w - 0.2D) < 1e-6D);
        check("modify 100% vertical kept", Math.abs(player.field_70181_x - 0.3608D) < 1e-3D);

        // ---- 5. REVERSAL：延迟到期后反向 ----
        VelocityFeature.setMode(VelocityFeature.MODE_REVERSAL);
        VelocityFeature.setReversalDelay(0);
        VelocityFeature.setReversalStrength(1.0F);
        player.field_70159_w = 0.0D;
        player.field_70179_y = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("reversal eats knockback first", player.field_70159_w == 0.0D);
        Thread.sleep(3L);
        invoke("tickReversal", new Class<?>[] {Object.class}, player);
        check("reversal pushes toward attacker x", Math.abs(player.field_70159_w + 0.4D) < 1e-6D);
        check("reversal pushes toward attacker z", Math.abs(player.field_70179_y - 0.4D) < 1e-6D);

        // ---- 6. STRAFE：无输入时沿击退法线侧移 ----
        VelocityFeature.setMode(VelocityFeature.MODE_STRAFE);
        VelocityFeature.setStrafeFactor(1.0F);
        player.field_70159_w = 0.0D;
        player.field_70179_y = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, 0.0D));
        applyKnockback(player);
        check("strafe converts to sideways", Math.abs(player.field_70159_w) < 1e-6D
                && Math.abs(player.field_70179_y - 0.4D) < 1e-6D);

        // ---- 7. BLINK：憋住出站 C03，结束再补发 ----
        VelocityFeature.setMode(VelocityFeature.MODE_BLINK);
        VelocityFeature.setBlinkTicks(5);
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("blink started", VelocityFeature.isBlinking());

        Object move1 = newMove(1.0D);
        Object move2 = newMove(2.0D);
        Object swing = new Object();     // 非 C03，必须直接放行
        channel2.writeOutbound(move1);
        channel2.writeOutbound(move2);
        channel2.writeOutbound(swing);
        check("two C03 held", VelocityFeature.getHeldCount() == 2);
        Object firstOut = channel2.readOutbound();
        check("non-movement packet passes while held", firstOut == swing);
        check("held C03 not on wire yet", channel2.readOutbound() == null);

        finishBlink(player);
        check("blink finished", !VelocityFeature.isBlinking());
        check("held queue drained", VelocityFeature.getHeldCount() == 0);
        Object released1 = channel2.readOutbound();
        Object released2 = channel2.readOutbound();
        check("buffered positions flushed in order", released1 == move1 && released2 == move2);

        // ---- 7b. Blink 的 Grim 三件套：受力保真 / 不制造滞空 / reach 按憋包起点 ----
        VelocityFeature.setMode(VelocityFeature.MODE_BLINK);
        VelocityFeature.setBlinkKeep(1.0F);
        player.field_70122_E = false;
        player.field_70165_t = 1.5D;
        player.field_70163_u = 64.0D;
        player.field_70161_v = -2.5D;
        player.field_70159_w = 0.0D;
        player.field_70181_x = 0.0D;
        player.field_70179_y = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("blink keeps knockback in motion (Grim needs the delta)",
                Math.abs(player.field_70159_w - 0.4D) < 1e-6D
                        && Math.abs(player.field_70179_y + 0.4D) < 1e-6D);
        check("blink keeps vertical when airborne",
                Math.abs(player.field_70181_x - 0.3608D) < 1e-3D);
        check("blink origin captured from pre-hold position",
                Math.abs(getDouble("blinkOriginX") - 1.5D) < 1e-9D
                        && Math.abs(getDouble("blinkOriginZ") + 2.5D) < 1e-9D);

        check("burst range measured from blink origin",
                Math.abs(burstDistance(player, targetAt(4.5D, 64.0D, -2.5D)) - 3.0D) < 1e-6D);
        check("burst range ignores client drift",
                Math.abs(burstDistance(playerAt(4.0D, 64.0D, -2.5D),
                        targetAt(4.5D, 64.0D, -2.5D)) - 3.0D) < 1e-6D);

        player.field_70122_E = true;
        player.field_70181_x = 0.0D;
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setHorizontal(0.0F);
        VelocityFeature.setVertical(0.0F);
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("grounded player keeps vertical motion (no GroundSpoof)",
                player.field_70181_x == 0.0D);
        check("grounded player still loses horizontal", player.field_70159_w == 0.0D);

        // 弃包回退：本地坐标回到 P0，缓存清空
        VelocityFeature.setMode(VelocityFeature.MODE_BLINK);
        VelocityFeature.setBlinkFlush(false);
        VelocityFeature.setBlinkRewind(true);
        player.field_70122_E = false;
        player.field_70165_t = 0.0D;
        player.field_70161_v = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        channel2.writeOutbound(newMove(9.0D));
        check("rewind path holds a packet first", VelocityFeature.getHeldCount() == 1);
        finishBlink(player);
        check("rewind drains held packets", VelocityFeature.getHeldCount() == 0);
        check("rewind restores blink origin",
                Math.abs(player.field_70165_t - 0.0D) < 1e-9D
                        && Math.abs(player.field_70161_v - 0.0D) < 1e-9D);
        VelocityFeature.setBlinkFlush(true);
        VelocityFeature.setBlinkRewind(false);

        // ---- 7c. Grim 守护：归零类模式不允许在 Grim 上跑起来 ----
        VelocityFeature.setServer(VelocityFeature.SERVER_GRIM);   // 预设 -> BLINK
        VelocityFeature.setGrimGuard(true);
        VelocityFeature.setMode(VelocityFeature.MODE_REVERSAL);
        check("guard upgrades REVERSAL to SPRINT SLAP",
                effectiveMode() == VelocityFeature.MODE_SPRINT_SLAP);
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setHorizontal(0.0F);
        VelocityFeature.setVertical(0.0F);
        check("guard upgrades zero MODIFY to SPRINT SLAP",
                effectiveMode() == VelocityFeature.MODE_SPRINT_SLAP);
        VelocityFeature.setMode(VelocityFeature.MODE_BLINK);
        check("guard upgrades BLINK (no reduction, just packet lag)",
                effectiveMode() == VelocityFeature.MODE_SPRINT_SLAP);
        VelocityFeature.setHorizontal(0.45F);
        VelocityFeature.setVertical(0.85F);
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        check("guard keeps 45/85 MODIFY as-is",
                effectiveMode() == VelocityFeature.MODE_MODIFY);
        check("guard raises horizontal floor",
                Math.abs(vRatio() - 0.85D) < 1e-6D);
        VelocityFeature.setVertical(0.0F);
        check("guard raises vertical floor to 35%",
                Math.abs(vRatio() - 0.35D) < 1e-6D);
        VelocityFeature.setGrimGuard(false);
        VelocityFeature.setMode(VelocityFeature.MODE_REVERSAL);
        check("guard off keeps REVERSAL (user override)",
                effectiveMode() == VelocityFeature.MODE_REVERSAL);
        VelocityFeature.setGrimGuard(true);
        VelocityFeature.setServer(VelocityFeature.SERVER_INTAVE);
        VelocityFeature.setMode(VelocityFeature.MODE_REVERSAL);
        check("guard leaves explicit non-Grim preset alone",
                effectiveMode() == VelocityFeature.MODE_REVERSAL);

        VelocityFeature.setServer(VelocityFeature.SERVER_GRIM);
        VelocityFeature.setBlinkKeep(1.0F);
        VelocityFeature.setHorizontal(1.0F);
        VelocityFeature.setVertical(1.0F);

        // ---- 7d. UNCERTAINTY / COUNTER 两个新模式 ----
        VelocityFeature.setServer(VelocityFeature.SERVER_GRIM);
        VelocityFeature.setGrimGuard(true);
        // 18:38 实机：本服 Grim 带 TransactionOrder 检查，扣 S32 两 tick 就是
        // `TransactionOrder (vl:0.0): skipped=2`，NoXZ 的回执前提失效 → 预设回到
        // SPRINT SLAP；NoXZ 仍可手动选。
        check("grim preset selects SPRINT SLAP (NoXZ is detected by TransactionOrder)",
                VelocityFeature.getMode() == VelocityFeature.MODE_SPRINT_SLAP);
        check("preset keeps full motion (no offset at all)",
                VelocityFeature.getHorizontal() == 1.0F && VelocityFeature.getVertical() == 1.0F);
        check("preset exposes the noxz attack budget",
                VelocityFeature.getNoXzAttacks() >= 1);

        // ---- 7d-0. NOXZ：空中吞击退 + 冻结位置包 ----
        // setMode 只在值变化时 resetRuntime，这里先切走再切回来，保证从干净状态开始。
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setMode(VelocityFeature.MODE_NOXZ);
        // 默认 noxzTx=false 会把 NoXZ 降级成 SPRINT SLAP（本服 Grim 的
        // TransactionOrder 检查让扣回执这条路径直接失效）。测 NoXZ 本体时显式打开。
        check("noxz degrades to SPRINT SLAP unless noxzTx is enabled",
                effectiveMode() == VelocityFeature.MODE_SPRINT_SLAP);
        setStatic("noxzTx", Boolean.TRUE);
        check("noxz runs as NOXZ when noxzTx is enabled",
                effectiveMode() == VelocityFeature.MODE_NOXZ);
        player.field_70122_E = false;                       // 空中
        invoke("tickNoXz", new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
        Object noxzS12 = newS12(7, 0.4D, 0.3608D, -0.4D);
        channel2.writeInbound(noxzS12);
        check("noxz swallows the knockback while airborne",
                channel2.readInbound() == null && VelocityFeature.isNoXzSuspending());
        Object noxzMove = newMove(3.0D);
        channel2.writeOutbound(noxzMove);
        check("noxz freezes outbound C03", channel2.readOutbound() == null);
        Object noxzS12b = newS12(7, 0.4D, 0.3608D, -0.4D);
        channel2.writeInbound(noxzS12b);
        check("noxz keeps swallowing while suspended", channel2.readInbound() == null);
        Object noxzS32 = newS32();
        channel2.writeInbound(noxzS32);
        check("noxz delays the transaction confirm (S32)", channel2.readInbound() == null);

        // ---- 7d-0b. 落地：退出挂起 ----
        player.field_70122_E = true;
        invoke("tickNoXz", new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
        check("noxz releases suspension after landing", !VelocityFeature.isNoXzSuspending());
        setStatic("noxzTx", Boolean.FALSE);

        // ---- 7d-1. SPRINT SLAP：击退包必须原样放行 ----
        VelocityFeature.setMode(VelocityFeature.MODE_SPRINT_SLAP);
        channel2.runPendingTasks();
        Object slapS12 = newS12(7, 0.4D, 0.3608D, -0.4D);
        player.field_70159_w = 0.0D;
        player.field_70181_x = 0.0D;
        player.field_70179_y = 0.0D;
        channel2.writeInbound(slapS12);
        check("sprint slap lets S12 through (client really takes the kb)",
                channel2.readInbound() == slapS12);
        check("sprint slap recorded the knockback", ((Boolean) getStatic("kbPending")).booleanValue());
        long windowsBefore = VelocityFeature.getSlapWindows();
        applyKnockback(player);
        check("sprint slap opens a window", VelocityFeature.getSlapWindows() == windowsBefore + 1L);
        check("sprint slap is active after the knockback", VelocityFeature.isSlapping());
        check("sprint slap never writes motion (zero offset by construction)",
                player.field_70159_w == 0.0D && player.field_70181_x == 0.0D
                        && player.field_70179_y == 0.0D);
        check("sprint slap does not open a blink window", !VelocityFeature.isBlinking());
        check("sprint slap window length follows |kb|",
                ((Integer) getStatic("slapTicksLeft")).intValue() >= 1
                        && ((Integer) getStatic("slapTicksLeft")).intValue()
                        <= VelocityFeature.getSlapMaxTicks());

        // ---- 7d-1a2. 2026-11-16 18:53 实机（vl 涨到 83）的三条归因 ----
        //
        // (1) 挨打那一 tick 就出手：Grim 的击退是「两个 transaction 夹住 S12」结算的，
        //     同批事务下一 tick 才确认。早一 tick 乘的是 0.6·v_old 而不是 0.6·v_kb，
        //     日志里就是 `AntiKB (vl:0.0): o: .046243 / .235468 / .254650`。
        check("slap window starts one tick after the knockback tick",
                VelocityFeature.getSlapStartTick() == VelocityFeature.getKbArriveTick() + 1);
        //
        // (2) 冲刺状态只改客户端、不发 C0B：服务端这一 tick 仍按冲刺算加速度
        //     （jumpMovementFactor 0.026），客户端已经按不冲刺算（0.020），
        //     每 tick 差 0.006 格 —— 正是 `Simulation (vl:0..83): .006153/.006157`。
        check("sprint state is synced with a real C0B by default",
                VelocityFeature.isSlapSprintSync());
        VelocityFeature.setSlapSprintSync(false);
        check("sprint sync can be disabled for A/B (old behaviour)",
                !VelocityFeature.isSlapSprintSync());
        check("sprint sync is persisted under velocity.*",
                VelocityFeature.save().contains("velocity.slapSprintSync=false"));
        VelocityFeature.setSlapSprintSync(true);
        //
        // (3) 第 6 次 ×0.6 在服务端没有分支（Grim: Math.min(maxAttackSlow, 5)），
        //     多削的那一次凭空少 40% 位移 → 窗口长度必须卡在 5。
        VelocityFeature.setSlapMaxTicks(10);
        channel2.runPendingTasks();
        channel2.writeInbound(newS12(7, 4.096D, 0.4D, 4.096D));
        applyKnockback(player);
        check("slap window never exceeds Grim's 5 attack-slow branches",
                ((Integer) getStatic("slapTicksLeft")).intValue() <= 5);
        channel2.readInbound();   // 这一发是自己写进去的，读掉它，别污染后面的断言
        VelocityFeature.setSlapMaxTicks(6);

        // ---- 7d-1b. attackEntity 在 1.8.9 是 void（joined.srg: func_78764_a …)V） ----
        // 回归点：曾经用 call() 的返回值判断「这一下打出去了没有」，而
        // Method.invoke 对 void 恒返回 null → 每一次都被当成失败，0.6 一次没乘，
        // 实机日志就是「命中=0，上次跳过=attackEntity 返回 null」。
        VoidController voidController = new VoidController();
        check("void attackEntity: call() reports null (the old bug)",
                OpenMaverFeatures.call(voidController, "attackEntity", "func_78764_a", "a",
                        player, player) == null);
        voidController.calls = 0;
        check("void attackEntity: callVoid() reports success",
                OpenMaverFeatures.callVoid(voidController, "attackEntity", "func_78764_a", "a",
                        player, player));
        check("void attackEntity: the attack really happened", voidController.calls == 1);

        // ---- 7d-1c. NOXZ 在地面放行 S12 时也要开攻击窗口 ----
        // 之前 MODE_NOXZ 掉进 default 的 MODIFY 分支，而 GRIM 预设 100/100，
        // 等于把击退原样写回去 —— 地面挨打完全没有效果。
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setMode(VelocityFeature.MODE_NOXZ);
        VelocityFeature.setGrimGuard(true);
        player.field_70122_E = true;                        // 站地上
        player.field_70159_w = 0.0D;
        player.field_70181_x = 0.0D;
        player.field_70179_y = 0.0D;
        channel2.runPendingTasks();
        Object groundS12 = newS12(7, 0.4D, 0.3608D, -0.4D);
        channel2.writeInbound(groundS12);
        check("noxz lets the ground knockback through", channel2.readInbound() == groundS12);
        long groundWindows = VelocityFeature.getSlapWindows();
        applyKnockback(player);
        check("noxz ground hit opens a slap window",
                VelocityFeature.getSlapWindows() == groundWindows + 1L);
        check("noxz ground hit invents no motion (S12 already applied it)",
                player.field_70159_w == 0.0D && player.field_70179_y == 0.0D);

        // 显式关掉守护后，UNCERTAINTY 仍然可以手动选（对测用）
        VelocityFeature.setGrimGuard(false);

        // 空手（FakePlayer 没有 getHeldItem / sendQueue）时 farm 自动退化，归零照常
        VelocityFeature.setMode(VelocityFeature.MODE_UNCERTAINTY);
        VelocityFeature.setUncertaintyTicks(2);
        VelocityFeature.setUncertaintyFarm(true);
        player.field_70159_w = 0.0D;
        player.field_70179_y = 0.0D;
        player.field_70181_x = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("farm failure falls back to COUNTER (no zeroing)",
                player.field_70159_w == 0.0D && player.field_70179_y == 0.0D
                        && player.field_70181_x == 0.0D);
        check("fallback does not open a blink window", !VelocityFeature.isBlinking());
        check("fallback schedules the jump", ((Integer) getStatic("jumpResetCountdown")).intValue() == 0);
        invoke("tickJumpReset", new Class<?>[] {Object.class}, player);
        check("fallback jumps instead of freezing", Math.abs(player.field_70181_x - 0.42D) < 1e-6D);
        check("uncertainty window uses uncertaintyTicks", activeBlinkTicks() == 2);
        check("farm failure is counted", VelocityFeature.getFarmFail() > 0L);

        // 上一段已经在守护关闭下跑过 UNCERTAINTY：这里恢复守护，确认它被拦下
        VelocityFeature.setGrimGuard(true);
        VelocityFeature.setMode(VelocityFeature.MODE_UNCERTAINTY);
        check("guard also blocks a manually selected UNCERTAINTY",
                effectiveMode() == VelocityFeature.MODE_SPRINT_SLAP);
        VelocityFeature.setGrimGuard(false);
        VelocityFeature.setMode(VelocityFeature.MODE_UNCERTAINTY);

        // farm 成功时（用反射注入假的 sendQueue + getHeldItem）才归零 + 憋包
        VelocityFeature.setUncertaintyFarm(false);
        player.field_70181_x = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("farm disabled also falls back (never zeroes blind)",
                !VelocityFeature.isBlinking());

        // COUNTER：一个字节的速度都不改，只起跳 + 反打
        VelocityFeature.setMode(VelocityFeature.MODE_COUNTER);
        check("counter mode is not guard-overridden",
                effectiveMode() == VelocityFeature.MODE_COUNTER);
        player.field_70159_w = 0.0D;
        player.field_70179_y = 0.0D;
        player.field_70181_x = 0.0D;
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("counter mode leaves motion untouched (zero offset by construction)",
                player.field_70159_w == 0.0D && player.field_70179_y == 0.0D
                        && player.field_70181_x == 0.0D);
        invoke("tickJumpReset", new Class<?>[] {Object.class}, player);
        check("counter mode jumps on the knockback tick",
                Math.abs(player.field_70181_x - 0.42D) < 1e-6D);

        VelocityFeature.setMode(VelocityFeature.MODE_UNCERTAINTY);
        VelocityFeature.setServer(VelocityFeature.SERVER_GRIM);

        // ---- 8. MODIFY 100/100 等价原版：不拦包 ----
        VelocityFeature.setMode(VelocityFeature.MODE_MODIFY);
        VelocityFeature.setHorizontal(1.0F);
        VelocityFeature.setVertical(1.0F);
        Object vanillaS12 = newS12(7, 0.4D, 0.3608D, -0.4D);
        channel2.writeInbound(vanillaS12);
        check("100/100 modify lets S12 through", channel2.readInbound() == vanillaS12);
        check("no pending knockback recorded", !((Boolean) getStatic("kbPending")).booleanValue());

        // ---- 9. JumpReset：击退事件触发起跳（不依赖 hurtTime） ----
        VelocityFeature.setMode(VelocityFeature.MODE_JUMP_RESET);
        VelocityFeature.setHorizontal(0.0F);
        VelocityFeature.setVertical(0.0F);
        VelocityFeature.setJumpDelay(0);
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        applyKnockback(player);
        check("jump reset scheduled", ((Integer) getStatic("jumpResetCountdown")).intValue() == 0);
        invoke("tickJumpReset", new Class<?>[] {Object.class}, player);
        check("jump reset raised motionY", Math.abs(player.field_70181_x - 0.42D) < 1e-6D);
        check("jump reset cleared countdown",
                ((Integer) getStatic("jumpResetCountdown")).intValue() == -1);

        // ---- 10. 无管线兜底：运动特征嗅探 ----
        setStatic("hookInstalled", Boolean.FALSE);
        setStatic("motionTracked", Boolean.TRUE);
        setStatic("lastMotionX", Double.valueOf(0.0D));
        setStatic("lastMotionY", Double.valueOf(0.0D));
        setStatic("lastMotionZ", Double.valueOf(0.0D));
        player.field_70159_w = 0.4D;      // 击退水平
        player.field_70181_x = 0.36D;
        player.field_70179_y = 0.0D;
        invoke("sniffKnockback", new Class<?>[] {Object.class}, player);
        check("sniff detects knockback", ((Boolean) getStatic("kbPending")).booleanValue());

        setStatic("kbPending", Boolean.FALSE);
        player.field_70159_w = 0.20D;     // 冲刺跳：水平不够
        player.field_70181_x = 0.42D;
        invoke("sniffKnockback", new Class<?>[] {Object.class}, player);
        check("sniff ignores sprint jump", !((Boolean) getStatic("kbPending")).booleanValue());

        player.field_70159_w = 0.40D;     // 击退但正在下落：垂直为负
        player.field_70181_x = -0.35D;
        invoke("sniffKnockback", new Class<?>[] {Object.class}, player);
        check("sniff ignores falling", !((Boolean) getStatic("kbPending")).booleanValue());

        // ---- 11. 关闭模块后残留状态必须清空 ----
        setStatic("active", Boolean.FALSE);
        channel2.writeInbound(newS12(7, 0.4D, 0.3608D, -0.4D));
        check("inactive module lets S12 through", channel2.readInbound() != null);
        channel2.finish();
        channel.finish();

        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 1.8.9 PlayerControllerMP#attackEntity 的返回类型就是 void（joined.srg 里是 V）。 */
    public static final class VoidController {
        int calls;

        public void attackEntity(Object player, Object target) {
            calls++;
        }
    }

    /** 构造一个 S32（transaction 确认）替身。 */
    private static Object newS32() throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.play.server.S32PacketConfirmTransaction");
        return type.getConstructor().newInstance();
    }

    private static void applyKnockback(FakePlayer player) throws Exception {
        invoke("applyPendingKnockback",
                new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
    }

    private static void finishBlink(FakePlayer player) throws Exception {
        invoke("finishBlink",
                new Class<?>[] {Class.forName("net.minecraft.client.Minecraft"), Object.class},
                null, player);
    }

    private static Object newS12(int entityId, double x, double y, double z) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.play.server.S12PacketEntityVelocity");
        return type.getConstructor(int.class, double.class, double.class, double.class)
                .newInstance(Integer.valueOf(entityId), Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
    }

    private static int activeBlinkTicks() throws Exception {
        Method method = VelocityFeature.class.getDeclaredMethod("activeBlinkTicks");
        method.setAccessible(true);
        return ((Integer) method.invoke(null)).intValue();
    }

    private static int effectiveMode() throws Exception {
        Method method = VelocityFeature.class.getDeclaredMethod("effectiveMode");
        method.setAccessible(true);
        return ((Integer) method.invoke(null)).intValue();
    }

    private static double vRatio() throws Exception {
        Method method = VelocityFeature.class.getDeclaredMethod("vRatio");
        method.setAccessible(true);
        return ((Double) method.invoke(null)).doubleValue();
    }

    private static double burstDistance(Object player, Object target) throws Exception {
        Method method = VelocityFeature.class.getDeclaredMethod(
                "burstDistance", Object.class, Object.class);
        method.setAccessible(true);
        return ((Double) method.invoke(null, player, target)).doubleValue();
    }

    private static Object targetAt(double x, double y, double z) {
        FakeTarget target = new FakeTarget();
        target.field_70165_t = x;
        target.field_70163_u = y;
        target.field_70161_v = z;
        return target;
    }

    private static Object playerAt(double x, double y, double z) {
        FakePlayer other = new FakePlayer();
        other.field_70165_t = x;
        other.field_70163_u = y;
        other.field_70161_v = z;
        return other;
    }

    private static Object newMove(double x) throws Exception {
        Class<?> type = Class.forName("net.minecraft.network.play.client.C03PacketPlayer");
        return type.getConstructor(double.class).newInstance(Double.valueOf(x));
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field field = VelocityFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Object getStatic(String name) throws Exception {
        Field field = VelocityFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static double getDouble(String name) throws Exception {
        Field field = VelocityFeature.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Number) field.get(null)).doubleValue();
    }

    private static void invoke(String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = VelocityFeature.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try {
            method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw new RuntimeException(name + " threw " + cause, cause);
        }
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + label);
        } else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }
}
