import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

/** Kill attribution comes from server death messages, never from entity removal. */
public final class TauntFeature {
    private static final String NAME = "([A-Za-z0-9_]{1,16})";
    private static final Pattern ENGLISH = Pattern.compile("^" + NAME
            + " (?:was (?:slain|killed|shot|fireballed|pummelled) by|was (?:thrown|knocked) into the void by) "
            + NAME + "(?: using \\[.*\\])?[.!]?(?: FINAL KILL!)?$");
    private static final Pattern CHINESE = Pattern.compile("^" + NAME + "\\s*被\\s*" + NAME
            + "\\s*(?:击杀|击败|杀死|射杀|推入虚空|击落虚空)[！!。.]?$");
    private static final Map<String, Integer> knownPlayers = new HashMap<String, Integer>();
    private static final Map<String, Integer> deaths = new HashMap<String, Integer>();
    private static final ArrayDeque<String> pending = new ArrayDeque<String>();
    private static Object world, player;
    private static int tick, nextSendTick;

    private TauntFeature() { }

    public static void onTick(Minecraft mc) {
        tick++;
        if (!context(mc)) return;
        rememberPlayers(mc);
        expire(knownPlayers, 200);
        expire(deaths, 40);
        if (pending.isEmpty() || tick < nextSendTick) return;
        String victim = pending.removeFirst();
        String message = MaverPersonalization.cleanTaunt(
                MaverPersonalization.getTauntText().replace("{target}", victim)).trim();
        if (message.isEmpty()) return;
        OpenMaverFeatures.callVoid(mc.thePlayer, "sendChatMessage", "func_71165_d", null, message);
        nextSendTick = tick + 20;
    }

    public static void onChat(Minecraft mc, ClientChatReceivedEvent event) {
        if (event == null || !context(mc)
                || Boolean.TRUE.equals(OpenMaverFeatures.call(event, "isCanceled", null, null))) return;
        Object type = read(event, "type", null);
        // 2 is the action bar, rather than a chat/death announcement.
        if (!(type instanceof Number) || ((Number) type).intValue() == 2) return;
        rememberPlayers(mc);
        Object component = read(event, "message", null);
        String victim = translatedKill(mc, component);
        if (victim == null) {
            // Do not flatten ordinary player-chat translations into apparent kill announcements.
            Object key = OpenMaverFeatures.call(component, "getKey", "func_150268_i", null);
            if (key != null) return;
            victim = textKill(mc, plain(component));
        }
        if (victim == null) return;
        String key = victim.toLowerCase(Locale.ROOT);
        Integer previous = deaths.get(key);
        if (previous != null && tick - previous < 40) return;
        deaths.put(key, tick);
        pending.addLast(victim);
    }

    private static String translatedKill(Minecraft mc, Object component) {
        Object key = OpenMaverFeatures.call(component, "getKey", "func_150268_i", null);
        Object args = OpenMaverFeatures.call(component, "getFormatArgs", "func_150271_j", null);
        if (!(key instanceof String) || !((String) key).startsWith("death.attack.")
                || !(args instanceof Object[]) || ((Object[]) args).length < 2) return null;
        Object[] parts = (Object[]) args;
        // Death translations have victim first, attacker second, optional weapon third.
        String victim = nameFromComponent(mc, parts[0]);
        String killer = plain(parts[1]);
        String own = name(mc.thePlayer);
        Object display = OpenMaverFeatures.call(mc.thePlayer, "getDisplayName", "func_145748_c_", null);
        if (!killer.equals(own) && !killer.equals(plain(display))) return null;
        return victim != null && !victim.equalsIgnoreCase(own) ? victim : null;
    }

    private static String textKill(Minecraft mc, String text) {
        Matcher matcher = ENGLISH.matcher(text);
        if (!matcher.matches()) {
            matcher = CHINESE.matcher(text);
            if (!matcher.matches()) return null;
        }
        String victim = matcher.group(1), killer = matcher.group(2);
        String own = name(mc.thePlayer);
        return killer.equalsIgnoreCase(own) && !victim.equalsIgnoreCase(own) && known(victim) ? victim : null;
    }

    private static String nameFromComponent(Minecraft mc, Object component) {
        String value = plain(component);
        if (known(value)) return value;
        Object players = read(world(mc), "playerEntities", "field_73010_i");
        if (players instanceof Iterable<?>) {
            for (Object entity : (Iterable<?>) players) {
                if (!(entity instanceof EntityPlayer)) continue;
                Object display = OpenMaverFeatures.call(entity, "getDisplayName", "func_145748_c_", null);
                if (value.equals(plain(display))) return name(entity);
            }
        }
        return null;
    }

    private static boolean known(String name) {
        Integer seen = knownPlayers.get(name.toLowerCase(Locale.ROOT));
        return seen != null && tick - seen <= 200;
    }

    private static void rememberPlayers(Minecraft mc) {
        Object players = read(world(mc), "playerEntities", "field_73010_i");
        if (players instanceof Iterable<?>) {
            for (Object entity : (Iterable<?>) players) {
                if (entity instanceof EntityPlayer) remember(name(entity));
            }
        }
        Object connection = OpenMaverFeatures.call(mc, "getNetHandler", "func_147114_u", null);
        Object entries = OpenMaverFeatures.call(connection, "getPlayerInfoMap", "func_175106_d", null);
        if (entries instanceof Iterable<?>) {
            for (Object entry : (Iterable<?>) entries) {
                Object profile = OpenMaverFeatures.call(entry, "getGameProfile", "func_178845_a", null);
                Object name = OpenMaverFeatures.call(profile, "getName", null, null);
                if (name instanceof String) remember((String) name);
            }
        }
    }

    private static void remember(String name) {
        if (name.matches("[A-Za-z0-9_]{1,16}")) knownPlayers.put(name.toLowerCase(Locale.ROOT), tick);
    }

    private static boolean context(Minecraft mc) {
        if (mc == null || world(mc) == null || mc.thePlayer == null || !MaverPersonalization.isTauntEnabled()) {
            reset();
            return false;
        }
        if (world != world(mc) || player != mc.thePlayer) {
            reset();
            world = world(mc);
            player = mc.thePlayer;
        }
        return true;
    }

    private static void reset() {
        world = player = null;
        knownPlayers.clear();
        deaths.clear();
        pending.clear();
        nextSendTick = tick;
    }

    private static void expire(Map<String, Integer> values, int age) {
        Iterator<Map.Entry<String, Integer>> it = values.entrySet().iterator();
        while (it.hasNext()) if (tick - it.next().getValue() >= age) it.remove();
    }

    private static String name(Object entity) {
        Object name = OpenMaverFeatures.call(entity, "getName", "func_70005_c_", null);
        return name instanceof String ? (String) name : "";
    }

    private static String plain(Object component) {
        Object value = component instanceof String ? component
                : OpenMaverFeatures.call(component, "getUnformattedText", "func_150260_c", null);
        return value instanceof String ? ((String) value).replaceAll("§.", "").trim() : "";
    }

    // The runtime field is WorldClient; the compile-only stub uses World.
    private static Object world(Minecraft mc) {
        return read(mc, "theWorld", "field_71441_e");
    }

    private static Object read(Object object, String mcp, String srg) {
        return OpenMaverFeatures.read(object, mcp, srg, null);
    }
}
