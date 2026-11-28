import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Runs the actual 1.8.9 queue drain, with only downstream packet dispatch replaced by a probe. */
public final class NetworkManagerTransformTest implements Opcodes {
    private static final String TARGET = "net.minecraft.network.NetworkManager";
    private static final String LOCK = "java/util/concurrent/locks/ReentrantReadWriteLock";
    private static int checks;
    private static int fixtureId;
    private static State state;
    private static native boolean install(Object transformer, Class<?>[] targets, String[] names);

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void analyze(byte[] bytes, String flushName) throws Exception {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        int writes = 0;
        for (Object entry : type.methods) {
            MethodNode method = (MethodNode) entry;
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0)
                new Analyzer(new BasicVerifier()).analyze(type.name, method);
            if (!flushName.equals(method.name) || !"()V".equals(method.desc)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                check(!call.owner.equals(LOCK + "$ReadLock") && !call.name.equals("readLock"),
                        "drain never acquires or releases a shared read lock");
                if (call.owner.equals(LOCK) && call.name.equals("writeLock")) writes++;
            }
        }
        check(writes == 3, "exclusive acquisition and both finally releases patched");
    }

    private static final class Loader extends URLClassLoader {
        private final Map<String, byte[]> changed;
        Loader(URL[] urls, String name, byte[] bytes) {
            super(urls, NetworkManagerTransformTest.class.getClassLoader());
            changed = Collections.singletonMap(name, bytes);
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("NetworkManagerTransformTest")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                byte[] bytes = changed.get(name);
                // Define client classes without signed-JAR certificates, as LaunchWrapper does.
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

    private static final class State {
        final ReentrantReadWriteLock lock;
        final Map<Object, Object> listeners = new IdentityHashMap<Object, Object>();
        final List<Object> sent = new ArrayList<Object>();
        boolean exclusive, fail;
        State(ReentrantReadWriteLock lock) { this.lock = lock; }
    }

    /** The bytecode probe receives the original tuple's packet and listener array. */
    public static synchronized void dispatch(Object manager, Object packet, Object listeners) {
        if (state.exclusive && !state.lock.isWriteLockedByCurrentThread())
            throw new AssertionError("dispatch must remain inside the exclusive drain");
        if (state.listeners.get(packet) != listeners) throw new AssertionError("listener identity changed");
        if (state.fail) throw new IllegalStateException("downstream write failure");
        state.sent.add(packet);
    }

    private static byte[] probeDispatch(byte[] raw, String packet) {
        final String protocolKey = "network-test-protocol-" + fixtureId++;
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(raw).accept(new ClassVisitor(ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor out = super.visitMethod(access, n, d, s, e);
                if ("<clinit>".equals(n)) return new MethodVisitor(ASM5, out) {
                    @Override public void visitLdcInsn(Object value) {
                        // Netty 4.0 keys are globally unique, even across isolated game loaders.
                        super.visitLdcInsn("protocol".equals(value) ? protocolKey : value);
                    }
                };
                if (!d.equals("(L" + packet + ";[Lio/netty/util/concurrent/GenericFutureListener;)V")) return out;
                out.visitCode();
                out.visitVarInsn(ALOAD, 0); out.visitVarInsn(ALOAD, 1); out.visitVarInsn(ALOAD, 2);
                out.visitMethodInsn(INVOKESTATIC, "NetworkManagerTransformTest", "dispatch",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                out.visitInsn(RETURN); out.visitMaxs(3, 3); out.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static List<Throwable> flushConcurrently(final Object manager, final Method flush) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
        List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < 2; i++) {
            Thread thread = new Thread(() -> {
                try { start.await(); flush.invoke(manager); }
                catch (Throwable error) {
                    errors.add(error instanceof InvocationTargetException ? error.getCause() : error);
                }
            }, "queue-flush-" + i);
            thread.setDaemon(true); workers.add(thread); thread.start();
        }
        start.countDown();
        for (Thread thread : workers) {
            thread.join(5000);
            check(!thread.isAlive(), "queue flush terminates: " + thread.getName()
                    + Arrays.toString(thread.getStackTrace()) + "; errors=" + errors);
        }
        return errors;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static void exercise(URL[] urls, String managerName, String packetName, byte[] raw,
            NetworkManagerTransformer transformer, boolean fixed, boolean attach) throws Exception {
        byte[] fixture = probeDispatch(raw, packetName);
        if (fixed && !attach) fixture = transformer.transform(managerName, TARGET, fixture);
        try (Loader loader = new Loader(urls, managerName, fixture)) {
            Class<?> type = Class.forName(managerName, true, loader);
            if (attach) {
                check(install(transformer, new Class<?>[] {type}, new String[] {TARGET}), "JVMTI installs executing drain");
                check(install(transformer, new Class<?>[] {type}, new String[] {TARGET}), "JVMTI repeated install");
            }
            Class<?> direction = type.getConstructors()[0].getParameterTypes()[0];
            Object manager = type.getConstructors()[0].newInstance(direction.getEnumConstants()[0]);
            final ReentrantReadWriteLock lock = (ReentrantReadWriteLock) field(type, "j").get(manager);
            state = new State(lock); state.exclusive = fixed;
            Field channel = field(type, "k");
            channel.set(manager, Proxy.newProxyInstance(loader, new Class<?>[] {channel.getType()},
                    (proxy, method, args) -> "isOpen".equals(method.getName()) ? Boolean.TRUE : null));
            Method flush = type.getDeclaredMethod("m"); flush.setAccessible(true);
            Field queueField = field(type, "i");
            final CyclicBarrier race = new CyclicBarrier(2);
            final AtomicInteger snapshots = new AtomicInteger();
            Queue<Object> queue = new ConcurrentLinkedQueue<Object>() {
                @Override public boolean isEmpty() {
                    boolean empty = super.isEmpty();
                    if (!fixed && snapshots.getAndIncrement() < 2) try {
                        race.await(3, TimeUnit.SECONDS);
                    } catch (Exception error) { throw new AssertionError(error); }
                    return empty;
                }
                @Override public Object poll() {
                    if (fixed && !lock.isWriteLockedByCurrentThread())
                        throw new AssertionError("poll must use the exclusive lock");
                    return super.poll();
                }
            };
            queueField.set(manager, queue);
            Class<?> packet = loader.loadClass(packetName);
            Class<?> tuple = loader.loadClass(managerName + "$a");
            Constructor<?> constructor = tuple.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            List<Object> expected = new ArrayList<Object>();
            int count = fixed ? 1024 : 1;
            for (int i = 0; i < count; i++) {
                Object value = Proxy.newProxyInstance(loader, new Class<?>[] {packet}, (proxy, method, args) -> null);
                Object listeners = i % 2 == 0 ? null
                        : new io.netty.util.concurrent.GenericFutureListener<?>[] {future -> { }};
                state.listeners.put(value, listeners); expected.add(value);
                queue.add(constructor.newInstance(value, listeners));
            }
            List<Throwable> errors = flushConcurrently(manager, flush);
            if (!fixed) {
                check(errors.size() == 1 && errors.get(0) instanceof NullPointerException,
                        "original real client deterministically reproduces reported NPE");
            } else {
                check(errors.isEmpty(), "concurrent flushes have no errors: " + errors);
                check(state.sent.size() == expected.size(), "every packet dispatched once");
                for (int i = 0; i < expected.size(); i++)
                    check(state.sent.get(i) == expected.get(i), "original packet identity and order at " + i);
                check(queue.isEmpty(), "queue fully drained");
                check(!lock.isWriteLocked() && lock.getReadLockCount() == 0, "normal finally releases lock");
                queue.add(constructor.newInstance(expected.get(0), null)); state.fail = true;
                try { flush.invoke(manager); throw new AssertionError("downstream error swallowed"); }
                catch (InvocationTargetException error) {
                    check(error.getCause() instanceof IllegalStateException
                            && "downstream write failure".equals(error.getCause().getMessage()),
                            "downstream error preserved");
                }
                check(!lock.isWriteLocked() && lock.getReadLockCount() == 0, "exceptional finally releases lock");
                state.fail = false;
                queue.add(constructor.newInstance(expected.get(0), null)); channel.set(manager, null);
                flush.invoke(manager); check(queue.size() == 1, "disconnected manager retains queued packet");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> classes = new HashMap<String, String>(), methods = new HashMap<String, String>();
        for (String line : Files.readAllLines(Paths.get(args[1]))) {
            String[] p = line.split(" ");
            if (p[0].equals("CL:")) classes.put(p[1], p[2]);
            if (p[0].equals("MD:")) methods.put(p[1] + p[2], p[3].substring(p[3].lastIndexOf('/') + 1));
        }
        String manager = null, packet = null;
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            if (entry.getValue().equals(TARGET.replace('.', '/'))) manager = entry.getKey();
            if (entry.getValue().equals("net/minecraft/network/Packet")) packet = entry.getKey();
        }
        byte[] raw;
        try (JarFile jar = new JarFile(args[0])) { raw = read(jar.getInputStream(jar.getJarEntry(manager + ".class"))); }
        NetworkManagerTransformer transformer = new NetworkManagerTransformer();
        byte[] fixed = transformer.transform(manager, TARGET, raw); analyze(fixed, "m");
        check(Arrays.equals(fixed, transformer.transform(manager, TARGET, fixed)), "obfuscated patch is idempotent");
        for (final boolean mcp : new boolean[] {false, true}) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(raw).accept(new RemappingClassAdapter(writer, new Remapper() {
                @Override public String map(String name) { return classes.getOrDefault(name, name); }
                @Override public String mapMethodName(String owner, String n, String d) {
                    String name = methods.getOrDefault(owner + "/" + n + d, n);
                    return mcp && "func_150733_h".equals(name) ? "flushOutboundQueue" : name;
                }
            }), ClassReader.EXPAND_FRAMES);
            byte[] mapped = transformer.transform(TARGET, TARGET, writer.toByteArray());
            analyze(mapped, mcp ? "flushOutboundQueue" : "func_150733_h");
            check(Arrays.equals(mapped, transformer.transform(TARGET, TARGET, mapped)), "mapped patch is idempotent");
        }
        check(transformer.transform("other", "other", raw) == raw, "unrelated classes unchanged");
        check(transformer.transform(manager, TARGET, null) == null, "null input unchanged");
        check(Arrays.asList(new VelocityLoadingPlugin().getASMTransformerClass()).contains("NetworkManagerTransformer"),
                "Forge core plugin registers queue fix");
        List<URL> urls = new ArrayList<URL>(); urls.add(Paths.get(args[0]).toUri().toURL());
        try (java.util.stream.Stream<Path> paths = Files.walk(Paths.get(args[2]))) {
            for (Iterator<Path> i = paths.filter(p -> p.toString().endsWith(".jar")).iterator(); i.hasNext();)
                urls.add(i.next().toUri().toURL());
        }
        URL[] libraries = urls.toArray(new URL[0]); System.load(args[3]);
        try (Loader loader = new Loader(libraries, manager, fixed)) {
            check(loader.loadClass(manager).getDeclaredMethods().length > 0, "JVM verifies real patched client");
        }
        try (Loader loader = new Loader(libraries, manager, raw)) {
            Class<?> type = loader.loadClass(manager);
            check(install(transformer, new Class<?>[] {type}, new String[] {TARGET}), "JVMTI patches real loaded client");
        }
        exercise(libraries, manager, packet, raw, transformer, false, false);
        exercise(libraries, manager, packet, raw, transformer, true, false);
        exercise(libraries, manager, packet, raw, transformer, true, true);
        System.out.println("[network-test] " + checks + " checks passed; original NPE reproduced, Mod and JVMTI drains verified");
    }

    private static byte[] read(InputStream input) throws java.io.IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int size;
            while ((size = source.read(chunk)) != -1) output.write(chunk, 0, size);
            return output.toByteArray();
        }
    }
}
