import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import module.ModuleManager;
import module.player.FastPlace;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Exercises actual transformed bytecode, not a handwritten call to the cooldown hook. */
public final class FastPlaceTransformTest implements Opcodes {
    private static int checks;
    private static native boolean install(Object transformer, Class<?>[] targets, String[] names);
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void analyze(byte[] bytes) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        int hooks = 0;
        for (Object entry : type.methods) {
            MethodNode method = (MethodNode) entry;
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0) {
                new Analyzer(new BasicVerifier()).analyze(type.name, method);
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!"FastPlaceFeature".equals(call.owner) || !"useCooldown".equals(call.name)) continue;
                hooks++;
                check(insn.getNext() instanceof FieldInsnNode && insn.getNext().getOpcode() == PUTFIELD,
                        "cooldown hook directly precedes original field assignment");
                check(insn.getPrevious() instanceof VarInsnNode && insn.getPrevious().getOpcode() == ALOAD
                        && ((VarInsnNode) insn.getPrevious()).var == 0, "hook receives this client");
            }
        }
        check(hooks == 1, "exactly one cooldown hook");
    }

    private static final class Loader extends URLClassLoader {
        private final Map<String, byte[]> changed;
        Loader(URL[] urls, Map<String, byte[]> changed) {
            super(urls, FastPlaceTransformTest.class.getClassLoader()); this.changed = changed;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                byte[] bytes = changed.get(name);
                // Real obfuscated classes live in the default package of a signed client JAR.
                if (type == null && bytes == null && name.indexOf('.') < 0) {
                    URL resource = findResource(name + ".class");
                    if (resource != null) try { bytes = read(resource.openStream()); }
                    catch (java.io.IOException error) { throw new ClassNotFoundException(name, error); }
                }
                if (type == null) type = bytes == null ? super.loadClass(name, false)
                        : defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    private static void exercise(Class<?> type, FastPlace module) throws Exception {
        java.lang.reflect.Method click = type.getMethod("rightClickMouse");
        for (int ticks = 0; ticks <= 5; ticks++) {
            OpenMaverFeatures.setFastPlaceDelayTicks(ticks);
            Object mc = type.newInstance();
            java.lang.reflect.Field cooldown = type.getField("rightClickDelayTimer");
            int last = -1, clicks = 0;
            for (int tick = 0; tick < 31; tick++) {
                if (cooldown.getInt(mc) > 0) cooldown.setInt(mc, cooldown.getInt(mc) - 1);
                if (cooldown.getInt(mc) == 0) {
                    click.invoke(mc);
                    check(cooldown.getInt(mc) == ticks && type.getField("observedCooldown").getInt(mc) == ticks,
                            "cooldown is changed before vanilla dispatch, " + ticks + "t");
                    if (last >= 0) check(tick - last == Math.max(1, ticks), "actual held-input interval " + ticks + "t");
                    last = tick; clicks++;
                }
            }
            check(type.getField("clicks").getInt(mc) == clicks, "one vanilla dispatch per input attempt");
            System.out.println("[fastplace-transform] held " + ticks + "t -> " + clicks + " clicks / 31 ticks");
        }
        Object mc = type.newInstance();
        module.setEnabled(false); click.invoke(mc);
        check(type.getField("observedCooldown").getInt(mc) == 4, "disabled hook preserves vanilla cooldown");
        module.setEnabled(true);
        FastPlaceTest.Player player = (FastPlaceTest.Player) type.getField("thePlayer").get(mc);
        player.stack.item = new Object(); click.invoke(mc);
        check(type.getField("observedCooldown").getInt(mc) == 4, "non-block item preserves vanilla cooldown");
    }

    public static void main(String[] args) throws Exception {
        Path settings = Files.createTempFile("openmaver-fastplace-transform-", ".properties");
        System.setProperty("openmaver.features", settings.toString());
        System.setProperty("openmaver.config", settings.toString());
        FastPlace module = new FastPlace(); ModuleManager.register(module); module.setEnabled(true);
        FastPlaceTransformer transformer = new FastPlaceTransformer();
        final Map<String, String> classes = new HashMap<String, String>();
        final Map<String, String> methods = new HashMap<String, String>();
        final Map<String, String> fields = new HashMap<String, String>();
        for (String line : Files.readAllLines(Paths.get(args[1]))) {
            String[] p = line.split(" ");
            if (p[0].equals("CL:")) classes.put(p[1], p[2]);
            if (p[0].equals("MD:")) methods.put(p[1] + p[2], p[3].substring(p[3].lastIndexOf('/') + 1));
            if (p[0].equals("FD:")) fields.put(p[1], p[2].substring(p[2].lastIndexOf('/') + 1));
        }
        String obf = null;
        for (Map.Entry<String, String> e : classes.entrySet()) if (e.getValue().equals(MINECRAFT.replace('.', '/'))) obf = e.getKey();
        check(obf != null, "real Forge Minecraft mapping");
        byte[] raw;
        try (JarFile jar = new JarFile(args[0])) { raw = read(jar.getInputStream(jar.getJarEntry(obf + ".class"))); }
        byte[] transformed = transformer.transform(obf, MINECRAFT, raw);
        analyze(transformed);
        check(Arrays.equals(transformed, transformer.transform(obf, MINECRAFT, transformed)), "idempotent real obfuscated transform");
        for (final boolean mcp : new boolean[] {false, true}) {
            Remapper remapper = new Remapper() {
                public String map(String n) { return classes.containsKey(n) ? classes.get(n) : n; }
                public String mapMethodName(String owner, String name, String desc) {
                    String value = methods.get(owner + "/" + name + desc);
                    if (value == null) return name;
                    return mcp && "func_147121_ag".equals(value) ? "rightClickMouse" : value;
                }
                public String mapFieldName(String owner, String name, String desc) {
                    String value = fields.get(owner + "/" + name);
                    if (value == null) return name;
                    return mcp && "field_71467_ac".equals(value) ? "rightClickDelayTimer" : value;
                }
            };
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(raw).accept(new RemappingClassAdapter(writer, remapper), ClassReader.EXPAND_FRAMES);
            byte[] output = transformer.transform(MINECRAFT, MINECRAFT, writer.toByteArray());
            analyze(output);
            check(Arrays.equals(output, transformer.transform(MINECRAFT, MINECRAFT, output)), "idempotent mapped transform");
        }
        check(transformer.transform("SAMPLE", "SAMPLE", raw) == raw, "unrelated classes untouched");
        check(transformer.transform(obf, MINECRAFT, null) == null, "null input unchanged");
        System.out.println("[fastplace-transform] real 1.8.9 obfuscated / SRG / MCP bytecode verified");
        List<URL> urls = new ArrayList<URL>(); urls.add(Paths.get(args[0]).toUri().toURL());
        try (java.util.stream.Stream<Path> paths = Files.walk(Paths.get(args[2]))) {
            for (Iterator<Path> i = paths.filter(p -> p.toString().endsWith(".jar")).iterator(); i.hasNext();) urls.add(i.next().toUri().toURL());
        }
        System.load(args[3]);
        Map<String, byte[]> changed = new HashMap<String, byte[]>(); changed.put(obf, transformed);
        try (Loader loader = new Loader(urls.toArray(new URL[0]), changed)) {
            check(Class.forName(obf, false, loader).getDeclaredMethods().length > 0, "JVM verifies real transformed Minecraft");
        }
        changed = new HashMap<String, byte[]>(); changed.put(obf, raw);
        try (Loader loader = new Loader(urls.toArray(new URL[0]), changed)) {
            Class<?> target = Class.forName(obf, false, loader);
            check(install(transformer, new Class<?>[] {target}, new String[] {MINECRAFT}), "JVMTI installs on already loaded real Minecraft");
            check(install(transformer, new Class<?>[] {target}, new String[] {MINECRAFT}), "JVMTI repeat install");
        }
        String fixture = "FastPlaceTest$Mcp";
        byte[] fixtureRaw = read(FastPlaceTransformTest.class.getClassLoader().getResourceAsStream(fixture + ".class"));
        byte[] fixtureChanged = transformer.transform(fixture, MINECRAFT, fixtureRaw); analyze(fixtureChanged);
        changed = new HashMap<String, byte[]>(); changed.put(fixture, fixtureChanged);
        try (Loader loader = new Loader(new URL[0], changed)) { exercise(loader.loadClass(fixture), module); }
        changed = new HashMap<String, byte[]>(); changed.put(fixture, fixtureRaw);
        try (Loader loader = new Loader(new URL[0], changed)) {
            Class<?> target = loader.loadClass(fixture);
            check(install(transformer, new Class<?>[] {target}, new String[] {MINECRAFT}), "JVMTI installs executing fixture");
            exercise(target, module);
        }
        module.setEnabled(false); Files.deleteIfExists(settings);
        System.out.println("[fastplace-transform] " + checks + " checks passed");
    }

    private static byte[] read(InputStream input) throws java.io.IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int size;
            while ((size = source.read(chunk)) != -1) output.write(chunk, 0, size);
            return output.toByteArray();
        }
    }
}
