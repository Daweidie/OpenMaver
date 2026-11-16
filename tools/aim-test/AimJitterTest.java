import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Aim Assist 落点抖动的纯逻辑验证（不需要启动 Minecraft）。
 *
 * <p>问题背景：head 命中盒是 0.6 × 0.44，中心点就是 (8,8)。以前
 * {@code computeAimAngles} 精确算中心，每次锁定都把准星钉死在同一个几何点上 ——
 * 服务端看到的是一串零方差的完美瞄准。抖动要做的是「落点每次都不同、但仍在
 * 命中盒里」。
 *
 * <p>这里直接驱动 {@code OpenMaverFeatures.applyAimOffset}，验证四件事：
 * <ol>
 *   <li>强度 0 → 偏移 0（可退回老行为）；</li>
 *   <li>强度 &gt; 0 → 偏移一定不为 0（不会落在正中心）；</li>
 *   <li>偏移不超过命中盒半宽换算出的角度（仍然打得中）；</li>
 *   <li>锁定期间保持恒定，过了刷新周期才重滚。</li>
 * </ol>
 */
public final class AimJitterTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("OpenMaverFeatures");
        Method apply = type.getDeclaredMethod("applyAimOffset", double.class, float[].class);
        apply.setAccessible(true);
        Method setJitter = type.getDeclaredMethod("setAimJitter", float.class);
        setJitter.setAccessible(true);
        Field offsetYaw = type.getDeclaredField("aimOffsetYaw");
        offsetYaw.setAccessible(true);
        Field offsetPitch = type.getDeclaredField("aimOffsetPitch");
        offsetPitch.setAccessible(true);
        Field tickField = type.getDeclaredField("clientTick");
        tickField.setAccessible(true);
        Method clearLock = type.getDeclaredMethod("clearAimLock");
        clearLock.setAccessible(true);

        // ---- 1. 强度 0 = 老行为：精确打中心 ----
        setJitter.invoke(null, Float.valueOf(0.0F));
        clearLock.invoke(null);
        float[] angles = {45.0F, 10.0F};
        apply.invoke(null, Double.valueOf(3.0D), angles);
        check("jitter 0 keeps the exact hitbox centre",
                angles[0] == 45.0F && angles[1] == 10.0F);

        // ---- 2/3. 默认强度：偏移非 0，且落在命中盒内 ----
        setJitter.invoke(null, Float.valueOf(0.35F));
        double distance = 3.0D;
        double halfYaw = Math.toDegrees(Math.atan2(0.30D, distance));
        double halfPitch = Math.toDegrees(Math.atan2(0.22D, distance));
        int offCentre = 0;
        int insideBox = 0;
        int samples = 200;
        for (int i = 0; i < samples; i++) {
            clearLock.invoke(null);
            tickField.setLong(null, i * 100L);
            float[] probe = {45.0F, 10.0F};
            apply.invoke(null, Double.valueOf(distance), probe);
            double dy = Math.abs(probe[0] - 45.0F);
            double dp = Math.abs(probe[1] - 10.0F);
            if (dy > 1.0E-4D || dp > 1.0E-4D) {
                offCentre++;
            }
            if (dy <= halfYaw + 1.0E-4D && dp <= halfPitch + 1.0E-4D) {
                insideBox++;
            }
        }
        check("default jitter never lands dead centre (" + offCentre + "/" + samples + ")",
                offCentre == samples);
        check("default jitter stays inside the head hitbox (" + insideBox + "/" + samples + ")",
                insideBox == samples);

        // ---- 4. 锁定期间恒定，过刷新周期才重滚 ----
        clearLock.invoke(null);
        tickField.setLong(null, 1000L);
        float[] first = {45.0F, 10.0F};
        apply.invoke(null, Double.valueOf(distance), first);
        float firstYaw = offsetYaw.getFloat(null);
        float firstPitch = offsetPitch.getFloat(null);
        tickField.setLong(null, 1010L);
        float[] second = {45.0F, 10.0F};
        apply.invoke(null, Double.valueOf(distance), second);
        check("offset is stable while the target is locked",
                offsetYaw.getFloat(null) == firstYaw && offsetPitch.getFloat(null) == firstPitch);

        tickField.setLong(null, 1000L + 40L);
        float[] third = {45.0F, 10.0F};
        apply.invoke(null, Double.valueOf(distance), third);
        boolean rerolled = Math.abs(offsetYaw.getFloat(null) - firstYaw) > 1.0E-6D
                || Math.abs(offsetPitch.getFloat(null) - firstPitch) > 1.0E-6F;
        check("offset is rerolled after the refresh window", rerolled);

        // ---- 5. 强度 1 也不会越出命中盒 ----
        setJitter.invoke(null, Float.valueOf(1.0F));
        int outside = 0;
        for (int i = 0; i < samples; i++) {
            clearLock.invoke(null);
            tickField.setLong(null, 5000L + i * 100L);
            float[] probe = {45.0F, 10.0F};
            apply.invoke(null, Double.valueOf(distance), probe);
            double dy = Math.abs(probe[0] - 45.0F);
            double dp = Math.abs(probe[1] - 10.0F);
            double radius = Math.hypot(dy / halfYaw, dp / halfPitch);
            if (radius > 1.0001D) {
                outside++;
            }
        }
        check("strength 1 still fits the hitbox (" + outside + " outside)", outside == 0);

        // ---- 6. 近距离同样按比例收敛 ----
        setJitter.invoke(null, Float.valueOf(1.0F));
        clearLock.invoke(null);
        tickField.setLong(null, 20000L);
        float[] near = {45.0F, 10.0F};
        apply.invoke(null, Double.valueOf(1.0D), near);
        double nearHalfYaw = Math.toDegrees(Math.atan2(0.30D, 1.0D));
        check("near target keeps the same relative spread",
                Math.abs(near[0] - 45.0F) <= nearHalfYaw + 1.0E-4D);

        setJitter.invoke(null, Float.valueOf(0.35F));

        // ==============================================================
        // 7. Grim AimModulo360：相邻两包的 yaw delta 不能 >320
        //    判据（Grim 源码）：|yaw < 360| 且 |deltaXRot| > 320 且上一 tick < 30
        // ==============================================================
        Method advance = type.getDeclaredMethod("advanceYaw", float.class, float.class);
        advance.setAccessible(true);
        Method continuous = type.getDeclaredMethod("continuousYaw", float.class, float.class);
        continuous.setAccessible(true);
        Method wrap = type.getDeclaredMethod("wrapDegrees", float.class);
        wrap.setAccessible(true);

        // 跨 +180 的正向转向：连续累加后 delta 只有 1.2，而不是 358.8
        float yaw = 179.4F;
        float step = 1.2F;
        float next = ((Float) advance.invoke(null, Float.valueOf(yaw), Float.valueOf(step))).floatValue();
        float delta = Math.abs(next - yaw);
        check("crossing +180 keeps the delta small (" + delta + "°)", delta <= 1.5F);
        float wrappedNext = ((Float) wrap.invoke(null, Float.valueOf(next))).floatValue();
        check("crossing +180 keeps the same heading (" + wrappedNext + "°)",
                next > 180.0F && Math.abs(wrappedNext + 179.4F) < 1.0E-2F);

        // 跨 -180 的反向转向同理
        float yawDown = -179.4F;
        float nextDown = ((Float) advance.invoke(null,
                Float.valueOf(yawDown), Float.valueOf(-1.2F))).floatValue();
        check("crossing -180 keeps the delta small",
                Math.abs(nextDown - yawDown) <= 1.5F && nextDown < -180.0F);

        // 从 -179 瞄向 +179 的目标：最短路径写法应给出 180.6 附近，而不是 358 的跳变
        float target = 179.5F;
        float from = -178.9F;
        float resolved = ((Float) continuous.invoke(null,
                Float.valueOf(from), Float.valueOf(target))).floatValue();
        check("continuous target stays within 180° (" + Math.abs(resolved - from) + "°)",
                Math.abs(resolved - from) <= 180.0F + 1.0E-4F);
        check("continuous target keeps the same heading",
                Math.abs(((Float) wrap.invoke(null,
                        Float.valueOf(resolved - target))).floatValue()) < 1.0E-3F);

        // 长时间单侧旋转：连续累加可以越过 ±360，这正是 Grim 那个 check 的豁免区
        float accumulated = 350.0F;
        accumulated = ((Float) advance.invoke(null,
                Float.valueOf(accumulated), Float.valueOf(20.0F))).floatValue();
        check("yaw is allowed past +360 (Grim exempts |yaw| > 360)", accumulated > 360.0F);
        check("no abrupt jump while passing 360", Math.abs(accumulated - 350.0F) <= 20.0F + 1.0E-4F);

        // 反向也要一样
        float back = ((Float) continuous.invoke(null,
                Float.valueOf(179.0F), Float.valueOf(-179.0F))).floatValue();
        check("continuous target handles the -180 side",
                Math.abs(back - 179.0F) <= 2.0F + 1.0E-4F);

        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        if (failed > 0) {
            System.exit(1);
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
