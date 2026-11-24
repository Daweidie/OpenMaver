import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** Drives real target selection and lock updates without starting Minecraft. */
public final class AimTargetTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        set("aimEnabled", true);
        set("aimRequireClick", false);
        set("aimJitter", 0.0F);
        set("aimRange", 6.0F);
        selection();
        lockUpdates();
        System.out.println("Aim target checks passed: " + checks);
    }

    private static void selection() throws Exception {
        Minecraft mc = fixture();
        Local self = (Local) mc.thePlayer;
        Player player = new Player(4);
        mc.theWorld.loadedEntityList.add(player);
        ArmorStand stand = new ArmorStand(1);
        mc.theWorld.loadedEntityList.add(stand);
        tick(mc);
        check(target() == player, "A nearer armor stand must not steal the player's aim lock");

        stand.invisible = true;
        set("aimLockedTarget", stand);
        tick(mc);
        check(target() == player, "An existing lock on an invisible hologram stand is released");

        Player hidden = new Player(2);
        hidden.invisible = true;
        mc.theWorld.loadedEntityList.add(hidden);
        invoke("clearAimLock");
        tick(mc);
        check(target() == player, "Invisible players are excluded during selection");

        Mob mob = new Mob(2);
        mob.invisible = true;
        mc.theWorld.loadedEntityList.add(mob);
        invoke("clearAimLock");
        tick(mc);
        check(target() == player, "Invisible mobs are excluded during selection");

        hidden.invisible = false;
        self.blocked.add(hidden);
        invoke("clearAimLock");
        tick(mc);
        check(target() == player, "A player behind a solid block cannot acquire the lock");

        mob.invisible = false;
        invoke("clearAimLock");
        tick(mc);
        check(target() == mob, "Visible mobs remain valid targets");

        self.blocked.add(mob);
        player.invisible = true;
        tick(mc);
        check(target() == null && !hasTarget(), "Only hidden entities or stands leaves no aim target");
    }

    private static void lockUpdates() throws Exception {
        Minecraft mc = fixture();
        Local self = (Local) mc.thePlayer;
        Player near = new Player(2);
        Player far = new Player(4);
        mc.theWorld.loadedEntityList.add(near);
        mc.theWorld.loadedEntityList.add(far);
        tick(mc);
        check(target() == near, "A visible nearby player acquires the lock");

        near.invisible = true;
        tick(mc);
        check(target() == far, "A player becoming invisible loses the lock on the next tick");

        near.invisible = false;
        self.blocked.add(far);
        tick(mc);
        check(target() == near, "A player moving behind a block loses the lock on the next tick");

        mc.theWorld.loadedEntityList.remove(near);
        tick(mc);
        check(target() == null && !hasTarget(), "A removed entity cannot keep a stale aim lock");

        self.blocked.clear();
        tick(mc);
        check(target() == far, "A target can be acquired again once it is visible");

        mc.theWorld.loadedEntityList = null;
        tick(mc);
        check(target() == null && !hasTarget(), "Unavailable entity data releases the lock");
    }

    private static Minecraft fixture() throws Exception {
        invoke("clearAimLock");
        Minecraft mc = new Minecraft();
        mc.theWorld = new World();
        mc.thePlayer = new Local();
        mc.theWorld.loadedEntityList.add(mc.thePlayer);
        return mc;
    }

    private static void tick(Minecraft mc) throws Exception {
        Method method = OpenMaverFeatures.class.getDeclaredMethod("updateAimTarget", Minecraft.class);
        method.setAccessible(true);
        method.invoke(null, mc);
    }

    private static Object target() throws Exception { return get("aimLockedTarget"); }
    private static boolean hasTarget() throws Exception { return (Boolean) get("aimHasTarget"); }
    private static Object get(String name) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
    private static void set(String name, Object value) throws Exception {
        Field field = OpenMaverFeatures.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static void invoke(String name) throws Exception {
        Method method = OpenMaverFeatures.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static final class Local extends EntityPlayerSP {
        final Set<Entity> blocked = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        @Override public float getEyeHeight() { return 1.62F; }
        @Override public double getDistanceSqToEntity(Entity entity) {
            double dx = entity.posX - posX;
            double dy = entity.posY - posY;
            double dz = entity.posZ - posZ;
            return dx * dx + dy * dy + dz * dz;
        }
        @Override public boolean canEntityBeSeen(Entity entity) { return !blocked.contains(entity); }
    }
    public static final class Player extends EntityPlayer {
        boolean invisible;
        Player(double z) { posZ = z; height = 1.8F; }
        @Override public boolean isInvisibleToPlayer(EntityPlayer player) { return invisible; }
    }
    public static final class Mob extends EntityLivingBase {
        boolean invisible;
        Mob(double z) { posZ = z; height = 1.8F; }
        @Override public boolean isInvisibleToPlayer(EntityPlayer player) { return invisible; }
    }
    public static final class ArmorStand extends EntityArmorStand {
        boolean invisible;
        ArmorStand(double z) { posZ = z; height = 1.975F; }
        @Override public boolean isInvisibleToPlayer(EntityPlayer player) { return invisible; }
    }
}
