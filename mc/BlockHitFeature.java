import java.lang.reflect.Field;
import java.util.Properties;

import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Real 1.8.9 sword use: controller sends C08 and, on release, C07. */
public final class BlockHitFeature {
    private static int holdTicks = 2;
    private static int tick;
    private static int releaseTick;
    private static AttackEntityEvent pending;
    private static Object pendingWorld, pendingStack;
    private static Object owner, ownerWorld, controller, stack, binding;
    private static Object opponent, combatPlayer, combatWorld, combatStack;
    private static int lastAttackTick, unansweredHits, targetHurt, playerHurt, playerResistance;
    private static double targetHealth, playerHealth;
    private static boolean swinging, swingUsed;
    private static int swingProgress;
    private static boolean warned;

    private BlockHitFeature() { }

    public static int getHoldTicks() { return holdTicks; }
    public static void setHoldTicks(int value) {
        holdTicks = Math.max(1, Math.min(5, value));
        OpenMaverFeatures.markDirty();
    }

    public static void load(Properties values) {
        try {
            holdTicks = Math.max(1, Math.min(5,
                    Integer.parseInt(values.getProperty("blockhit.holdTicks", "2"))));
        } catch (NumberFormatException ignored) {
            holdTicks = 2;
        }
    }

    public static String save() { return "blockhit.holdTicks=" + holdTicks + "\n"; }

    /** Record only; an event may still be canceled by a later Forge subscriber. */
    public static void onAttack(Minecraft mc, AttackEntityEvent event) {
        if (!enabled() || !available(mc) || event.entityPlayer != mc.thePlayer
                || !(event.target instanceof EntityLivingBase)
                || event.target == mc.thePlayer || AntiBotFeature.isBot(event.target)) return;
        Object held = held(mc.thePlayer);
        if (!sword(held) || (using(mc.thePlayer) && owner != mc.thePlayer)) return;
        pending = event;
        pendingWorld = world(mc);
        pendingStack = held;
    }

    /** Release before any automated attacks and vanilla input handling. */
    public static void beforeTick(Minecraft mc) {
        tick++;
        if (!enabled() || !available(mc) || building()) {
            clearPending();
            clearCombat();
            release(mc, false);
            return;
        }
        if (opponent != null && (combatPlayer != mc.thePlayer || combatWorld != world(mc)
                || combatStack != held(mc.thePlayer) || tick - lastAttackTick > 20
                || !OpenMaverFeatures.alive(opponent))) clearCombat();
        observeCombat(mc);
        if (owner == null) return;
        if (owner != mc.thePlayer || ownerWorld != world(mc) || stack != held(owner)
                || controller != read(mc, "playerController", "field_71442_b")
                || binding != useBinding(mc) || !using(owner)
                || itemInUse(owner) != stack) {
            release(mc, false);
        } else if (physicalDown(binding)) {
            // A real use-key press transfers the sword to the player.
            release(mc, true);
        } else if (tick >= releaseTick || !threat(mc, false)) {
            release(mc, false);
        } else if (!setPressed(binding, true)) {
            release(mc, false);
        }
    }

    /** Called after module attacks, still before vanilla's C03 movement packet. */
    public static void afterTickStart(Minecraft mc) {
        AttackEntityEvent attack = pending;
        Object world = pendingWorld, attackedWith = pendingStack;
        clearPending();
        if (!enabled() || !available(mc) || building()) return;
        if (attack != null && attack.entityPlayer == mc.thePlayer && world == world(mc)
                && attackedWith == held(mc.thePlayer) && sword(attackedWith)
                && !Boolean.TRUE.equals(OpenMaverFeatures.call(attack, "isCanceled", null, null))
                && OpenMaverFeatures.alive(attack.target)) {
            if (opponent != attack.target || combatPlayer != mc.thePlayer || combatWorld != world
                    || combatStack != attackedWith) {
                clearCombat();
                opponent = attack.target;
                combatPlayer = mc.thePlayer;
                combatWorld = world;
                combatStack = attackedWith;
                targetHurt = integer(opponent, "hurtTime", "field_70737_aN");
                playerHurt = integer(combatPlayer, "hurtTime", "field_70737_aN");
                playerResistance = integer(combatPlayer, "hurtResistantTime", "field_70172_ad");
                targetHealth = health(opponent);
                playerHealth = health(combatPlayer);
            }
            lastAttackTick = tick;
        }
        observeCombat(mc);
        if (owner != null) {
            if (!threat(mc, false)) release(mc, false);
            return;
        }
        if (!threat(mc, true) || using(mc.thePlayer)) return;
        Object use = useBinding(mc);
        // Preserve manual right-clicking and other modules' ownership of use.
        if (use == null || physicalDown(use)
                || Boolean.TRUE.equals(read(use, "pressed", "field_74513_e"))) return;
        Object pc = read(mc, "playerController", "field_71442_b");
        if (pc == null || !setPressed(use, true)) return;
        owner = mc.thePlayer;
        ownerWorld = world(mc);
        controller = pc;
        stack = combatStack;
        binding = use;
        releaseTick = tick + holdTicks;
        swingUsed = true;
        // sendUseItem may return false even when it starts use (same ItemStack).
        // Verify the actual player use state instead of that return value.
        boolean invoked = OpenMaverFeatures.callVoid(pc, "sendUseItem", "func_78769_a", null,
                owner, ownerWorld, stack);
        if (!invoked || !using(owner) || itemInUse(owner) != stack) {
            release(mc, false);
            if (!warned) {
                warned = true;
                System.err.println("[OpenMaver] BlockHit: sword use did not start");
            }
        }
    }

