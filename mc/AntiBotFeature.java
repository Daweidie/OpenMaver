import com.mojang.authlib.GameProfile;
import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Tick-based player classification shared by automatic combat and client overlays. */
public final class AntiBotFeature {
    private static boolean tabCheck = true;
    private static boolean nameCheck;
    private static boolean filterRender = true;
    private static int checkDelay = 40;
    private static final Map<EntityPlayer, Observation> observations =
            new IdentityHashMap<EntityPlayer, Observation>();
    private static Object lastWorld;
    private static Object lastPlayer;
    private static Object lastConnection;
    private static boolean failureLogged;

    private AntiBotFeature() {}

    public static void load(Properties values) {
        tabCheck = Boolean.parseBoolean(values.getProperty("antibot.tabCheck", "true"));
        nameCheck = Boolean.parseBoolean(values.getProperty("antibot.nameCheck", "false"));
        filterRender = Boolean.parseBoolean(values.getProperty("antibot.filterRender", "true"));
        try {
            checkDelay = clampDelay(Integer.parseInt(values.getProperty("antibot.checkDelay", "40")));
        } catch (NumberFormatException ignored) {
            checkDelay = 40;
        }
        reset();
    }

    public static String save() {
        return "antibot.tabCheck=" + tabCheck + "\nantibot.nameCheck=" + nameCheck
                + "\nantibot.filterRender=" + filterRender + "\nantibot.checkDelay=" + checkDelay + "\n";
    }

    public static boolean isTabCheck() { return tabCheck; }
    public static void setTabCheck(boolean value) {
        tabCheck = value;
        changed();
    }
    public static boolean isNameCheck() { return nameCheck; }
    public static void setNameCheck(boolean value) {
        nameCheck = value;
        changed();
    }
    public static boolean isFilterRender() { return filterRender; }
    public static void setFilterRender(boolean value) {
        filterRender = value;
        OpenMaverFeatures.markDirty();
    }
    public static int getCheckDelay() { return checkDelay; }
    public static void setCheckDelay(int value) {
        checkDelay = clampDelay(value);
        changed();
    }

    private static int clampDelay(int value) { return Math.max(0, Math.min(100, value)); }
    private static void changed() {
        reset();
        OpenMaverFeatures.markDirty();
    }

    /** Consumers perform a constant-time lookup; no network lookup in render/attack loops. */
    public static boolean isBot(Object entity) {
        if (!(entity instanceof EntityPlayer) || entity == lastPlayer || !enabled()) return false;
        Observation state = observations.get(entity);
        return state != null && state.bot;
    }

    public static boolean shouldFilterRender(Object entity) {
        return filterRender && isBot(entity);
    }

    public static int getBotCount() {
        if (!enabled()) return 0;
        int count = 0;
        for (Observation state : observations.values()) if (state.bot) count++;
        return count;
    }

    private static boolean enabled() {
        Module module = ModuleManager.getModule("AntiBot");
        return module != null && module.isEnabled();
    }

    /** Called once at tick START, before selecting targets or completing deferred attacks. */
    public static void onTick(Minecraft mc) {
        if (!enabled() || mc == null) {
            reset();
            return;
        }
        try {
            Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
            Object self = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
            Object connection = OpenMaverFeatures.call(mc, "getNetHandler", "func_147114_u", null);
            if (world == null || !(self instanceof EntityPlayer)) {
                reset();
                return;
            }
            if (world != lastWorld || self != lastPlayer || connection != lastConnection) {
                reset();
                lastWorld = world;
                lastPlayer = self;
                lastConnection = connection;
            }
            Object loaded = OpenMaverFeatures.read(world, "loadedEntityList", "field_72996_f", List.class);
            if (!(loaded instanceof List<?>)) {
                reset();
                return;
            }
            Set<UUID> tabIds = tabCheck ? tabIds(connection) : null;
            Set<EntityPlayer> present = Collections.newSetFromMap(new IdentityHashMap<EntityPlayer, Boolean>());
            for (Object entity : (List<?>) loaded) {
                if (!(entity instanceof EntityPlayer) || entity == self) continue;
                EntityPlayer player = (EntityPlayer) entity;
                if (!present.add(player)) continue;
                Observation state = observations.get(player);
                if (state == null) {
                    state = new Observation();
                    observations.put(player, state);
                }
                GameProfile profile = profile(player, "getGameProfile", "func_146103_bH");
                // Missing APIs/data are unknown, rather than evidence that every player is a bot.
                boolean suspicious = profile != null && nameCheck && invalidName(profile.getName());
                if (profile != null && profile.getId() != null && tabIds != null
                        && !tabIds.contains(profile.getId())) suspicious = true;
                state.suspectTicks = suspicious ? Math.min(101, state.suspectTicks + 1) : 0;
                state.bot = suspicious && state.suspectTicks >= Math.max(1, checkDelay);
            }
            // Entity identity prevents old verdicts from leaking to a respawn with the same ID/UUID.
            observations.keySet().retainAll(present);
        } catch (Throwable error) {
            reset();
            if (!failureLogged) {
                failureLogged = true;
                System.err.println("[OpenMaver] AntiBot detection unavailable; keeping players: " + error);
            }
        }
    }

    /** A missing, empty or partially unreadable Tab list is not authoritative. */
    private static Set<UUID> tabIds(Object connection) {
        Object entries = OpenMaverFeatures.call(connection, "getPlayerInfoMap", "func_175106_d", null);
        if (!(entries instanceof Collection<?>) || ((Collection<?>) entries).isEmpty()) return null;
        Set<UUID> ids = new HashSet<UUID>();
        for (Object info : (Collection<?>) entries) {
            GameProfile profile = profile(info, "getGameProfile", "func_178845_a");
            if (profile == null || profile.getId() == null) return null;
            ids.add(profile.getId());
        }
        return ids;
    }

    private static GameProfile profile(Object owner, String mcp, String srg) {
        Object value = OpenMaverFeatures.call(owner, mcp, srg, null);
        return value instanceof GameProfile ? (GameProfile) value : null;
    }

    /** Optional strict Java-edition name check; disabled for servers with custom/offline names. */
    private static boolean invalidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) return true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_') return true;
        }
        return false;
    }

    private static void reset() {
        observations.clear();
        lastWorld = lastPlayer = lastConnection = null;
    }

    private static final class Observation {
        int suspectTicks;
        boolean bot;
    }
}
