import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import module.Module;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Verify first-world startup and real GUI entries survive individual feature errors. */
public final class ClientStartupTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("openmaver-startup-");
        System.setProperty("openmaver.config", root.resolve("modules.jsonc").toString());
        System.setProperty("openmaver.features", root.resolve("features.properties").toString());
        System.setProperty("openmaver.events.log", root.resolve("events.log").toString());
        String config = "{\"tools\":{\"logger\":{\"path\":\""
                + root.resolve("core.log").toString().replace("\\", "\\\\") + "\"}}}";
        Files.write(root.resolve("modules.jsonc"), config.getBytes(StandardCharsets.UTF_8));
        Minecraft mc = new Minecraft();
        Minecraft.instance = mc;
        mc.theWorld = new World();
        mc.thePlayer = new EntityPlayerSP();
        Module blockHit = new module.combat.BlockHit();
        ModuleManager.register(blockHit);
        blockHit.setEnabled(true);
        OpenMaverClientHandler handler = new OpenMaverClientHandler();

        handler.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        check((Boolean) get(handler, "blockHitFailed"), "BlockHit failure is detected");
        check(!(Boolean) get(handler, "failed"), "BlockHit cannot disable the event bridge");
        check(!blockHit.isEnabled(), "failed BlockHit releases ownership by disabling itself");
        mc.thePlayer = new EntityPlayerSP();
        MaverPersonalization.setTauntEnabled(true);
        handler.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        check((Boolean) get(handler, "tauntFailed"), "taunt failure is detected");
        check(!(Boolean) get(handler, "failed"), "taunt cannot disable the event bridge");
        check(OpenMaver.isStarted(), "first world still starts the real core");
        check(ModuleManager.getModules().size() == 31, "all 31 core modules register");
        check(ModuleManager.getTickCount() == 1, "other modules receive this same tick");

        GuiOpenMaverClickGui gui = new GuiOpenMaverClickGui(null);
        Method build = GuiOpenMaverClickGui.class.getDeclaredMethod("buildEntries");
        build.setAccessible(true);
        build.invoke(gui);
        List<?> entries = (List<?>) get(gui, "entries");
        Set<Object> displayed = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object entry : entries) displayed.add(get(entry, "module"));
        for (Module module : ModuleManager.getModules()) {
            if ("Scaffold2".equals(module.getName()) || "Telly".equals(module.getName())
                    || "GodBridge".equals(module.getName())) continue;
            check(displayed.contains(module), module.getName() + " is present in ClickGUI");
        }
        check(entries.size() == 31, "ClickGUI contains 28 standalone modules and 3 independent features");
        mc.theWorld = new World();
        handler.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        check(ModuleManager.getTickCount() == 2, "subsequent ticks continue without retrying broken features");
        OpenMaver.stop();
        System.out.println("startup-test: " + checks + " passed");
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
