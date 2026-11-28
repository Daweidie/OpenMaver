import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Serializes vanilla's queue consumers with the lock already used by its producers. */
public final class NetworkManagerTransformer implements IClassTransformer, Opcodes {
    private static final String LOCK = "java/util/concurrent/locks/ReentrantReadWriteLock";
    private static final String READ = LOCK + "$ReadLock";
    private static final String WRITE = LOCK + "$WriteLock";

    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !"net.minecraft.network.NetworkManager".equals(transformedName)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        final int[] methods = {0}, changed = {0}, existing = {0};
        reader.accept(new ClassVisitor(ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor method = super.visitMethod(access, n, d, s, e);
                if (!"()V".equals(d) || !("flushOutboundQueue".equals(n)
                        || "func_150733_h".equals(n) || "m".equals(n))) return method;
                methods[0]++;
                return new MethodVisitor(ASM5, method) {
                    @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        // A read lock lets the connection and client threads both pass isEmpty(),
                        // then one polls null. Keep vanilla's try/finally and use an exclusive lock
                        // for the whole drain, including dispatch, to preserve queued packet order.
                        if (LOCK.equals(owner) && "readLock".equals(n) && ("()L" + READ + ";").equals(d)) {
                            n = "writeLock";
                            d = "()L" + WRITE + ";";
                            changed[0]++;
                        } else if (READ.equals(owner) && "()V".equals(d)
                                && ("lock".equals(n) || "unlock".equals(n))) {
                            owner = WRITE;
                            changed[0]++;
                        } else if (LOCK.equals(owner) && "writeLock".equals(n) && ("()L" + WRITE + ";").equals(d)) {
                            existing[0]++;
                        } else if (WRITE.equals(owner) && "()V".equals(d)
                                && ("lock".equals(n) || "unlock".equals(n))) {
                            existing[0]++;
                        }
                        super.visitMethodInsn(op, owner, n, d, itf);
                    }
                };
            }
        }, 0);
        // Acquisition, normal release and exceptional release each use two calls.
        if (methods[0] != 1 || changed[0] + existing[0] != 6) {
            throw new IllegalStateException("NetworkManager queue lock missing or ambiguous: "
                    + methods[0] + " methods, " + (changed[0] + existing[0]) + " lock calls");
        }
        return changed[0] == 0 ? bytes : writer.toByteArray();
    }
}
