import module.Module;
import module.ModuleManager;
import net.minecraft.util.MovementInput;

/** Owns the handoff between manual block interaction and KeepSprint movement yaw. */
public final class KeepSprintPlacement {
    private static final float TURN_STEP = 20.0F;
    private static Object player, world;
    private static long tick, requestedUntil = Long.MIN_VALUE;
    private static float offset;
    private static boolean handoff, ready, deferredClick;
    private static Object deferredStack;
    private static int deferredSlot;
    private static String status = "";

    private KeepSprintPlacement() { }

    public static void reset() {
        player = world = null;
        tick = 0;
        requestedUntil = Long.MIN_VALUE;
        offset = 0;
        handoff = ready = deferredClick = false;
        deferredStack = null;
        status = "";
    }

    private static boolean context(Object mc, Object currentPlayer) {
        Object currentWorld = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        if (player != currentPlayer || world != currentWorld) {
            reset();
            player = currentPlayer;
            world = currentWorld;
            // A previous rotation owner may have just released the player.
            Object reported = OpenMaverFeatures.read(currentPlayer, "lastReportedYaw", "field_175164_bL", null);
            if (reported instanceof Number) offset = ((Number) reported).floatValue()
                    - (float) OpenMaverFeatures.number(currentPlayer, "rotationYaw", "field_70177_z");
        }
        return currentPlayer != null && currentWorld != null;
    }

    private static boolean enabled() {
        Module module = ModuleManager.getModule("KeepSprint");
        return module != null && module.isEnabled() && KeepSprintFeature.isRotate();
    }

