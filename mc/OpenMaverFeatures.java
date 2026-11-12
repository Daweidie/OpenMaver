import module.Module;
import module.ModuleManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** Minecraft-side aim and living-entity outline features. */
public final class OpenMaverFeatures {

    private static boolean aimEnabled;
    private static boolean espEnabled;
    private static int settingsCheckTicks;
    private static long settingsModified = Long.MIN_VALUE;
    private static boolean settingsDirty;

    // Aim Assist（数值范围与 GUI 滑块一致）
    private static float aimSpeed = 0.35F;
    private static float aimRange = 6.0F;
    private static float aimFov = 180.0F;
    private static int aimMode = 0;       // 0 = Smooth, 1 = Instant
    private static int aimTarget = 0;     // 0 = Head, 1 = Chest, 2 = Body
    private static boolean aimRequireClick = true;

    // Player ESP
    private static float espRange = 48.0F;
    private static float espThickness = 1.5F;
    private static float espOpacity = 0.9F;
    private static int espColor = 0;      // 0 = Orange, 1 = Cyan, 2 = Violet, 3 = Red
    private static boolean espPlayersOnly;

    // AutoSprint
    private static boolean sprintOnlyForward = true;
    private static boolean sprintPauseSneak = true;
    private static boolean sprintPauseItem = true;

    private static final float[][] ESP_COLORS = {
        {1.0F, 0.42F, 0.22F},
        {0.36F, 0.88F, 1.0F},
        {0.69F, 0.55F, 1.0F},
        {1.0F, 0.35F, 0.40F}
    };

    private OpenMaverFeatures() {
    }

    public static void onTick(Minecraft mc) {
        refreshSettings();
        if (read(mc, "currentScreen", "field_71462_r", null) != null) {
            return;
        }
        updateAutoSprint(mc);
        if (aimEnabled && (!aimRequireClick || Mouse.isButtonDown(0))) {
            aimAtNearest(mc);
        }
    }

    /**
     * AutoSprint 的真实实现放在 MC 侧：core 里的 AutoSprint 模块只负责开关状态，
     * 因为它可能在独立模式/agent bootstrap 里加载，不能直接引用 net.minecraft 类。
     */
    private static void updateAutoSprint(Minecraft mc) {
        Module autoSprint = ModuleManager.getModule("AutoSprint");
        if (autoSprint == null || !autoSprint.isEnabled()) {
            return;
        }
        Object player = read(mc, "thePlayer", "field_71439_g", null);
        if (player == null) {
            return;
        }
        Object input = read(player, "movementInput", "field_71158_b", null);
        if (input == null) {
            return;
        }
        double forward = number(input, "moveForward", "field_78900_b");
        Object sneak = read(input, "sneak", "field_78899_d", Boolean.class);
        if (sprintOnlyForward && forward <= 0.0D) {
            return;
        }
        if (sprintPauseSneak && Boolean.TRUE.equals(sneak)) {
            return;
        }
        Object usingItem = call(player, "isUsingItem", "func_71039_bw", null);
        if (sprintPauseItem && Boolean.TRUE.equals(usingItem)) {
            return;
        }
        call(player, "setSprinting", "func_70031_b", null, Boolean.TRUE);
    }

