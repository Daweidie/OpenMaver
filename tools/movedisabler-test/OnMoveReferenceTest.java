/** Compiled alongside the actual reference OnMoveCheck, with minimal Bukkit API doubles. */
public final class OnMoveReferenceTest {
    public static void main(String[] args) {
        Player player = new Player();
        OnMoveCheck check = new OnMoveCheck(new InventoryClickAnalysis());
        MovementMetadata motion = player.user.meta().movement();
        motion.dx = .24;
        motion.keyForward = 1;
        InventoryClickEvent walking = new InventoryClickEvent(player);
        check.receiveWindowClick(walking);
        require(walking.cancelled && player.closed == 1 && Modules.processor.lastVL == 0,
                "walking click is cancelled even with zero VL");
        motion.keyForward = 0;
        InventoryClickEvent coasting = new InventoryClickEvent(player);
        check.receiveWindowClick(coasting);
        require(!coasting.cancelled && motion.dx == .24, "neutral-input inertia permits click without zeroing distance");
        motion.keyStrafe = 1;
        InventoryClickEvent overtaken = new InventoryClickEvent(player);
        check.receiveWindowClick(overtaken);
        require(overtaken.cancelled, "later inferred input invalidates the same click snapshot");
        motion.flyingGrace = true;
        InventoryClickEvent grace = new InventoryClickEvent(player);
        check.receiveWindowClick(grace);
        require(!grace.cancelled, "genuine flying uncertainty is a separate exemption");
        motion.flyingGrace = false;
        motion.dx = .08;
        InventoryClickEvent slow = new InventoryClickEvent(player);
        check.receiveWindowClick(slow);
        require(!slow.cancelled, "ordinary distance threshold");
        player.sneaking = true;
        InventoryClickEvent sneak = new InventoryClickEvent(player);
        check.receiveWindowClick(sneak);
        require(sneak.cancelled, "sneaking distance threshold");
        System.out.println("OnMoveReferenceTest: 6 passed, 0 failed (reference predicate only; no Physics/Bukkit runtime)");
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}

// Dependency doubles only. OnMoveCheck's body comes verbatim from references/.
@interface BukkitEventSubscription { }
class InventoryClickAnalysis { }
class IntavePlugin { static IntavePlugin singletonInstance() { return new IntavePlugin(); } }
class CheckPart<T> {
    CheckPart(T parent) { }
    User userOf(Player player) { return player.user; }
}
class User {
    final MetadataBundle data = new MetadataBundle();
    MetadataBundle meta() { return data; }
}
class MetadataBundle {
    final MovementMetadata data = new MovementMetadata();
    MovementMetadata movement() { return data; }
}
class MovementMetadata {
    int keyForward, keyStrafe;
    boolean inWeb, flyingGrace;
    double dx, dz;
    Object simulator() { return null; }
    boolean receivedFlyingPacketIn(int ticks) { return flyingGrace; }
    double offsetMotionX() { return dx; }
    double offsetMotionZ() { return dz; }
}
class Simulators { static final Object ELYTRA = new Object(); }
class Hypot { static double fast(double x, double z) { return Math.hypot(x, z); } }
interface HumanEntity { }
class Player implements HumanEntity {
    final User user = new User();
    int closed;
    boolean sneaking;
    Player getPlayer() { return this; }
    boolean isSneaking() { return sneaking; }
    void closeInventory() { closed++; }
}
enum ClickType { CREATIVE, LEFT }
class InventoryClickEvent {
    final Player player;
    boolean cancelled;
    InventoryClickEvent(Player player) { this.player = player; }
    HumanEntity getWhoClicked() { return player; }
    ClickType getClick() { return ClickType.LEFT; }
    void setCancelled(boolean value) { cancelled = value; }
}
class Synchronizer { static void synchronize(User user, Runnable task) { task.run(); } }
class Violation {
    double vl;
    static Violation builderFor(Class<?> type) { return new Violation(); }
    Violation forPlayer(Player player) { return this; }
    Violation withMessage(String message) { return this; }
    Violation withVL(double value) { vl = value; return this; }
    Violation build() { return this; }
}
class Modules {
    static final Processor processor = new Processor();
    static Processor violationProcessor() { return processor; }
    static class Processor {
        double lastVL;
        void processViolation(Violation violation) { lastVL = violation.vl; }
    }
}