    private static boolean usable(Object mc, Object currentPlayer) {
        return enabled() && currentPlayer != null && OpenMaverFeatures.alive(currentPlayer)
                && OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) == null
                && !Boolean.FALSE.equals(OpenMaverFeatures.read(mc, "inGameHasFocus", "field_71415_G", null))
                && !KeepSprintFeature.rotationBusy()
                && !AttackReduceFeature.ownsMovementInput(currentPlayer,
                        OpenMaverFeatures.read(currentPlayer, "movementInput", "field_71158_b", null));
    }

    private static boolean useDown(Object mc) {
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object key = OpenMaverFeatures.read(settings, "keyBindUseItem", "field_74313_G", null);
        return Boolean.TRUE.equals(OpenMaverFeatures.call(key, "isKeyDown", "func_151470_d", null))
                || Boolean.TRUE.equals(OpenMaverFeatures.read(key, "pressed", "field_74513_e", null));
    }

    private static void request() {
        requestedUntil = tick + 2; // Keep the placement's following native movement at camera yaw.
        if (!handoff) {
            handoff = true;
            ready = aligned();
        }
    }

    private static boolean aligned() {
        return Math.abs(offset) < 0.001F;
    }

    private static Object heldStack(Object currentPlayer) {
        return OpenMaverFeatures.call(currentPlayer, "getHeldItem", "func_70694_bm", "bA");
    }

    private static int slot(Object currentPlayer) {
        Object inventory = OpenMaverFeatures.read(currentPlayer, "inventory", "field_71071_by", null);
        return (int) OpenMaverFeatures.number(inventory, "currentItem", "field_70461_c");
    }

    private static boolean sameDeferredItem(Object currentPlayer) {
        return heldStack(currentPlayer) == deferredStack && slot(currentPlayer) == deferredSlot;
    }

    /** Entry guard runs BEFORE vanilla mutates cooldown, swings, or sends interaction packets. */
    public static boolean allowRightClick(Object mc) {
        Object currentPlayer = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (!context(mc, currentPlayer) || !usable(mc, currentPlayer)
                || !OpenMaverFeatures.holdingBlock(currentPlayer)) {
            deferredClick = false;
            return true;
        }
        request();
        if (!ready || !aligned()) {
            deferredClick = true;
            deferredStack = heldStack(currentPlayer);
            deferredSlot = slot(currentPlayer);
            return false;
        }
        deferredClick = false;
        return true;
    }

    static void onPlayerTickStart(Object mc, Object currentPlayer) {
        if (!context(mc, currentPlayer) || !usable(mc, currentPlayer)) {
            reset();
            return;
        }
        tick++;
        if (OpenMaverFeatures.holdingBlock(currentPlayer) && useDown(mc)) request();
        // A short tap must survive the turn, but never survive a slot/world/GUI change.
        if (deferredClick) {
            if (OpenMaverFeatures.holdingBlock(currentPlayer) && sameDeferredItem(currentPlayer)) request();
            else deferredClick = false;
        }
    }

    /** Retry a released, deferred tap at ClientTick START, before the native movement packet. */
    static void onClientTickStart(Object mc) {
        if (!deferredClick) return;
        Object currentPlayer = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (!context(mc, currentPlayer) || !usable(mc, currentPlayer)
                || !OpenMaverFeatures.holdingBlock(currentPlayer) || !sameDeferredItem(currentPlayer)) {
            deferredClick = false;
            return;
        }
        if (!ready || useDown(mc)) return; // Held input is still dispatched by vanilla.
        Object settings = OpenMaverFeatures.read(mc, "gameSettings", "field_71474_y", null);
        Object key = OpenMaverFeatures.read(settings, "keyBindUseItem", "field_74313_G", null);
        if (OpenMaverFeatures.number(key, "pressTime", "field_151474_i") > 0) return;
        if (OpenMaverFeatures.number(mc, "rightClickDelayTimer", "field_71467_ac") > 0) return;
        // Clear before calling the transformed method so a failed dispatch is never replayed forever.
        deferredClick = false;
        Object renderer = OpenMaverFeatures.read(mc, "entityRenderer", "field_71460_t", null);
        if (!OpenMaverFeatures.callVoid(renderer, "getMouseOver", "func_78473_a", "a", 1.0F)) return;
        OpenMaverFeatures.callVoid(mc, "rightClickMouse", "func_147121_ag", "ax");
    }

    /** Null means ordinary KeepSprint owns the yaw. Otherwise use the bounded handoff yaw. */
    static Float movementYaw(float viewYaw, float forward, float strafe, boolean sprintAllowed) {
        if (!handoff) return null;
        boolean placing = deferredClick || tick <= requestedUntil;
        float target = placing || !sprintAllowed || Math.max(Math.abs(forward), Math.abs(strafe)) < 0.8F
                ? 0 : KeepSprintFeature.movementYaw(0, forward, strafe);
        // Interpolate raw yaw all the way to the exact vanilla value. Wrapping the
        // offset (or stopping at an equivalent +/-360) snaps when vanilla takes over.
        float delta = target - offset;
        offset += Math.max(-TURN_STEP, Math.min(TURN_STEP, delta));
        ready = false; // Published only after this tick's native movement has been sent.
        status = placing ? (aligned() ? "PLACE" : "TURN PLACE") : "TURN MOVE";
        if (!placing && Math.abs(target - offset) < 0.001F) handoff = false;
        return viewYaw + offset;
    }

    /** Discrete vanilla keys keep the movement predictor and jump direction in the same frame. */
    static void remapInput(MovementInput input, float viewYaw, float aimYaw) {
        float forward = input.moveForward, strafe = input.moveStrafe;
        float scale = Math.max(Math.abs(forward), Math.abs(strafe));
        if (scale == 0) return;
        float desiredYaw = KeepSprintFeature.movementYaw(viewYaw, forward, strafe);
        double angle = Math.toRadians(45 * Math.round(OpenMaverFeatures.wrapDegrees(aimYaw - desiredYaw) / 45));
        input.moveForward = Math.round(Math.cos(angle)) * scale;
        input.moveStrafe = Math.round(Math.sin(angle)) * scale;
    }

    static String getStatus() { return status; }

    static void recordRotation(Object mc, Object currentPlayer, float yawOffset) {
        if (context(mc, currentPlayer)) offset = yawOffset;
    }

    static void onTickEnd(Object mc, float viewYaw) {
        Object currentPlayer = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        if (!context(mc, currentPlayer) || !usable(mc, currentPlayer)) { reset(); return; }
        // Sample the native boundary, including ticks where another input owner made us yield.
        Object reported = OpenMaverFeatures.read(currentPlayer, "lastReportedYaw", "field_175164_bL", null);
        float nativeYaw = reported instanceof Number ? ((Number) reported).floatValue()
                : (float) OpenMaverFeatures.number(currentPlayer, "rotationYaw", "field_70177_z");
        offset = nativeYaw - viewYaw;
        if (handoff) ready = aligned();
    }
}