    /** Damage acknowledgments, rather than attack clicks, establish a combo. */
    private static void observeCombat(Minecraft mc) {
        if (opponent == null) return;
        int ownHurt = integer(mc.thePlayer, "hurtTime", "field_70737_aN");
        int ownResistance = integer(mc.thePlayer, "hurtResistantTime", "field_70172_ad");
        int hurt = integer(opponent, "hurtTime", "field_70737_aN");
        double ownHealth = health(mc.thePlayer), hp = health(opponent);
        boolean damaged = ownHurt > playerHurt || ownResistance > playerResistance || ownHealth < playerHealth;
        if (damaged) unansweredHits = 0;
        if (tick - lastAttackTick <= 10 && (hurt > targetHurt || hp < targetHealth)) unansweredHits++;
        playerHurt = ownHurt;
        playerResistance = ownResistance;
        targetHurt = hurt;
        playerHealth = ownHealth;
        targetHealth = hp;
        boolean active = Boolean.TRUE.equals(read(opponent, "isSwingInProgress", "field_82175_bq"));
        int progress = integer(opponent, "swingProgressInt", "field_110158_av");
        if (active && (!swinging || progress < swingProgress)) swingUsed = false;
        swinging = active;
        swingProgress = progress;
    }

    /** Conservative counterattack window; a swing is only an observable hint. */
    private static boolean threat(Minecraft mc, boolean starting) {
        if (opponent == null || combatWorld != world(mc) || combatPlayer != mc.thePlayer
                || combatStack != held(mc.thePlayer) || tick - lastAttackTick > 20
                || !OpenMaverFeatures.alive(opponent) || AntiBotFeature.isBot(opponent)
                || unansweredHits >= 2
                // Protect the next offensive hit while its server acknowledgment is in flight.
                || (unansweredHits > 0 && tick - lastAttackTick <= 6)
                || targetHurt > 0 || integer(mc.thePlayer, "hurtTime", "field_70737_aN") > 0
                || integer(mc.thePlayer, "hurtResistantTime", "field_70172_ad") > 10
                || !swinging || swingProgress < 0 || swingProgress > 2
                || (starting && swingUsed) || using(opponent)) return false;
        AxisAlignedBB box = OpenMaverFeatures.boundingBox(mc.thePlayer);
        if (box == null) return false;
        double x = OpenMaverFeatures.number(opponent, "posX", "field_70165_t");
        double y = OpenMaverFeatures.number(opponent, "posY", "field_70163_u") + OpenMaverFeatures.eyeHeight(opponent);
        double z = OpenMaverFeatures.number(opponent, "posZ", "field_70161_v");
        double dx = x - Math.max(box.minX, Math.min(box.maxX, x));
        double dy = y - Math.max(box.minY, Math.min(box.maxY, y));
        double dz = z - Math.max(box.minZ, Math.min(box.maxZ, z));
        if (dx * dx + dy * dy + dz * dz > 9.0D) return false;
        double toward = Math.toDegrees(Math.atan2((box.minZ + box.maxZ) * 0.5D - z,
                (box.minX + box.maxX) * 0.5D - x)) - 90.0D;
        float yaw = (float) OpenMaverFeatures.number(opponent, "rotationYaw", "field_70177_z");
        if (Math.abs(OpenMaverFeatures.wrapDegrees((float) toward - yaw)) > 55.0F) return false;
        return Boolean.TRUE.equals(OpenMaverFeatures.call(opponent, "canEntityBeSeen", "func_70685_l", null, mc.thePlayer));
    }

