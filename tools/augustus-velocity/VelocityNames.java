import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.lang.reflect.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
public class VelocityNames implements Opcodes {
 static class Reader extends ClassLoader {
  final JarFile jar;
  Reader(String p) throws Exception { super(ClassLoader.getPlatformClassLoader());jar=new JarFile(p); }
  protected Class<?> findClass(String name)throws ClassNotFoundException {
   try {
    String p=name.replace('.','/')+".class";
    byte[] b=jar.getInputStream(jar.getJarEntry(p)).readAllBytes();
    if(name.matches("net\\.47G[67ACHJNOQTWX_klmnzw]")) {
     ClassNode c=new ClassNode();new ClassReader(b).accept(c,0);
     c.superName="java/lang/Object";c.interfaces.clear();
     c.fields.removeIf(f->(f.access&ACC_STATIC)==0 || f.desc.contains("Lnet/"));
     c.methods.removeIf(m->(m.access&ACC_STATIC)==0 || m.desc.contains("Lnet/"));
     ClassWriter w=new ClassWriter(0);c.accept(w);b=w.toByteArray();
    }
    return defineClass(name,b,0,b.length);
   }catch(Exception e){throw new ClassNotFoundException(name,e);}
  }
 }
 static AbstractInsnNode prev(AbstractInsnNode n){do{n=n.getPrevious();}while(n!=null && n.getOpcode()<0);return n;}
 static Object constant(AbstractInsnNode n){if(n instanceof LdcInsnNode)return ((LdcInsnNode)n).cst;if(n instanceof IntInsnNode)return ((IntInsnNode)n).operand;if(n.getOpcode()>=ICONST_M1&&n.getOpcode()<=ICONST_5)return n.getOpcode()-ICONST_0;return null;}
 public static void main(String[] args)throws Exception {
  Reader loader=new Reader(args[0]);
  Class.forName("net.4f",true,loader);
  Class.forName("net.4d",true,loader);
  Class.forName("net.47Gw",true,loader);
  Class.forName("net.47GJ",true,loader);
  for(String n:new String[]{"47G6","47G7","47GA","47GC","47GH","47GN","47GO","47GQ","47GT","47GW","47GX","47G_","47Gk","47Gl","47Gm","47Gn","47Gz"}) {
   try {
    Class<?> cls=Class.forName("net."+n,true,loader);
    Field key=cls.getDeclaredField("b");key.setAccessible(true);long b=key.getLong(null);
    ClassNode c=new ClassNode();new ClassReader(loader.jar.getInputStream(loader.jar.getJarEntry("net/"+n+".class"))).accept(c,0);
    Method decrypt=null;for(Method m:cls.getDeclaredMethods())if(m.getReturnType()==String.class && Arrays.equals(m.getParameterTypes(),new Class[]{int.class,long.class})){decrypt=m;decrypt.setAccessible(true);}
    LinkedHashSet<String> strings=new LinkedHashSet<>();
    for(MethodNode m:c.methods)if(m.name.equals("<init>")){
     AbstractInsnNode first=m.instructions.getFirst();while(first.getOpcode()<0)first=first.getNext();
     long ctorKey=b^((Number)constant(first.getNext())).longValue();
     for(AbstractInsnNode in:m.instructions){
      if(in instanceof InvokeDynamicInsnNode && ((InvokeDynamicInsnNode)in).desc.equals("(IJ)Ljava/lang/String;")){
       AbstractInsnNode x=prev(in),load=prev(x),ln=prev(load),ix=prev(ln);
       if(x.getOpcode()==LXOR && load instanceof VarInsnNode && ((VarInsnNode)load).var==3){
        strings.add((String)decrypt.invoke(null,((Number)constant(ix)).intValue(),((Number)constant(ln)).longValue()^ctorKey));
       }
      }else if(in instanceof FieldInsnNode && in.getOpcode()==GETSTATIC && ((FieldInsnNode)in).desc.equals("Ljava/lang/String;")){
       Field f=cls.getDeclaredField(((FieldInsnNode)in).name);f.setAccessible(true);strings.add((String)f.get(null));
      }
     }
    }
    System.out.println(n+" "+strings);
   }catch(Throwable t){System.out.println(n+" ERROR "+t);t.printStackTrace(System.out);}
  }
 }
}
