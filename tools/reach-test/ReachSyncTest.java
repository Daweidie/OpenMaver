import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

/** Reproduces camera/reported-look divergence without connecting to a server. */
public final class ReachSyncTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        check("Grim cap is off by default so DISTANCE takes effect",
                !OpenMaverFeatures.isReachGrimCheck());
        profiles();
        layeredCaps();
        pingSpoof();
        reportedPose();
        queuedMovement();
        diagnostics();
        pendingJump();
        System.out.println("[reach-sync-test] " + passed + " checks passed");
    }

    private static void profiles() throws Exception {
        for (boolean bypass : new boolean[] {false, true}) {
            OpenMaverFeatures.setBypassEnabled(bypass);
            for (int profile = 0; profile <= 2; profile++) {
                OpenMaverFeatures.setBypassProfile(profile);
                String mode = " bypass=" + bypass + " profile=" + profile;
                // 默认（GRIM CAP 关闭）：DISTANCE 就是实际上限，滑块必须真的生效。
                OpenMaverFeatures.setReachGrimCheck(false);
                OpenMaverFeatures.setReachDistance(4.2F);
                check("4.2m setting is the effective limit" + mode,
                        OpenMaverFeatures.getReachDistance() == 4.2F
                                && OpenMaverFeatures.getEffectiveReachDistance() == 4.2F);
                check("3.32m target is attacked at 4.2m" + mode, attacks(new ReportedPlayer(), 3.32D) == 1);
                check("4.1m target is attacked at 4.2m" + mode, attacks(new ReportedPlayer(), 4.1D) == 1);
                OpenMaverFeatures.setReachDistance(5.0F);
                check("5m setting reaches a 4.8m target" + mode, attacks(new ReportedPlayer(), 4.8D) == 1);
                check("5m setting still rejects 5.01m" + mode, attacks(new ReportedPlayer(), 5.01D) == 0);
                OpenMaverFeatures.setReachDistance(3.5F);
                check("shorter distance limits the same target" + mode, attacks(new ReportedPlayer(), 4.1D) == 0);
                OpenMaverFeatures.setReachDistance(2.5F);
                check("configured distances below vanilla apply" + mode,
                        OpenMaverFeatures.getEffectiveReachDistance() == 2.5F
                                && attacks(new ReportedPlayer(), 2.6D) == 0);
                OpenMaverFeatures.setReachDistance(0.0F);
                check("zero disables supplemental attacks" + mode, attacks(new ReportedPlayer(), 1.0D) == 0);
                // GRIM CAP：硬上限 3.00 米（Grim ENTITY_INTERACTION_RANGE）。
                OpenMaverFeatures.setReachGrimCheck(true);
                OpenMaverFeatures.setReachDistance(4.2F);
                check("4.2m setting keeps the 3.00m Grim cap" + mode,
                        OpenMaverFeatures.getReachDistance() == 4.2F
                                && OpenMaverFeatures.getEffectiveReachDistance() == 3.0F);
                check("Grim cap filters the 3.32m attack" + mode, attacks(new ReportedPlayer(), 3.32D) == 0);
                check("Grim cap keeps a 2.96m target" + mode, attacks(new ReportedPlayer(), 2.96D) == 1);
                OpenMaverFeatures.setReachGrimCheck(false);
                check("turning the cap off restores the 3.32m attack" + mode,
                        attacks(new ReportedPlayer(), 3.32D) == 1);
            }
        }
    }

    /**
     * PING SPOOF：把目标过去若干 tick 的位置并进服务端复核盒子。
     * 目标在你事务窗口里从更近处走开时，服务端量到的距离比真实盒子短。
     */
    private static void pingSpoof() throws Exception {
        OpenMaverFeatures.setReachGrimCheck(true);
        OpenMaverFeatures.setReachDistance(4.2F);
        OpenMaverFeatures.setReachPingSpoof(0);
        clearHistory();
        check("spoof off rejects a 3.6m hit against the 3.00m cap",
                attacks(new ReportedPlayer(), 3.6D) == 0);
        OpenMaverFeatures.setReachPingSpoof(300);
        // ReachTarget(3.6) 的盒子中心在 z=4.0；历史里 0.7 米前的位置让并集前移。
        seedHistory(0, 4.0D - 0.7D);
        check("spoof window makes the same 3.6m hit pass the 3.00m cap",
                attacks(new ReportedPlayer(), 3.6D) == 1);
        seedHistory(0, 4.0D + 0.7D);
        check("spoof window does not help when the target is closing in",
                attacks(new ReportedPlayer(), 3.6D) == 0);
        clearHistory();
        OpenMaverFeatures.setReachPingSpoof(0);
        OpenMaverFeatures.setReachPingJitter(40);
    }

    @SuppressWarnings("unchecked")
    private static void seedHistory(int entityId, double centerZ) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField("reachHistory");
        field.setAccessible(true);
        Map<Integer, Deque<double[]>> history =
                (Map<Integer, Deque<double[]>>) field.get(null);
        Deque<double[]> deque = new ArrayDeque<double[]>();
        // 两次采样：`serverBoxFor` 只在窗口里有多于一个点时做并集。
        deque.add(new double[] {0.0D, 0.9D, centerZ});
        deque.add(new double[] {0.0D, 0.9D, centerZ});
        history.put(entityId, deque);
    }

    @SuppressWarnings("unchecked")
    private static void clearHistory() throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField("reachHistory");
        field.setAccessible(true);
        ((Map<Integer, Deque<double[]>>) field.get(null)).clear();
    }

    /**
     * 两层上限：相机射线按 DISTANCE，服务端实际量距（上报姿态）按 3.00。
     * 上一 tick 比本 tick 更靠近目标时（后撤/侧移），这段差价就是白送的距离。
     */
    private static void layeredCaps() throws Exception {
        OpenMaverFeatures.setReachGrimCheck(true);
        OpenMaverFeatures.setReachDistance(4.2F);
        check("standing 3.32m hit is rejected by the 3.00m server cap",
                attacks(new ReportedPlayer(), 3.32D) == 0);
        ReportedPlayer backpedal = new ReportedPlayer();
        backpedal.lastReportedPosZ = 0.35D;
        check("backpedal headroom lets the same 3.32m hit through",
                attacks(backpedal, 3.32D) == 1);
        ReportedPlayer tooFar = new ReportedPlayer();
        tooFar.lastReportedPosZ = -0.35D;
        check("walking in shrinks the headroom instead of adding it",
                attacks(tooFar, 2.9D) == 0);
        OpenMaverFeatures.setReachDistance(3.0F);
        check("camera cap still bounds the headroom",
                attacks(backpedal, 3.32D) == 0);
        OpenMaverFeatures.setReachDistance(4.2F);
        check("riding players get the full distance (Grim 2.0 skips vehicles)",
                attacks(new RidingPlayer(), 4.1D) == 1);
        check("riding is still bounded by DISTANCE", attacks(new RidingPlayer(), 4.3D) == 0);
        OpenMaverFeatures.setReachGrimCheck(false);
        OpenMaverFeatures.setReachDistance(4.2F);
    }

    private static void reportedPose() throws Exception {
        OpenMaverFeatures.setReachGrimCheck(true);
        OpenMaverFeatures.setReachDistance(4.2F);
        ReportedPlayer player = new ReportedPlayer();
        player.lastReportedYaw = 90.0F;
        check("camera hit with reported look miss is filtered", attacks(player, 2.9D) == 0);
        player = new ReportedPlayer();
        player.rotationYaw = 90.0F;
        check("reported hit with camera look miss is filtered", attacks(player, 2.9D) == 0);
        player = new ReportedPlayer();
        player.lastReportedPitch = 90.0F;
        check("reported pitch is used", attacks(player, 2.9D) == 0);
        player = new ReportedPlayer();
        player.lastReportedPosX = 4.0D;
        check("reported eye origin is used", attacks(player, 2.9D) == 0);
        player = new ReportedPlayer();
        player.lastReportedPosZ = -0.25D;
        check("reported distance must also fit the effective limit", attacks(player, 2.9D) == 0);
        player = new ReportedPlayer();
        player.lastReportedYaw = 360.0F;
        check("continuous yaw angles keep the same ray direction", attacks(player, 2.9D) == 1);
        player = new ReportedPlayer();
        player.lastReportedYaw = Float.NaN;
        check("nonfinite reported pose is rejected", attacks(player, 2.9D) == 0);
        player = new ReportedPlayer();
        player.lastReportedPitch = 91.0F;
        check("invalid reported pitch is rejected", attacks(player, 2.9D) == 0);
        check("missing reported fields fall back to the camera pose",
                attacks(new ReachDebugTest.ReachPlayer(), 2.9D) == 1);
        check("SRG reported fields resolve", attacks(new SrgPlayer(), 2.9D) == 1);
        SrgPlayer srg = new SrgPlayer();
        srg.field_175164_bL = 90.0F;
        check("SRG reported yaw also filters mismatched views", attacks(srg, 2.9D) == 0);
        check("1.8.9 obfuscated reported fields resolve", attacks(new ObfPlayer(), 2.9D) == 1);
        ObfPlayer obf = new ObfPlayer();
        obf.bN = 90.0F;
        check("obfuscated reported yaw also filters mismatched views", attacks(obf, 2.9D) == 0);
    }

    private static void diagnostics() throws Exception {
        OpenMaverFeatures.setBypassEnabled(false);
        OpenMaverFeatures.setReachGrimCheck(true);
        OpenMaverFeatures.setReachDistance(4.2F);
        ReachDebug.setEnabled(true);
        check("debug header exposes the Grim cap independently of global bypass",
                contains(ReachDebug.drain(), "grim=CAP 3.00")
                        && OpenMaverFeatures.getEffectiveReachDistance() == 3.0F);
        ReportedPlayer player = new ReportedPlayer();
        player.lastReportedYaw = 90.0F;
        attacks(player, 2.9D);
        check("reported ray misses have their own diagnostic count", contains(ReachDebug.history(), "reported-miss=1"));
        player = new ReportedPlayer();
        player.lastReportedPosZ = -0.25D;
        attacks(player, 2.9D);
        check("reported range failures have their own diagnostic count", contains(ReachDebug.history(), "reported-range=1"));
        attacks(new ReportedPlayer(), 3.32D);
        check("targets cut by the server-side cap are counted separately",
                contains(ReachDebug.history(), "srv=3.00")
                        && contains(ReachDebug.history(), "reported-range=1")
                        && contains(ReachDebug.history(), "grim-cap=3.00"));
        attacks(new ReachDebugTest.ReachPlayer(), 2.9D);
        check("unavailable pose is reported once and never disables Reach",
                attacks(new ReachDebugTest.ReachPlayer(), 2.9D) == 1);
        ReachDebug.setEnabled(false);
        ReachDebug.setEnabled(true);
        BrokenMinecraft mc = new BrokenMinecraft();
        invokeAttack(mc, new ReportedPlayer(), 2.9D);
        check("failed controller call is not logged as a HIT",
                contains(ReachDebug.history(), "attack failed") && !contains(ReachDebug.history(), "HIT "));
        check("failed controller call leaves the attack slot free", !usedField().getBoolean(null));
        ReachDebug.setEnabled(false);
        OpenMaverFeatures.setReachGrimCheck(false);
        OpenMaverFeatures.setReachDistance(4.2F);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void queuedMovement() throws Exception {
        Field sessionField = AttackReduceFeature.class.getDeclaredField("session");
        sessionField.setAccessible(true);
        Object previous = sessionField.get(null);
        AttackReduceFeature.Session session = new AttackReduceFeature.Session(null, null, null, null, 0, 0, null, null);
        Field movesField = session.getClass().getDeclaredField("moves");
        movesField.setAccessible(true);
        java.util.Queue moves = (java.util.Queue) movesField.get(session);
        moves.add(new Object());
        sessionField.set(null, session);
        try {
            check("held movement prevents supplemental attack", attacks(new ReportedPlayer(), 2.9D) == 0);
            moves.clear();
            check("drained movement restores supplemental attack", attacks(new ReportedPlayer(), 2.9D) == 1);
        } finally {
            sessionField.set(null, previous);
        }
    }

    private static void pendingJump() throws Exception {
        module.combat.Reach reach = new module.combat.Reach();
        module.combat.Criticals criticals = new module.combat.Criticals();
        module.ModuleManager.register(reach);
        module.ModuleManager.register(criticals);
        reach.setEnabled(true);
        criticals.setEnabled(true);
        OpenMaverFeatures.setCriticalsMode(featureField("CRITICALS_JUMP").getInt(null));
        OpenMaverFeatures.setReachGrimCheck(true);
        OpenMaverFeatures.setReachDistance(4.2F);
        try {
            PendingMinecraft mc = pendingFixture(3.32D);
            runPending(mc, false);
            check("delayed Reach attacks repeat the geometry checks", mc.playerController.attacks == 0);
            mc = pendingFixture(2.9D);
            mc.thePlayer.lastReportedYaw = 90.0F;
            runPending(mc, false);
            check("delayed Reach attacks repeat the reported look check", mc.playerController.attacks == 0);
            mc = pendingFixture(2.9D);
            runPending(mc, true);
            check("delayed Reach attacks respect the shared attack slot", mc.playerController.attacks == 0);
            mc = pendingFixture(2.9D);
            runPending(mc, false);
            check("valid delayed Reach attacks swing before attacking", mc.playerController.attacks == 1);
            reach.setEnabled(false);
            mc = pendingFixture(2.9D);
            runPending(mc, false);
            check("disabling Reach cancels a pending Reach jump attack", mc.playerController.attacks == 0);
        } finally {
            reach.setEnabled(false);
            criticals.setEnabled(false);
        }
    }

    private static PendingMinecraft pendingFixture(double distance) {
        PendingMinecraft mc = new PendingMinecraft();
        mc.thePlayer = new ReportedPlayer();
        mc.thePlayer.visible = true;
        mc.thePlayer.motionY = -0.1D;
        mc.theWorld = new World();
        mc.theWorld.loadedEntityList.add(new ReachDebugTest.ReachTarget(distance));
        return mc;
    }

    private static void runPending(PendingMinecraft mc, boolean used) throws Exception {
        usedField().setBoolean(null, used);
        setFeatureField("criticalsJumpTarget", mc.theWorld.loadedEntityList.get(0));
        setFeatureField("criticalsJumpAttackSource", featureField("CRITICALS_ATTACK_REACH").getInt(null));
        setFeatureField("criticalsJumpDeadlineNanos", System.nanoTime() + 5_000_000_000L);
        Method process = OpenMaverFeatures.class.getDeclaredMethod("processPendingCriticalJump", Minecraft.class);
        process.setAccessible(true);
        process.invoke(null, mc);
    }

    private static Field featureField(String name) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setFeatureField(String name, Object value) throws Exception {
        featureField(name).set(null, value);
    }

    private static int attacks(ReachDebugTest.ReachPlayer player, double distance) throws Exception {
        ReachDebugTest.ReachMinecraft mc = new ReachDebugTest.ReachMinecraft();
        invokeAttack(mc, player, distance);
        return mc.playerController.attacks;
    }

    private static void invokeAttack(Minecraft mc, ReachDebugTest.ReachPlayer player, double distance) throws Exception {
        player.visible = true;
        World world = new World();
        world.loadedEntityList.add(new ReachDebugTest.ReachTarget(distance));
        usedField().setBoolean(null, false);
        Method attack = OpenMaverFeatures.class.getDeclaredMethod("attackNearestEntityInReach",
                Minecraft.class, Object.class, Object.class);
        attack.setAccessible(true);
        attack.invoke(null, mc, player, world);
    }

    private static Field usedField() throws Exception {
        Field used = OpenMaverFeatures.class.getDeclaredField("entityAttackUsedThisTick");
        used.setAccessible(true);
        return used;
    }

    private static boolean contains(Iterable<String> lines, String text) {
        for (String line : lines) if (line.contains(text)) return true;
        return false;
    }

    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        passed++;
        System.out.println("[reach-sync-test] ok: " + name);
    }

    public static final class ReportedPlayer extends ReachDebugTest.ReachPlayer {
        private double lastReportedPosX, lastReportedPosY, lastReportedPosZ;
        private float lastReportedYaw, lastReportedPitch;
    }

    public static final class RidingPlayer extends ReachDebugTest.ReachPlayer {
        /** 只要非 null 就代表骑乘；Grim 2.0 会因此整条跳过 Reach。 */
        private final Object ridingEntity = new Object();
    }

    public static final class SrgPlayer extends ReachDebugTest.ReachPlayer {
        private double field_175172_bI, field_175166_bJ, field_175167_bK;
        private float field_175164_bL, field_175165_bM;
    }

    public static final class ObfPlayer extends ReachDebugTest.ReachPlayer {
        private double bK, bL, bM;
        private float bN, bO;
    }

    public static final class BrokenMinecraft extends Minecraft {
        public final Object playerController = new Object();
    }

    public static final class PendingMinecraft extends Minecraft {
        public ReportedPlayer thePlayer;
        public final ReachDebugTest.ReachController playerController = new ReachDebugTest.ReachController();
    }
}