    private static void clearCombat() {
        opponent = combatPlayer = combatWorld = combatStack = null;
        unansweredHits = 0;
        swinging = swingUsed = false;
        swingProgress = 0;
    }

    private static int integer(Object object, String mcp, String srg) {
        return (int) OpenMaverFeatures.number(object, mcp, srg);
    }

    private static double health(Object entity) {
        Object hp = OpenMaverFeatures.call(entity, "getHealth", "func_110143_aJ", null);
        return hp instanceof Number ? ((Number) hp).doubleValue() : 0.0D;
    }

    private static void release(Minecraft mc, boolean manual) {
        if (owner == null) return;
        boolean sameWorld = mc != null && mc.thePlayer == owner && world(mc) == ownerWorld;
        boolean handOver = manual && sameWorld && available(mc) && physicalDown(binding);
        setPressed(binding, sameWorld && available(mc) && physicalDown(binding));
        if (!handOver && using(owner) && itemInUse(owner) == stack) {
            if (sameWorld && world(mc) != null
                    && controller == read(mc, "playerController", "field_71442_b")) {
                // Vanilla stops client use and sends RELEASE_USE_ITEM to the server.
                OpenMaverFeatures.callVoid(controller, "onStoppedUsingItem", "func_78766_c", null, owner);
            } else {
                // Never send an old world's release into a new connection.
                OpenMaverFeatures.callVoid(owner, "stopUsingItem", "func_71034_by", null);
            }
        }
        owner = ownerWorld = controller = stack = binding = null;
    }

    private static void clearPending() { pending = null; pendingWorld = pendingStack = null; }

    private static boolean enabled() {
        Module module = ModuleManager.getModule("BlockHit");
        return module != null && module.isEnabled();
    }

    private static boolean available(Minecraft mc) {
        return mc != null && mc.thePlayer != null && world(mc) != null
                && mc.currentScreen == null && OpenMaverFeatures.alive(mc.thePlayer)
                && !Boolean.FALSE.equals(read(mc, "inGameHasFocus", "field_71415_G"));
    }

    private static boolean building() {
        for (String name : new String[] {"Scaffold", "Scaffold2", "Telly", "GodBridge"}) {
            Module module = ModuleManager.getModule(name);
            if (module != null && module.isEnabled()) return true;
        }
        return false;
    }

    private static Object held(Object player) {
        return OpenMaverFeatures.call(player, "getHeldItem", "func_70694_bm", null);
    }

    private static Object itemInUse(Object player) {
        return OpenMaverFeatures.call(player, "getItemInUse", "func_71011_bu", null);
    }

    private static boolean using(Object player) {
        return Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", null));
    }

    private static boolean sword(Object held) {
        Object item = OpenMaverFeatures.call(held, "getItem", "func_77973_b", null);
        for (Class<?> type = item == null ? null : item.getClass(); type != null; type = type.getSuperclass()) {
            if ("net.minecraft.item.ItemSword".equals(type.getName())) return true;
        }
        return false;
    }

    private static Object useBinding(Minecraft mc) {
        return read(read(mc, "gameSettings", "field_71474_y"), "keyBindUseItem", "field_74313_G");
    }

    private static boolean physicalDown(Object key) {
        Object value = OpenMaverFeatures.call(key, "getKeyCode", "func_151463_i", null);
        if (!(value instanceof Number)) return false;
        int code = ((Number) value).intValue();
        return code < 0 ? code >= -100 && Mouse.isButtonDown(code + 100)
                : code > 0 && code < 256 && Keyboard.isKeyDown(code);
    }

    private static boolean setPressed(Object key, boolean value) {
        Field field = OpenMaverFeatures.findField(key, new String[] {"pressed", "field_74513_e"});
        if (field == null) return false;
        try { field.setBoolean(key, value); return true; }
        catch (IllegalAccessException ignored) { return false; }
    }

    // The runtime field is WorldClient; the compile-only stub uses World.
    private static Object world(Minecraft mc) {
        return read(mc, "theWorld", "field_71441_e");
    }

    private static Object read(Object object, String mcp, String srg) {
        return OpenMaverFeatures.read(object, mcp, srg, null);
    }
}
