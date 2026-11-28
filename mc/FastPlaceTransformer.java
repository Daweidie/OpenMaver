import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Label;

/** Coordinates manual placement before dispatch, then adjusts the vanilla cooldown assignment. */
public final class FastPlaceTransformer implements IClassTransformer, Opcodes {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !"net.minecraft.client.Minecraft".equals(transformedName)) return bytes;
        final ClassReader reader = new ClassReader(bytes);
        final int[] existing = {0};
        final int[] guards = {0};
        reader.accept(new ClassVisitor(ASM5) {
            @Override public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                if (!isRightClick(n, d)) return null;
                return new MethodVisitor(ASM5) {
                    @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (op == INVOKESTATIC && "FastPlaceFeature".equals(owner)
                                && "useCooldown".equals(n) && "(ILjava/lang/Object;)I".equals(d)) existing[0]++;
                        if (op == INVOKESTATIC && "KeepSprintPlacement".equals(owner)
                                && "allowRightClick".equals(n) && "(Ljava/lang/Object;)Z".equals(d)) guards[0]++;
                    }
                };
            }
        }, 0);
        if (existing[0] > 1 || guards[0] > 1) throw new IllegalStateException("Duplicate placement hooks");
        if (existing[0] == 1 && guards[0] == 1) return bytes;
        final int[] assignments = {0};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(ASM5, writer) {
            @Override public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                MethodVisitor method = super.visitMethod(access, n, d, s, e);
                if (!isRightClick(n, d)) return method;
                return new MethodVisitor(ASM5, method) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (guards[0] != 0) return;
                        Label vanilla = new Label();
                        super.visitVarInsn(ALOAD, 0);
                        super.visitMethodInsn(INVOKESTATIC, "KeepSprintPlacement", "allowRightClick",
                                "(Ljava/lang/Object;)Z", false);
                        super.visitJumpInsn(IFNE, vanilla);
                        super.visitInsn(RETURN);
                        super.visitLabel(vanilla);
                        super.visitFrame(F_FULL, 1, new Object[] {reader.getClassName()}, 0, new Object[0]);
                    }
                    @Override public void visitFieldInsn(int op, String owner, String n, String d) {
                        if (existing[0] == 0 && op == PUTFIELD && reader.getClassName().equals(owner) && "I".equals(d)
                                && ("rightClickDelayTimer".equals(n) || "field_71467_ac".equals(n) || "ap".equals(n))) {
                            // Stack: client, cooldown -> client, cooldown, client -> client, newCooldown.
                            super.visitVarInsn(ALOAD, 0);
                            super.visitMethodInsn(INVOKESTATIC, "FastPlaceFeature", "useCooldown",
                                    "(ILjava/lang/Object;)I", false);
                            assignments[0]++;
                        }
                        super.visitFieldInsn(op, owner, n, d);
                    }
                };
            }
        }, 0);
        if (existing[0] + assignments[0] != 1) {
            throw new IllegalStateException("FastPlace cooldown assignment missing or ambiguous: " + assignments[0]);
        }
        return writer.toByteArray();
    }

    private static boolean isRightClick(String name, String desc) {
        return "()V".equals(desc)
                && ("rightClickMouse".equals(name) || "func_147121_ag".equals(name) || "ax".equals(name));
    }
}
