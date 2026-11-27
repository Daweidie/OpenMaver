import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;

/** Narrow hooks; no Minecraft classes are linked while LaunchWrapper transforms them. */
public final class VelocityTransformer implements IClassTransformer, Opcodes {
    public byte[] transform(String name,String transformedName,byte[] bytes) {
        if(bytes==null)return null;
        final int kind="net.minecraft.entity.Entity".equals(transformedName)?1:
            "net.minecraft.world.World".equals(transformedName)?2:
            "net.minecraft.client.entity.EntityPlayerSP".equals(transformedName)?3:
            "net.minecraft.entity.player.EntityPlayer".equals(transformedName)?4:0;
        if(kind==0)return bytes;
        ClassReader r=new ClassReader(bytes);
        final boolean[] hooked={false};
        r.accept(new ClassVisitor(ASM5) {
            @Override public MethodVisitor visitMethod(int access,String n,String d,String s,String[] e) {
                return new MethodVisitor(ASM5) {
                    @Override public void visitMethodInsn(int op,String owner,String name,String desc,boolean itf) {if(owner.equals("AugustusVelocityHooks"))hooked[0]=true;}
                };
            }
        },0);
        if(hooked[0])return bytes;
        final int[] coverage={0};
        ClassWriter w=new ClassWriter(r,ClassWriter.COMPUTE_MAXS);
        r.accept(new ClassVisitor(ASM5,w) {
            @Override public MethodVisitor visitMethod(int access,String n,String d,String signature,String[] exceptions) {
                MethodVisitor mv=super.visitMethod(access,n,d,signature,exceptions);
                final boolean move=kind==1&&d.equals("(DDD)V")&&(n.equals("moveEntity")||n.equals("func_70091_d")||n.equals("d"));
                final boolean collision=kind==2&&((d.equals("(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;")
                    &&(n.equals("getCollidingBoundingBoxes")||n.equals("func_72945_a")))
                    ||(n.equals("a")&&d.equals("(Lpk;Laug;)Ljava/util/List;")));
                final boolean motion=kind==3&&d.equals("()V")&&(n.equals("onUpdateWalkingPlayer")||n.equals("func_175161_p")||n.equals("p"));
                final boolean update=kind==3&&d.equals("()V")&&(n.equals("onUpdate")||n.equals("func_70071_h_")||n.equals("t_"));
                final boolean attack=kind==4&&((n.equals("attackTargetEntityWithCurrentItem")||n.equals("func_71059_n"))&&d.startsWith("(L")&&d.endsWith(";)V")
                    ||n.equals("f")&&d.equals("(Lpk;)V"));
                if(!(move||collision||motion||update||attack))return mv;
                coverage[0]|=move?1:collision?2:motion?4:update?8:16;
                return new MethodVisitor(ASM5,mv) {
                    boolean damageSeen;
                    Label attackMerge;
                    Label attackStart,attackEnd,attackFailure;
                    boolean mergePending;
                    void emitMerge() {
                        if(!mergePending)return;mergePending=false;
                        mv.visitVarInsn(ALOAD,0);mv.visitVarInsn(ALOAD,1);
                        mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","attackMerge","(Ljava/lang/Object;Ljava/lang/Object;)V",false);
                    }
                    @Override public void visitVarInsn(int opcode,int var) {emitMerge();super.visitVarInsn(opcode,var);}
                    @Override public void visitCode() {
                        super.visitCode();
                        if(move) {
                            mv.visitVarInsn(ALOAD,0);mv.visitVarInsn(DLOAD,1);mv.visitVarInsn(DLOAD,3);mv.visitVarInsn(DLOAD,5);
                            mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","move","(Ljava/lang/Object;DDD)[D",false);
                            mv.visitInsn(DUP);mv.visitInsn(ICONST_0);mv.visitInsn(DALOAD);mv.visitVarInsn(DSTORE,1);
                            mv.visitInsn(DUP);mv.visitInsn(ICONST_1);mv.visitInsn(DALOAD);mv.visitVarInsn(DSTORE,3);
                            mv.visitInsn(ICONST_2);mv.visitInsn(DALOAD);mv.visitVarInsn(DSTORE,5);
                        }
                        if(motion) { mv.visitVarInsn(ALOAD,0);mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","preMotion","(Ljava/lang/Object;)V",false); }
                        if(attack) {
                            mv.visitVarInsn(ALOAD,0);mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","beginAttack","(Ljava/lang/Object;)V",false);
                            attackStart=new Label();attackEnd=new Label();attackFailure=new Label();mv.visitLabel(attackStart);
                        }
                    }
                    @Override public void visitInsn(int opcode) {
                        emitMerge();
                        if(attack&&opcode==RETURN) {
                            mv.visitVarInsn(ALOAD,0);mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","endAttack","(Ljava/lang/Object;)V",false);
                        }
                        if(collision&&opcode==ARETURN) {
                            mv.visitVarInsn(ALOAD,1);mv.visitVarInsn(ALOAD,2);
                            mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","collision","(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;",false);
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean itf) {
                        emitMerge();
                        if(attack&&desc.endsWith(";F)Z")&&(method.equals("attackEntityFrom")||method.equals("func_70097_a")||method.equals("a")))damageSeen=true;
                        boolean playerUpdate=update&&opcode==INVOKESPECIAL&&desc.equals("()V")
                            &&(method.equals("onUpdate")||method.equals("func_70071_h_")||method.equals("t_"));
                        if(playerUpdate) {mv.visitVarInsn(ALOAD,0);mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","preUpdate","(Ljava/lang/Object;)V",false);}
                        super.visitMethodInsn(opcode,owner,method,desc,itf);
                        if(playerUpdate) {mv.visitVarInsn(ALOAD,0);mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","postUpdate","(Ljava/lang/Object;)V",false);}
                    }
                    @Override public void visitJumpInsn(int opcode,Label label) {
                        // Vanilla's knockback>0 branch merges immediately after setSprinting(false).
                        // This point is inside the accepted-damage block, before enchantment effects.
                        if(attack&&damageSeen&&attackMerge==null&&opcode==IFLE)attackMerge=label;
                        super.visitJumpInsn(opcode,label);
                    }
                    @Override public void visitLabel(Label label) {
                        super.visitLabel(label);
                        if(attack&&label==attackMerge)mergePending=true;
                    }
                    @Override public void visitLdcInsn(Object c) {
                        emitMerge();
                        if(attack&&c instanceof Double&&((Double)c).doubleValue()==.6) {
                            mv.visitVarInsn(ALOAD,0);mv.visitVarInsn(ALOAD,1);
                            mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","attackSlowdown","(Ljava/lang/Object;Ljava/lang/Object;)D",false);
                        } else super.visitLdcInsn(c);
                    }
                    @Override public void visitMaxs(int stack,int locals) {
                        if(attack) {
                            mv.visitLabel(attackEnd);mv.visitTryCatchBlock(attackStart,attackEnd,attackFailure,null);mv.visitLabel(attackFailure);
                            mv.visitFrame(F_FULL,2,new Object[]{r.getClassName(),d.substring(2,d.indexOf(';'))},1,new Object[]{"java/lang/Throwable"});
                            mv.visitVarInsn(ALOAD,0);mv.visitMethodInsn(INVOKESTATIC,"AugustusVelocityHooks","endAttack","(Ljava/lang/Object;)V",false);mv.visitInsn(ATHROW);
                        }
                        super.visitMaxs(stack,locals);
                    }
                };
            }
        },0);
        int expected=kind==1?1:kind==2?2:kind==3?12:16;
        if(coverage[0]!=expected)throw new IllegalStateException("Velocity hook target missing: "+transformedName+" ("+coverage[0]+"/"+expected+")");
        return w.toByteArray();
    }
}
