import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import util.Logger;

/** LiquidBounce-style cooldown override; vanilla owns input, ray tracing and placement. */
public final class FastPlaceFeature {
    private static boolean hooksAvailable = true;
    private static String status = "OFF";

    private FastPlaceFeature() { }

    /** START only updates the HUD; it never clicks or changes the remaining cooldown. */
    public static void onTickStart(Minecraft mc) {
        String pause = pauseReason(mc);
        status = pause == null ? OpenMaverFeatures.getFastPlaceDelayTicks() + "t" : pause;
    }

    /** Called exactly where vanilla assigns the cooldown in rightClickMouse(). */
    public static int useCooldown(int vanilla, Object mc) {
        String pause = pauseReason(mc);
        status = pause == null ? OpenMaverFeatures.getFastPlaceDelayTicks() + "t" : pause;
        return pause == null ? OpenMaverFeatures.getFastPlaceDelayTicks() : vanilla;
    }

    /** AutoClicker yields its automatic right clicks while FastPlace handles blocks. */
    public static boolean isActive(Object mc) {
        return pauseReason(mc) == null;
    }

    public static String getStatus() { return status; }

    public static void setHooksAvailable(boolean available) {
        hooksAvailable = available;
        if (!available) Logger.warn("FastPlace", "cooldown hook missing; retaining vanilla cooldown");
    }

    private static String pauseReason(Object mc) {
        Module module = ModuleManager.getModule("FastPlace");
        if (module == null || !module.isEnabled()) return "OFF";
        if (!hooksAvailable) return "NO HOOK";
        if (OpenMaverFeatures.read(mc, "currentScreen", "field_71462_r", null) != null) return "GUI";
        Object player = OpenMaverFeatures.read(mc, "thePlayer", "field_71439_g", null);
        Object world = OpenMaverFeatures.read(mc, "theWorld", "field_71441_e", null);
        if (player == null || world == null) return "NO WORLD";
        if (!OpenMaverFeatures.alive(player)) return "DEAD";
        if (Boolean.FALSE.equals(OpenMaverFeatures.read(mc, "inGameHasFocus", "field_71415_G", null))) {
            return "UNFOCUSED";
        }
        if (OpenMaverFeatures.placementModuleEnabled()) return "SCAFFOLD";
        if (!OpenMaverFeatures.holdingBlock(player)) return "NO BLOCK";
        if (Boolean.TRUE.equals(OpenMaverFeatures.call(player, "isUsingItem", "func_71039_bw", "bS"))) {
            return "USING ITEM";
        }
        return null;
    }
}