    private static void refreshSettings() {
        if (settingsCheckTicks++ < 20) {
            return;
        }
        settingsCheckTicks = 0;
        if (settingsDirty) {
            settingsDirty = false;
            saveSettings();
            return;
        }
        String path = System.getProperty("openmaver.features", "config/openmaver.properties");
        File file = new File(path);
        long modified = file.isFile() ? file.lastModified() : -1L;
        if (modified == settingsModified) {
            return;
        }
        settingsModified = modified;
        Properties values = new Properties();
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) {
                values.load(input);
            } catch (Exception error) {
                System.err.println("[OpenMaver] Cannot read feature settings: " + error);
                return;
            }
        }
        aimEnabled = readBoolean(values, "aim", false);
        espEnabled = readBoolean(values, "esp", false);
        aimSpeed = clamp(readFloat(values, "aim.speed", 0.35F), 0.05F, 1.0F);
        aimRange = clamp(readFloat(values, "aim.range", 6.0F), 3.0F, 12.0F);
        aimFov = clamp(readFloat(values, "aim.fov", 180.0F), 30.0F, 180.0F);
        aimMode = readInt(values, "aim.mode", 0, 1);
        aimTarget = readInt(values, "aim.target", 0, 2);
        aimRequireClick = readBoolean(values, "aim.requireClick", true);
        espRange = clamp(readFloat(values, "esp.range", 48.0F), 16.0F, 128.0F);
        espThickness = clamp(readFloat(values, "esp.thickness", 1.5F), 0.5F, 4.0F);
        espOpacity = clamp(readFloat(values, "esp.opacity", 0.9F), 0.2F, 1.0F);
        espColor = readInt(values, "esp.color", 0, 3);
        espPlayersOnly = readBoolean(values, "esp.playersOnly", false);
        sprintOnlyForward = readBoolean(values, "sprint.onlyForward", true);
        sprintPauseSneak = readBoolean(values, "sprint.pauseSneak", true);
        sprintPauseItem = readBoolean(values, "sprint.pauseItem", true);
    }

    public static boolean isAimEnabled() {
        return aimEnabled;
    }

    public static void toggleAim() {
        aimEnabled = !aimEnabled;
        saveSettings();
    }

    public static boolean isEspEnabled() {
        return espEnabled;
    }

    public static void toggleEsp() {
        espEnabled = !espEnabled;
        saveSettings();
    }

    // ==================================================================
    // GUI 可编辑参数（内存即时生效，最多 1 秒落盘一次）
    // ==================================================================

    public static float getAimSpeed() { return aimSpeed; }
    public static void setAimSpeed(float value) { aimSpeed = clamp(value, 0.05F, 1.0F); markDirty(); }

    public static float getAimRange() { return aimRange; }
    public static void setAimRange(float value) { aimRange = clamp(value, 3.0F, 12.0F); markDirty(); }

    public static float getAimFov() { return aimFov; }
    public static void setAimFov(float value) { aimFov = clamp(value, 30.0F, 180.0F); markDirty(); }

    public static int getAimMode() { return aimMode; }
    public static void setAimMode(int value) { aimMode = clamp(value, 0, 1); markDirty(); }

    public static int getAimTarget() { return aimTarget; }
    public static void setAimTarget(int value) { aimTarget = clamp(value, 0, 2); markDirty(); }

    public static boolean isAimRequireClick() { return aimRequireClick; }
    public static void setAimRequireClick(boolean value) { aimRequireClick = value; markDirty(); }

    public static float getEspRange() { return espRange; }
    public static void setEspRange(float value) { espRange = clamp(value, 16.0F, 128.0F); markDirty(); }

    public static float getEspThickness() { return espThickness; }
    public static void setEspThickness(float value) { espThickness = clamp(value, 0.5F, 4.0F); markDirty(); }

    public static float getEspOpacity() { return espOpacity; }
    public static void setEspOpacity(float value) { espOpacity = clamp(value, 0.2F, 1.0F); markDirty(); }

    public static int getEspColor() { return espColor; }
    public static void setEspColor(int value) { espColor = clamp(value, 0, 3); markDirty(); }

    public static boolean isEspPlayersOnly() { return espPlayersOnly; }
    public static void setEspPlayersOnly(boolean value) { espPlayersOnly = value; markDirty(); }

    public static boolean isSprintOnlyForward() { return sprintOnlyForward; }
    public static void setSprintOnlyForward(boolean value) { sprintOnlyForward = value; markDirty(); }

    public static boolean isSprintPauseSneak() { return sprintPauseSneak; }
    public static void setSprintPauseSneak(boolean value) { sprintPauseSneak = value; markDirty(); }

    public static boolean isSprintPauseItem() { return sprintPauseItem; }
    public static void setSprintPauseItem(boolean value) { sprintPauseItem = value; markDirty(); }

    /** GUI 关闭时立即落盘，避免最后的拖动丢失。 */
    public static void flushSettings() {
        if (settingsDirty) {
            settingsDirty = false;
            saveSettings();
        }
    }

    /** 批量关闭所有 MC 侧功能（供 Panic / 关闭流程使用）。 */
    public static void disableAll() {
        aimEnabled = false;
        espEnabled = false;
        saveSettings();
    }

    private static void markDirty() {
        settingsDirty = true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean readBoolean(Properties values, String key, boolean fallback) {
        String raw = values.getProperty(key);
        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static float readFloat(Properties values, String key, float fallback) {
        String raw = values.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(Properties values, String key, int fallback, int max) {
        String raw = values.getProperty(key);
        int parsed = fallback;
        if (raw != null) {
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(0, Math.min(max, parsed));
    }

    /**
     * 把当前开关写回 openmaver.properties。
     *
     * <p>之前 GUI 里的 toggle 只改内存，20 tick 后 {@link #refreshSettings()} 又从磁盘读回
     * false，所以开关看起来是 ON，实际一秒后就被悄悄关掉了。
     */
    private static void saveSettings() {
        String path = System.getProperty("openmaver.features", "config/openmaver.properties");
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(("aim=" + aimEnabled
                    + "\nesp=" + espEnabled
                    + "\naim.speed=" + aimSpeed
                    + "\naim.range=" + aimRange
                    + "\naim.fov=" + aimFov
                    + "\naim.mode=" + aimMode
                    + "\naim.target=" + aimTarget
                    + "\naim.requireClick=" + aimRequireClick
                    + "\nesp.range=" + espRange
                    + "\nesp.thickness=" + espThickness
                    + "\nesp.opacity=" + espOpacity
                    + "\nesp.color=" + espColor
                    + "\nesp.playersOnly=" + espPlayersOnly
                    + "\nsprint.onlyForward=" + sprintOnlyForward
                    + "\nsprint.pauseSneak=" + sprintPauseSneak
                    + "\nsprint.pauseItem=" + sprintPauseItem
                    + "\n").getBytes("UTF-8"));
        } catch (IOException error) {
            System.err.println("[OpenMaver] Cannot save feature settings: " + error);
            return;
        }
        settingsDirty = false;
        settingsModified = file.lastModified();
    }

    public static void onRender(RenderWorldLastEvent event) {
        if (!espEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Object worldObject = read(mc, "theWorld", "field_71441_e", null);
        Object playerObject = read(mc, "thePlayer", "field_71439_g", null);
        if (!(worldObject instanceof net.minecraft.world.World)
                || !(playerObject instanceof EntityLivingBase)) {
            return;
        }
        net.minecraft.world.World world = (net.minecraft.world.World) worldObject;
        EntityLivingBase player = (EntityLivingBase) playerObject;

        RenderManager renderManager = (RenderManager) read(mc, "renderManager", "field_175616_W", RenderManager.class);
        if (renderManager == null) {
            return;
        }
        List<?> entities = (List<?>) read(world, "loadedEntityList", "field_72996_f", List.class);
        if (entities == null) {
            return;
        }
        GlColor.restore();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT
                | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(espThickness);
        float[] espRgb = ESP_COLORS[Math.max(0, Math.min(ESP_COLORS.length - 1, espColor))];
        GlColor.set(espRgb[0], espRgb[1], espRgb[2], espOpacity);
        GL11.glBegin(GL11.GL_LINES);
        double espRangeSq = espRange * espRange;
        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                continue;
            }
            if (espPlayersOnly && !(candidate instanceof EntityPlayer)) {
                continue;
            }
            EntityLivingBase entity = (EntityLivingBase) candidate;
            if (!alive(entity) || distance(player, entity) > espRangeSq) {
                continue;
            }
            AxisAlignedBB box = boundingBox(entity);
            if (box == null) {
                continue;
            }
            drawBox(box,
                    number(renderManager, "viewerPosX", "field_78725_b"),
                    number(renderManager, "viewerPosY", "field_78726_c"),
                    number(renderManager, "viewerPosZ", "field_78723_d"));
        }
        GL11.glEnd();
        GL11.glPopAttrib();
        GlColor.restore();
    }

    private static void aimAtNearest(Minecraft mc) {
        EntityLivingBase player = (EntityLivingBase) read(mc, "thePlayer", "field_71439_g", null);
        net.minecraft.world.World world = (net.minecraft.world.World) read(mc, "theWorld", "field_71441_e", null);
        if (player == null || world == null) {
            return;
        }
        List<?> entities = (List<?>) read(world, "loadedEntityList", "field_72996_f", List.class);
        if (entities == null) {
            return;
        }
        EntityLivingBase nearest = null;
        double nearestDistance = aimRange * aimRange;
        double yaw = number(player, "rotationYaw", "field_70177_z");
        double pitch = number(player, "rotationPitch", "field_70125_A");
        double playerX = number(player, "posX", "field_70165_t");
        double playerZ = number(player, "posZ", "field_70161_v");
        double targetFraction = aimTarget == 0 ? 0.90D : (aimTarget == 1 ? 0.70D : 0.50D);
        float bestTargetYaw = 0.0F;
        float bestTargetPitch = 0.0F;
        for (int i = 0; i < entities.size(); i++) {
            Object candidate = entities.get(i);
            if (!(candidate instanceof EntityLivingBase) || candidate == player) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) candidate;
            if (!alive(living)) {
                continue;
            }
            double distance = distance(player, living);
            if (distance >= nearestDistance) {
                continue;
            }

            double dx = number(living, "posX", "field_70165_t") - playerX;
            double dz = number(living, "posZ", "field_70161_v") - playerZ;
            double targetY = number(living, "posY", "field_70163_u")
                    + number(living, "height", "field_70131_O") * targetFraction;
            double playerY = number(player, "posY", "field_70163_u") + eyeHeight(player);
            double dy = targetY - playerY;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
            float targetPitch = (float) (-Math.atan2(dy, horizontal) * 180.0D / Math.PI);

            // FOV 限制：只瞄准当前视角半角范围内的目标
            if (aimFov < 179.9F) {
                float half = aimFov * 0.5F;
                if (Math.abs(wrapDegrees(targetYaw - (float) yaw)) > half
                        || Math.abs(wrapDegrees(targetPitch - (float) pitch)) > half) {
                    continue;
                }
            }
            nearestDistance = distance;
            nearest = living;
            bestTargetYaw = targetYaw;
            bestTargetPitch = targetPitch;
        }
        if (nearest == null) {
            return;
        }

        float factor = aimMode == 1 ? 1.0F : aimSpeed;
        setNumber(player, "rotationYaw", "field_70177_z",
                yaw + wrapDegrees(bestTargetYaw - (float) yaw) * factor);
        setNumber(player, "rotationPitch", "field_70125_A",
                pitch + wrapDegrees(bestTargetPitch - (float) pitch) * factor);
    }

    private static Object read(Object target, String mcp, String srg, Class<?> expected) {
        if (target == null) return null;
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field != null) {
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                if (expected == null || value == null || expected.isInstance(value)) return value;
            } catch (Throwable ignored) {
                // Fall through to the type based fallback below.
            }
        }
        // 只在调用方明确给出期望类型时才做类型兜底；expected == null 时返回任意字段
        // 会读到完全不相干的成员（例如把 renderViewEntity 当成别的对象）。
        if (expected == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                if (!expected.isAssignableFrom(candidate.getType())) continue;
                try {
                    candidate.setAccessible(true);
                    Object value = candidate.get(target);
                    if (value == null || expected.isInstance(value)) return value;
                } catch (Throwable ignored) {
                    // Continue searching without affecting the game loop.
                }
            }
        }
        return null;
    }

    /** 沿继承链查找字段。getDeclaredField 不会搜索父类，之前因此读不到 posX/rotationYaw。 */
    private static Field findField(Object target, String[] names) {
        if (target == null) return null;
        for (String name : names) {
            if (name == null) continue;
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                    // Try the superclass, then the next runtime name.
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean alive(Object entity) {
        Object value = call(entity, "isEntityAlive", "func_70089_S", "E");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static double distance(Object entity, Object other) {
        Object value = call(entity, "getDistanceSqToEntity", "func_70068_e", "e", other);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.MAX_VALUE;
    }

    private static AxisAlignedBB boundingBox(Object entity) {
        Object value = call(entity, "getEntityBoundingBox", "func_174813_aQ", "aQ");
        return value instanceof AxisAlignedBB ? (AxisAlignedBB) value : null;
    }

    private static double eyeHeight(Object entity) {
        Object value = call(entity, "getEyeHeight", "func_70047_e", "bB");
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0D;
    }

    private static Object call(Object target, String mcp, String srg, String obf, Object... args) {
        if (target == null) return null;
        for (String name : new String[] {mcp, srg, obf}) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                    if (!parametersMatch(method.getParameterTypes(), args)) continue;
                    try {
                        method.setAccessible(true);
                        return method.invoke(target, args);
                    } catch (Throwable ignored) {
                        // Try another mapping or inherited declaration.
                    }
                }
            }
        }
        return null;
    }

    private static boolean parametersMatch(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (!parameterMatches(types[i], args[i])) return false;
        }
        return true;
    }

    private static boolean parameterMatches(Class<?> type, Object arg) {
        if (arg == null) return !type.isPrimitive();
        if (!type.isPrimitive()) return type.isInstance(arg);
        if (type == Boolean.TYPE) return arg instanceof Boolean;
        if (type == Byte.TYPE) return arg instanceof Byte;
        if (type == Character.TYPE) return arg instanceof Character;
        if (type == Short.TYPE) return arg instanceof Short;
        if (type == Integer.TYPE) return arg instanceof Integer;
        if (type == Long.TYPE) return arg instanceof Long;
        if (type == Float.TYPE) return arg instanceof Float;
        if (type == Double.TYPE) return arg instanceof Double;
        return false;
    }

    private static String[] runtimeCandidates(String mcp, String srg) {
        if ("field_71441_e".equals(srg)) return new String[] {mcp, srg, "f"};
        if ("field_71439_g".equals(srg)) return new String[] {mcp, srg, "i"};
        if ("field_71462_r".equals(srg)) return new String[] {mcp, srg, "h"};
        if ("field_72996_f".equals(srg)) return new String[] {mcp, srg, "f"};
        if ("field_70165_t".equals(srg)) return new String[] {mcp, srg, "p"};
        if ("field_70163_u".equals(srg)) return new String[] {mcp, srg, "q"};
        if ("field_70161_v".equals(srg)) return new String[] {mcp, srg, "r"};
        if ("field_70131_O".equals(srg)) return new String[] {mcp, srg, "U"};
        if ("field_70177_z".equals(srg)) return new String[] {mcp, srg, "y"};
        if ("field_70125_A".equals(srg)) return new String[] {mcp, srg, "z"};
        if ("field_72340_a".equals(srg)) return new String[] {mcp, srg, "a"};
        if ("field_72338_b".equals(srg)) return new String[] {mcp, srg, "b"};
        if ("field_72339_c".equals(srg)) return new String[] {mcp, srg, "c"};
        if ("field_72336_d".equals(srg)) return new String[] {mcp, srg, "d"};
        if ("field_72337_e".equals(srg)) return new String[] {mcp, srg, "e"};
        if ("field_72334_f".equals(srg)) return new String[] {mcp, srg, "f"};
        return new String[] {mcp, srg};
    }

    private static double number(Object target, String mcp, String srg) {
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) return 0.0D;
        try {
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Number ? ((Number) value).doubleValue() : 0.0D;
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    private static void setNumber(Object target, String mcp, String srg, double value) {
        Field field = findField(target, runtimeCandidates(mcp, srg));
        if (field == null) return;
        try {
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == Float.TYPE) field.setFloat(target, (float) value);
            else if (type == Double.TYPE) field.setDouble(target, value);
            else if (type == Integer.TYPE) field.setInt(target, (int) value);
            else if (type == Long.TYPE) field.setLong(target, (long) value);
        } catch (Throwable ignored) {
            // Never let a reflection mismatch break the game loop.
        }
    }

    private static void drawBox(AxisAlignedBB box, double cameraX, double cameraY, double cameraZ) {
        double x1 = number(box, "minX", "field_72340_a") - cameraX;
        double y1 = number(box, "minY", "field_72338_b") - cameraY;
        double z1 = number(box, "minZ", "field_72339_c") - cameraZ;
        double x2 = number(box, "maxX", "field_72336_d") - cameraX;
        double y2 = number(box, "maxY", "field_72337_e") - cameraY;
        double z2 = number(box, "maxZ", "field_72334_f") - cameraZ;
        line(x1, y1, z1, x2, y1, z1);
        line(x2, y1, z1, x2, y1, z2);
        line(x2, y1, z2, x1, y1, z2);
        line(x1, y1, z2, x1, y1, z1);
        line(x1, y2, z1, x2, y2, z1);
        line(x2, y2, z1, x2, y2, z2);
        line(x2, y2, z2, x1, y2, z2);
        line(x1, y2, z2, x1, y2, z1);
        line(x1, y1, z1, x1, y2, z1);
        line(x2, y1, z1, x2, y2, z1);
        line(x2, y1, z2, x2, y2, z2);
        line(x1, y1, z2, x1, y2, z2);
    }

    private static void line(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }
}
