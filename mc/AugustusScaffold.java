import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Normal's independent Augustus scaffold controller.
 *
 * <p>Source: ../OpenAugustus-Maver/.../world/Scaffold_47Te.java:
 * method_48$f2eea116cc (support probes), method_44$85a266d04d (movement yaw),
 * method_43$e45a9973e5 (pitch scan), method_43$0d3917b489 (pitch selection).
 * Native rotation helpers are adapted to bounded acceleration and relative mouse steps.
 * ScaffoldFeature supplies only inventory, input hooks and the C03 -> C08 transport.
 */
final class AugustusScaffold {
    static final String[] PITCH_SELECTIONS = {
            "NEAREST", "FURTHEST", "HIGHEST", "LOWEST", "MID", "RANDOM"
    };
    static final Settings SETTINGS = new Settings();
    private static final int[][] DELTAS = {
            {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    static final class Range {
        final double min, max, defaultLow, defaultHigh;
        double low, high;

        Range(double min, double max, double low, double high) {
            this.min = min;
            this.max = max;
            this.defaultLow = this.low = low;
            this.defaultHigh = this.high = high;
        }

        void setLow(double value) {
            low = Math.min(high, bounded(value, min, max, defaultLow));
            OpenMaverFeatures.markDirty();
        }

        void setHigh(double value) {
            high = Math.max(low, bounded(value, min, max, defaultHigh));
            OpenMaverFeatures.markDirty();
        }

        double sample() {
            return low == high ? low : ThreadLocalRandom.current().nextDouble(low, high);
        }

        void load(Properties properties, String name) {
            low = read(properties, name + "Min", defaultLow, min, max);
            high = Math.max(low, read(properties, name + "Max", defaultHigh, min, max));
        }

        String save(String name) {
            return name + "Min=" + low + "\n" + name + "Max=" + high + "\n";
        }
    }

    static final class Settings {
        final Range yawSpeed = new Range(0, 180, 30, 50);
        final Range pitchSpeed = new Range(0, 180, 30, 50);
        final Range yawAcceleration = new Range(1, 180, 7, 15);
        final Range pitchAcceleration = new Range(1, 180, 7, 15);
        final Range cps = new Range(0, 40, 10, 20);
        int pitchSelection;
        double maxPitchCorrection = 180;
        boolean extraBlockSearch = true;
        boolean alwaysPlace = true;
        boolean forcePlace = true;
        boolean noSwing;

        void load(Properties properties) {
            yawSpeed.load(properties, "scaffold.normal.yawSpeed");
            pitchSpeed.load(properties, "scaffold.normal.pitchSpeed");
            yawAcceleration.load(properties, "scaffold.normal.yawAcceleration");
            pitchAcceleration.load(properties, "scaffold.normal.pitchAcceleration");
            cps.load(properties, "scaffold.normal.cps");
            pitchSelection = (int) read(properties, "scaffold.normal.pitchSelection", 0, 0, 5);
            maxPitchCorrection = read(properties, "scaffold.normal.maxPitchCorrection", 180, 0, 180);
            extraBlockSearch = bool(properties, "extraBlockSearch", true);
            alwaysPlace = bool(properties, "alwaysPlace", true);
            forcePlace = bool(properties, "forcePlace", true);
            noSwing = bool(properties, "noSwing", false);
        }

        String save() {
            return yawSpeed.save("scaffold.normal.yawSpeed")
                    + pitchSpeed.save("scaffold.normal.pitchSpeed")
                    + yawAcceleration.save("scaffold.normal.yawAcceleration")
                    + pitchAcceleration.save("scaffold.normal.pitchAcceleration")
                    + cps.save("scaffold.normal.cps")
                    + "scaffold.normal.pitchSelection=" + pitchSelection
                    + "\nscaffold.normal.maxPitchCorrection=" + maxPitchCorrection
                    + "\nscaffold.normal.extraBlockSearch=" + extraBlockSearch
                    + "\nscaffold.normal.alwaysPlace=" + alwaysPlace
                    + "\nscaffold.normal.forcePlace=" + forcePlace
                    + "\nscaffold.normal.noSwing=" + noSwing + "\n";
        }
    }

    static final class Target {
        final int x, y, z, face;
        final double hitX, hitY, hitZ;

        Target(int x, int y, int z, int face, double hitX, double hitY, double hitZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.face = face;
            this.hitX = hitX;
            this.hitY = hitY;
            this.hitZ = hitZ;
        }

        boolean sameBlock(Target other) {
            return other != null && x == other.x && y == other.y && z == other.z;
        }
    }

    static final class Aim {
        final float yaw, pitch;
        final Target target;

        Aim(float yaw, float pitch, Target target) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.target = target;
        }
    }

    private float yawVelocity, pitchVelocity;
    private long nextClickNanos;

    void reset() {
        yawVelocity = pitchVelocity = 0;
        nextClickNanos = 0;
    }

    Aim update(Object player, Object world, float cameraYaw, float cameraPitch,
            float sentYaw, float sentPitch, int forward, int strafe, double reach,
            double mouseStep, boolean hasBlocks) {
        if (!hasBlocks) {
            reset();
            return new Aim(cameraYaw, cameraPitch, null);
        }
        Scene scene = new Scene(player, world, reach);
        Target support = findSupport(scene, cameraYaw, forward, strafe);
        if (support == null) {
            reset();
            return new Aim(cameraYaw, cameraPitch, null);
        }

        float fromYaw = Float.isNaN(sentYaw) ? cameraYaw : sentYaw;
        float fromPitch = Float.isNaN(sentPitch) ? cameraPitch : sentPitch;
        float wantedYaw = movementYaw(cameraYaw, forward, strafe) - 180.0F;
        if (forward == 0 && strafe == 0) wantedYaw = fromYaw;
        boolean visibleFace = canSeePlaceableFace(scene, support);
        Aim wanted = visibleFace ? scanPitch(scene, support,
                quantize(fromYaw, wantedYaw, mouseStep), fromPitch, mouseStep) : null;
        if (wanted == null && visibleFace && SETTINGS.extraBlockSearch) {
            // Augustus tries alternating yaw offsets when the movement yaw cannot hit.
            for (int offset = 1; offset <= 180 && wanted == null; offset++) {
                wanted = scanPitch(scene, support, quantize(fromYaw, wantedYaw + offset, mouseStep), fromPitch, mouseStep);
                if (wanted == null) {
                    wanted = scanPitch(scene, support, quantize(fromYaw, wantedYaw - offset, mouseStep), fromPitch, mouseStep);
                }
            }
        }
        if (wanted == null) {
            // Augustus' default rotation prepares the backward aim before reaching an edge.
            wanted = new Aim(wantedYaw, 80.34F, null);
        }

        float yawDelta = ScaffoldFeature.wrapDegrees(wanted.yaw - fromYaw);
        float pitchDelta = wanted.pitch - fromPitch;
        double yawSpeed = SETTINGS.yawSpeed.sample();
        yawVelocity = accelerate(yawVelocity, yawDelta, yawSpeed,
                SETTINGS.yawAcceleration.sample());
        pitchVelocity = accelerate(pitchVelocity, pitchDelta, SETTINGS.pitchSpeed.sample(),
                SETTINGS.pitchAcceleration.sample());
        float yaw = quantize(fromYaw, fromYaw + yawVelocity, mouseStep);
        float pitch = ScaffoldFeature.clampPitch(quantize(fromPitch,
                fromPitch + pitchVelocity, mouseStep));
        scene.predictSteering(yaw, cameraYaw, forward, strafe);
        Target hit = scene.ray(scene.eyeX, scene.eyeY, scene.eyeZ, yaw, pitch, reach);
        if (!validClick(scene, hit, support) && canSeePlaceableFace(scene, support)) {
            // Augustus scans again after smoothing and applies MaxPitchCorrection
            // relative to the previous server pitch (180 degrees by default).
            Aim correction = scanPitch(scene, support, yaw, fromPitch, mouseStep);
            if (correction != null) {
                double limit = SETTINGS.maxPitchCorrection;
                float corrected = (float) Math.max(fromPitch - limit,
                        Math.min(fromPitch + limit, correction.pitch));
                pitch = ScaffoldFeature.clampPitch(quantize(fromPitch, corrected, mouseStep));
                pitchVelocity = 0;
                hit = scene.ray(scene.eyeX, scene.eyeY, scene.eyeZ, yaw, pitch, reach);
            }
        }
        if (!validClick(scene, hit, support) && SETTINGS.extraBlockSearch) {
            // Recheck additional yaw angles using the movement each angle will cause.
            // A corner solution based on the previous movement can disappear once
            // MoveFix steers this tick's acceleration in the new direction.
            search:
            for (int offset = 1; offset <= 180; offset++) {
                for (int sign = 1; sign >= -1; sign -= 2) {
                    float candidateYaw = quantize(fromYaw, yaw + sign * offset, mouseStep);
                    if (Math.abs(ScaffoldFeature.wrapDegrees(candidateYaw - fromYaw))
                            > yawSpeed + mouseStep * .5D) continue;
                    scene.predictSteering(candidateYaw, cameraYaw, forward, strafe);
                    if (!canSeePlaceableFace(scene, support)) continue;
                    Aim correction = scanPitch(scene, support, candidateYaw, fromPitch, mouseStep);
                    if (correction == null) continue;
                    double limit = SETTINGS.maxPitchCorrection;
                    float corrected = quantize(fromPitch, (float) Math.max(fromPitch - limit,
                            Math.min(fromPitch + limit, correction.pitch)), mouseStep);
                    Target candidate = scene.ray(scene.eyeX, scene.eyeY, scene.eyeZ,
                            candidateYaw, corrected, reach);
                    if (!validClick(scene, candidate, support)) continue;
                    yaw = candidateYaw;
                    pitch = corrected;
                    hit = candidate;
                    yawVelocity = pitchVelocity = 0;
                    break search;
                }
            }
        }
        if (!validClick(scene, hit, support)) hit = null;
        return new Aim(yaw, pitch, hit);
    }

    /** Prefer a support behind the predicted feet, then sweep the surrounding layers. */
    private static Target findSupport(Scene scene, float cameraYaw, int forward, int strafe) {
        double dx = scene.motionX * 2.0D;
        double dz = scene.motionZ * 2.0D;
        if (Math.hypot(dx, dz) < 1.0E-6D) {
            Target below = belowSupport(scene);
            if (below != null) return below;
        }
        if (Math.hypot(dx, dz) < 1.0E-6D && (forward != 0 || strafe != 0)) {
            double movement = Math.toRadians(movementYaw(cameraYaw, forward, strafe));
            dx = -Math.sin(movement) * 0.2D;
            dz = Math.cos(movement) * 0.2D;
        }
        double probeX = scene.x + dx;
        double probeZ = scene.z + dz;
        float reverseYaw = (float) Math.toDegrees(Math.atan2(-dz, -dx)) - 90.0F;
        Target nearest = null;
        for (double depth = 0.5D; depth < 3.0D; depth++) {
            Target hit = scene.ray(probeX, scene.y - depth, probeZ, reverseYaw, 0, 1.0D);
            nearest = nearer(scene, nearest, hit);
        }
        // At a diagonal corner the reverse ray can still hit the older block while
        // the freshly extended neighbour is closer. Include the surrounding probes.
        boolean corner = nearest != null
                && (scene.eyeX < nearest.x || scene.eyeX > nearest.x + 1)
                && (scene.eyeZ < nearest.z || scene.eyeZ > nearest.z + 1);
        if (nearest != null && (!SETTINGS.extraBlockSearch || !corner)) return nearest;
        for (double depth = 0.5D; depth < 4.0D; depth++) {
            double vertical = ScaffoldFeature.floor(scene.y - depth) + .5D - scene.y;
            if (nearest != null && vertical * vertical > distanceSq(scene, nearest)) continue;
            for (int offset = 0; offset < 360; offset++) {
                Target hit = scene.ray(probeX, scene.y - depth, probeZ,
                        reverseYaw + offset, 0, scene.reach);
                nearest = nearer(scene, nearest, hit);
            }
        }
        if (nearest != null) return nearest;
        return belowSupport(scene);
    }

    private static Target belowSupport(Scene scene) {
        for (int depth = 1; depth < 4; depth++) {
            int x = ScaffoldFeature.floor(scene.x);
            int y = ScaffoldFeature.floor(scene.y - depth);
            int z = ScaffoldFeature.floor(scene.z);
            if (ScaffoldFeature.isSolidSupport(scene.world, x, y, z)) {
                return new Target(x, y, z, 1, x + 0.5D, y + 1.0D, z + 0.5D);
            }
        }
        return null;
    }

    private static Target nearer(Scene scene, Target current, Target candidate) {
        if (candidate == null
                || !ScaffoldFeature.isSolidSupport(scene.world, candidate.x, candidate.y, candidate.z)
                || !ScaffoldFeature.isAir(scene.world, candidate.x, candidate.y + 1, candidate.z)) {
            return current;
        }
        return current == null || distanceSq(scene, candidate) < distanceSq(scene, current)
                ? candidate : current;
    }

    private static double distanceSq(Scene scene, Target target) {
        double dx = target.x + 0.5D - scene.x;
        double dy = target.y + 0.5D - scene.y;
        double dz = target.z + 0.5D - scene.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean canSeePlaceableFace(Scene scene, Target support) {
        return (scene.eyeX < support.x && canPlace(scene.world, support.x, support.y, support.z, 4))
                || (scene.eyeX > support.x + 1 && canPlace(scene.world, support.x, support.y, support.z, 5))
                || (scene.eyeZ < support.z && canPlace(scene.world, support.x, support.y, support.z, 2))
                || (scene.eyeZ > support.z + 1 && canPlace(scene.world, support.x, support.y, support.z, 3))
                || (scene.y - support.y > 2 && canPlace(scene.world, support.x, support.y, support.z, 1));
    }

    /** Original scan: 15 <= pitch < 90, step 0.04, mouse-quantized distinct angles. */
    private static Aim scanPitch(Scene scene, Target support, float yaw,
            float referencePitch, double mouseStep) {
        if (scene.y - support.y <= 2.0D && scene.eyeX >= support.x
                && scene.eyeX <= support.x + 1 && scene.eyeZ >= support.z
                && scene.eyeZ <= support.z + 1) return null; // Only the forbidden top is visible.
        // Reject yaw directions that cannot intersect the support's horizontal bounds.
        double[] origin = {scene.eyeX, scene.eyeZ};
        double[] direction = {-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw))};
        int[] base = {support.x, support.z};
        double near = 0, far = scene.reach;
        for (int axis = 0; axis < 2; axis++) {
            if (Math.abs(direction[axis]) < 1.0E-10D) {
                if (origin[axis] < base[axis] || origin[axis] > base[axis] + 1) return null;
            } else {
                double a = (base[axis] - origin[axis]) / direction[axis];
                double b = (base[axis] + 1 - origin[axis]) / direction[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (far < near) return null;
            }
        }
        // Restrict the same scan to the support's projected bounds before ray tracing.
        double horizontalNear = Math.hypot(Math.max(0, Math.max(support.x - scene.eyeX,
                scene.eyeX - support.x - 1)), Math.max(0, Math.max(support.z - scene.eyeZ,
                scene.eyeZ - support.z - 1)));
        double horizontalFar = Math.hypot(Math.max(Math.abs(support.x - scene.eyeX),
                Math.abs(support.x + 1 - scene.eyeX)), Math.max(Math.abs(support.z - scene.eyeZ),
                Math.abs(support.z + 1 - scene.eyeZ)));
        double minPitch = Math.max(15, Math.toDegrees(Math.atan2(
                scene.eyeY - support.y - 1, horizontalFar)) - mouseStep);
        double maxPitch = Math.min(90, Math.toDegrees(Math.atan2(
                scene.eyeY - support.y, horizontalNear)) + mouseStep);
        List<Aim> candidates = new ArrayList<Aim>();
        float previousPitch = Float.NaN;
        double first = 15 + Math.max(0, Math.floor((minPitch - 15) / 0.04D)) * 0.04D;
        for (double raw = first; raw < maxPitch; raw += 0.04D) {
            float pitch = quantize(referencePitch, (float) raw, mouseStep);
            if (pitch == previousPitch || pitch >= 90) continue;
            previousPitch = pitch;
            Target hit = scene.ray(scene.eyeX, scene.eyeY, scene.eyeZ, yaw, pitch, scene.reach);
            if (validClick(scene, hit, support)) candidates.add(new Aim(yaw, pitch, hit));
        }
        if (candidates.isEmpty()) return null;
        if (SETTINGS.pitchSelection == 5) {
            return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        }
        Aim selected = candidates.get(0);
        for (Aim candidate : candidates) {
            if (pitchScore(candidate, referencePitch, SETTINGS.pitchSelection)
                    < pitchScore(selected, referencePitch, SETTINGS.pitchSelection)) selected = candidate;
        }
        return selected;
    }

    static double pitchScore(Aim aim, float referencePitch, int selection) {
        switch (selection) {
            case 1: return -Math.abs(aim.pitch - referencePitch);
            case 2: return -aim.pitch;
            case 3: return aim.pitch;
            case 4: return Math.abs(0.5D - (aim.target.hitY - aim.target.y));
            default: return Math.abs(aim.pitch - referencePitch);
        }
    }

    private static boolean validClick(Scene scene, Target hit, Target support) {
        return hit != null && hit.sameBlock(support) && hit.face != 0
                && canPlace(scene.world, hit.x, hit.y, hit.z, hit.face)
                && (hit.face != 1 || scene.y - hit.y > 2.0D);
    }

    static boolean canPlace(Object world, int x, int y, int z, int face) {
        if (face < 1 || face >= DELTAS.length
                || !ScaffoldFeature.isSolidSupport(world, x, y, z)) return false;
        int[] delta = DELTAS[face];
        return ScaffoldFeature.isAir(world, x + delta[0], y + delta[1], z + delta[2]);
    }

    boolean clickReady(Object player, Object world) {
        int x = ScaffoldFeature.floor(OpenMaverFeatures.number(player, "posX", "field_70165_t")
                + OpenMaverFeatures.number(player, "motionX", "field_70159_w"));
        int y = ScaffoldFeature.floor(OpenMaverFeatures.number(player, "posY", "field_70163_u") - 0.5D);
        int z = ScaffoldFeature.floor(OpenMaverFeatures.number(player, "posZ", "field_70161_v")
                + OpenMaverFeatures.number(player, "motionZ", "field_70179_y"));
        return SETTINGS.alwaysPlace || (SETTINGS.cps.high > 0 && System.nanoTime() >= nextClickNanos)
                || (SETTINGS.forcePlace && ScaffoldFeature.isAir(world, x, y, z));
    }

    void placed() {
        double cps = SETTINGS.cps.sample();
        nextClickNanos = cps > 0 ? System.nanoTime() + (long) (1_000_000_000D / cps) : Long.MAX_VALUE;
    }

    static float movementYaw(float cameraYaw, int forward, int strafe) {
        if (forward < 0) cameraYaw += 180;
        float diagonal = forward < 0 ? -0.5F : forward > 0 ? 0.5F : 1;
        if (strafe > 0) cameraYaw -= 90 * diagonal;
        if (strafe < 0) cameraYaw += 90 * diagonal;
        return cameraYaw;
    }

    static float accelerate(float velocity, float delta, double speed, double acceleration) {
        // A newly selected face can reverse the requested turn. Restart that axis
        // instead of carrying momentum away from the new click interval.
        if (Math.signum(velocity) != Math.signum(delta)) velocity = 0;
        float desired = (float) Math.max(-speed, Math.min(speed, delta));
        float next = velocity + (float) Math.max(-acceleration,
                Math.min(acceleration, desired - velocity));
        if (delta == 0 || Math.signum(next) != Math.signum(delta)) return 0;
        return Math.copySign(Math.min(Math.abs(delta), Math.abs(next)), delta);
    }

    static float quantize(float base, float target, double step) {
        if (step <= 0 || !Double.isFinite(step)) return target;
        return base + (float) (Math.round((target - base) / step) * step);
    }

    /** Double coordinates avoid float eye-position truncation on distant bridges. */
    static Target ray(Object world, double x, double y, double z, float yaw, float pitch, double reach) {
        double yawRad = Math.toRadians(yaw), pitchRad = Math.toRadians(pitch);
        Object result = OpenMaverFeatures.call(world, "rayTraceBlocks", "func_72933_a", null,
                ScaffoldFeature.vec3(x, y, z), ScaffoldFeature.vec3(
                        x - Math.sin(yawRad) * Math.cos(pitchRad) * reach,
                        y - Math.sin(pitchRad) * reach,
                        z + Math.cos(yawRad) * Math.cos(pitchRad) * reach));
        if (result == null || !"BLOCK".equals(String.valueOf(OpenMaverFeatures.read(
                result, "typeOfHit", "field_72313_a", null)))) return null;
        Object pos = OpenMaverFeatures.call(result, "getBlockPos", "func_178782_a", null);
        if (pos == null) pos = OpenMaverFeatures.read(result, "blockPos", "field_178783_e", null);
        Object hit = OpenMaverFeatures.read(result, "hitVec", "field_72307_f", null);
        int face = ScaffoldFeature.facingIndex(OpenMaverFeatures.read(
                result, "sideHit", "field_178784_b", null));
        if (pos == null || hit == null || face < 0) return null;
        return new Target(coordinate(pos, "getX", "func_177958_n", "x", "field_177962_a"),
                coordinate(pos, "getY", "func_177956_o", "y", "field_177960_b"),
                coordinate(pos, "getZ", "func_177952_p", "z", "field_177961_c"), face,
                OpenMaverFeatures.number(hit, "xCoord", "field_72450_a"),
                OpenMaverFeatures.number(hit, "yCoord", "field_72448_b"),
                OpenMaverFeatures.number(hit, "zCoord", "field_72449_c"));
    }

    private static int coordinate(Object pos, String getter, String srg, String field, String srgField) {
        Object value = OpenMaverFeatures.call(pos, getter, srg, null);
        return value instanceof Number ? ((Number) value).intValue()
                : (int) OpenMaverFeatures.number(pos, field, srgField);
    }

    private static final class Scene {
        final Object world;
        final double x, y, z, motionX, motionZ, reach, acceleration;
        double eyeX, eyeY, eyeZ;

        Scene(Object player, Object world, double reach) {
            this.world = world;
            this.reach = reach;
            x = OpenMaverFeatures.number(player, "posX", "field_70165_t");
            y = OpenMaverFeatures.number(player, "posY", "field_70163_u");
            z = OpenMaverFeatures.number(player, "posZ", "field_70161_v");
            motionX = OpenMaverFeatures.number(player, "motionX", "field_70159_w");
            motionZ = OpenMaverFeatures.number(player, "motionZ", "field_70179_y");
            // START sees motion after friction. The last completed displacement includes
            // vanilla input acceleration and predicts the next eye more accurately.
            double dx = displacement(player, "lastTickPosX", "field_70142_S", x, motionX, reach);
            double dz = displacement(player, "lastTickPosZ", "field_70136_U", z, motionZ, reach);
            double dy = OpenMaverFeatures.number(player, "motionY", "field_70181_x");
            acceleration = Math.hypot(dx - motionX, dz - motionZ);
            eyeX = x + dx;
            eyeY = y + OpenMaverFeatures.eyeHeight(player) + dy;
            eyeZ = z + dz;
        }

        void predictSteering(float aimYaw, float cameraYaw, int forward, int strafe) {
            if (forward == 0 && strafe == 0 || acceleration <= 0 || acceleration > .5D) return;
            float angle = ScaffoldFeature.wrapDegrees(movementYaw(cameraYaw, forward, strafe) - aimYaw + 22.5F);
            int sector = ((int) (angle + 180) / 45) % 8;
            double direction = Math.toRadians(aimYaw + 180 + sector * 45);
            // Reuse measured acceleration, but steer it in the same snapped direction
            // as the input hook. Tiny yaw corrections otherwise change a corner face.
            eyeX = x + motionX - Math.sin(direction) * acceleration;
            eyeZ = z + motionZ + Math.cos(direction) * acceleration;
        }

        private static double displacement(Object player, String field, String srg,
                double position, double motion, double reach) {
            Object previous = OpenMaverFeatures.read(player, field, srg, null);
            if (!(previous instanceof Number)) return motion;
            double delta = position - ((Number) previous).doubleValue();
            return Double.isFinite(delta) && Math.abs(delta) <= reach ? delta : motion;
        }

        Target ray(double x, double y, double z, float yaw, float pitch, double distance) {
            return AugustusScaffold.ray(world, x, y, z, yaw, pitch, distance);
        }
    }

    private static double bounded(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static double read(Properties properties, String key, double fallback, double min, double max) {
        try {
            return bounded(Double.parseDouble(properties.getProperty(key, "")), min, max, fallback);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean bool(Properties properties, String name, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty("scaffold.normal." + name,
                Boolean.toString(fallback)));
    }
}
