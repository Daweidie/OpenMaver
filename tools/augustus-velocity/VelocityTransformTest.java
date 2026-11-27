import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Uses Forge's ASM 5 and the real client, plus an executing Java-8 attack fixture. */
public final class VelocityTransformTest implements Opcodes {
    static int checks,begin,end,first,merges;
    static boolean firstSeen;
    private static native boolean install(Object transformer,Class<?>[] targets,String[] names);
    static void check(boolean value,String message) {checks++;if(!value)throw new AssertionError(message);}
    public static final class DamageSource { }
    public static final class Target {public boolean accepted,fail;public boolean attackEntityFrom(DamageSource d,float f){if(fail)throw new IllegalStateException("damage fixture");return accepted;}}
    public static final class AttackFixture {
        public double motionX=1,motionZ=2,observed;
        public int knockback;
        public boolean sprinting=true;
        public void setSprinting(boolean value){sprinting=value;}
        public void attackTargetEntityWithCurrentItem(Target target) {
            if(target.attackEntityFrom(null,1)) {
                if(knockback>0) {motionX*=.6;motionZ*=.6;setSprinting(false);}
                observed=motionX;
            }
        }
    }
    public static void begin(Object p) {begin++;firstSeen=false;}
    public static void end(Object p) {end++;}
    public static double slowdown(Object p,Object target) {if(!firstSeen)first++;firstSeen=true;return .25;}
    public static void merge(Object p,Object target) throws Exception {
        merges++;if(!firstSeen)for(String field:new String[]{"motionX","motionZ"}) {java.lang.reflect.Field f=p.getClass().getField(field);f.setDouble(p,f.getDouble(p)*.5);}
    }
    static byte[] fakeHooks() {
        ClassWriter w=new ClassWriter(0);w.visit(V1_8,ACC_PUBLIC,"AugustusVelocityHooks",null,"java/lang/Object",null);
        for(String[] m:new String[][]{{"beginAttack","begin","(Ljava/lang/Object;)V"},{"endAttack","end","(Ljava/lang/Object;)V"},{"attackMerge","merge","(Ljava/lang/Object;Ljava/lang/Object;)V"},{"attackSlowdown","slowdown","(Ljava/lang/Object;Ljava/lang/Object;)D"}}) {
            MethodVisitor v=w.visitMethod(ACC_PUBLIC|ACC_STATIC,m[0],m[2],null,null);v.visitCode();v.visitVarInsn(ALOAD,0);
            if(m[2].contains(";Ljava/lang/Object;"))v.visitVarInsn(ALOAD,1);
            v.visitMethodInsn(INVOKESTATIC,"VelocityTransformTest",m[1],m[2],false);v.visitInsn(m[2].endsWith("D")?DRETURN:RETURN);v.visitMaxs(2,2);v.visitEnd();
        }
        w.visitEnd();return w.toByteArray();
    }
    static final class Loader extends URLClassLoader {
        final Map<String,byte[]> changed;
        Loader(URL[] urls,Map<String,byte[]> c){super(urls,VelocityTransformTest.class.getClassLoader());changed=c;}
        protected Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException {
            synchronized(getClassLoadingLock(n)) {
                Class<?> c=findLoadedClass(n);byte[] b=changed.get(n);
                // Match LaunchWrapper's byte-array loading: vanilla's signed JAR
                // certificates must not mix with transformed default-package classes.
                if(c==null&&b==null&&n.indexOf('.')<0) {
                    URL resource=findResource(n+".class");
                    if(resource!=null)try {b=read(resource.openStream());}catch(IOException e){throw new ClassNotFoundException(n,e);}
                }
                if(c==null)c=b==null?super.loadClass(n,false):defineClass(n,b,0,b.length);
                if(resolve)resolveClass(c);return c;
            }
        }
    }
    static void analyze(byte[] bytes) throws Exception {
        ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);
        for(Object obj:c.methods) {MethodNode m=(MethodNode)obj;if((m.access&(ACC_ABSTRACT|ACC_NATIVE))==0)new Analyzer(new BasicVerifier()).analyze(c.name,m);}
    }
    static Map<String,Integer> calls(byte[] bytes) {
        final Map<String,Integer> counts=new TreeMap<String,Integer>();new ClassReader(bytes).accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e) {return new MethodVisitor(ASM5) {
                public void visitMethodInsn(int op,String o,String n,String d,boolean i) {if(o.equals("AugustusVelocityHooks"))counts.put(n,counts.getOrDefault(n,0)+1);}
            };}
        },0);return counts;
    }
    public static void main(String[] args) throws Exception {
        final Map<String,String> classes=new HashMap<String,String>(),methods=new HashMap<String,String>(),fields=new HashMap<String,String>();
        for(String line:Files.readAllLines(Paths.get(args[1]))) {
            String[] p=line.split(" ");if(p[0].equals("CL:"))classes.put(p[1],p[2]);
            if(p[0].equals("MD:"))methods.put(p[1]+p[2],p[3].substring(p[3].lastIndexOf('/')+1));
            if(p[0].equals("FD:"))fields.put(p[1],p[2].substring(p[2].lastIndexOf('/')+1));
        }
        Remapper remapper=new Remapper() {
            public String map(String n){return classes.getOrDefault(n,n);}
            public String mapMethodName(String owner,String name,String desc){return methods.getOrDefault(owner+"/"+name+desc,name);}
            public String mapFieldName(String owner,String name,String desc){return fields.getOrDefault(owner+"/"+name,name);}
        };
        Map<String,byte[]> changed=new LinkedHashMap<String,byte[]>(),originals=new LinkedHashMap<String,byte[]>();VelocityTransformer transformer=new VelocityTransformer();
        List<String> targetNames=new ArrayList<String>();
        JarFile jar=new JarFile(args[0]);String[] required={"move","collision","preMotion","preUpdate","postUpdate","beginAttack","attackSlowdown","attackMerge","endAttack"};Set<String> found=new HashSet<String>();
        for(String target:new String[]{"net/minecraft/entity/Entity","net/minecraft/world/World","net/minecraft/client/entity/EntityPlayerSP","net/minecraft/entity/player/EntityPlayer"}) {
            String obf=null;for(Map.Entry<String,String> e:classes.entrySet())if(e.getValue().equals(target))obf=e.getKey();
            byte[] raw=read(jar.getInputStream(jar.getJarEntry(obf+".class")));
            byte[] output=transformer.transform(obf,target.replace('/','.'),raw);analyze(output);
            Map<String,Integer> counts=calls(output);check(!counts.isEmpty(),"real obfuscated hook "+target);found.addAll(counts.keySet());
            check(Arrays.equals(output,transformer.transform(obf,target.replace('/','.'),output)),"idempotent "+target);
            changed.put(obf.replace('/','.'),output);
            originals.put(obf.replace('/','.'),raw);targetNames.add(target.replace('/','.'));
            ClassWriter writer=new ClassWriter(0);new ClassReader(raw).accept(new RemappingClassAdapter(writer,remapper),ClassReader.EXPAND_FRAMES);
            byte[] srg=transformer.transform(target.replace('/','.'),target.replace('/','.'),writer.toByteArray());analyze(srg);
            check(calls(srg).equals(counts),"SRG and obfuscated hook coverage "+target);
            System.out.println("[velocity-transform] "+target+" "+counts);
        }
        for(String hook:required)check(found.contains(hook),"missing hook "+hook);
        List<URL> urls=new ArrayList<URL>();urls.add(Paths.get(args[0]).toUri().toURL());
        try(java.util.stream.Stream<Path> paths=Files.walk(Paths.get(args[2]))) {for(Iterator<Path> i=paths.filter(p->p.toString().endsWith(".jar")).iterator();i.hasNext();)urls.add(i.next().toUri().toURL());}
        try(Loader loader=new Loader(urls.toArray(new URL[0]),changed)) {for(String n:changed.keySet())check(Class.forName(n,false,loader).getDeclaredMethods().length>0,"JVM verifier "+n);}
        System.load(args[3]);
        try(Loader loader=new Loader(urls.toArray(new URL[0]),originals)) {
            List<Class<?>> targets=new ArrayList<Class<?>>();for(String n:originals.keySet())targets.add(Class.forName(n,false,loader));
            check(install(transformer,targets.toArray(new Class<?>[0]),targetNames.toArray(new String[0])),"JVMTI transforms already loaded real client classes");
            check(install(transformer,targets.toArray(new Class<?>[0]),targetNames.toArray(new String[0])),"JVMTI repeat installation");
        }
        String fixture=AttackFixture.class.getName();byte[] fixtureRaw=read(VelocityTransformTest.class.getClassLoader().getResourceAsStream(fixture+".class"));
        Map<String,byte[]> synthetic=new LinkedHashMap<String,byte[]>(originals);synthetic.remove("wn");synthetic.put(fixture,fixtureRaw);synthetic.put("AugustusVelocityHooks",fakeHooks());
        try(Loader loader=new Loader(urls.toArray(new URL[0]),synthetic)) {
            Class<?> c=loader.loadClass(fixture);Target target=new Target();java.lang.reflect.Method attack=c.getMethod("attackTargetEntityWithCurrentItem",Target.class);
            List<Class<?>> targets=new ArrayList<Class<?>>();for(String n:synthetic.keySet())if(!n.equals("AugustusVelocityHooks"))targets.add(Class.forName(n,false,loader));
            check(install(transformer,targets.toArray(new Class<?>[0]),targetNames.toArray(new String[0])),"JVMTI installs executing attack fixture");
            for(int branch=0;branch<3;branch++) {
                Object p=c.newInstance();target.accepted=branch>0;c.getField("knockback").setInt(p,branch==2?1:0);begin=end=first=merges=0;
                attack.invoke(p,target);check(begin==1&&end==1,"balanced frame "+branch);check(merges==(branch==0?0:1),"accepted damage merge "+branch);check(first==(branch==2?1:0),"FIRST only on knockback branch "+branch);
                double x=branch==0?1:branch==1?.5:.25;check(c.getField("motionX").getDouble(p)==x,"branch coefficient "+branch);
                if(branch>0)check(c.getField("observed").getDouble(p)==x,"reduction precedes following effects "+branch);
            }
            target.fail=true;begin=end=0;
            try {attack.invoke(c.newInstance(),target);throw new AssertionError("missing attack exception");}catch(java.lang.reflect.InvocationTargetException e){check(e.getCause() instanceof IllegalStateException,"attack exception preserved");}
            check(begin==1&&end==1,"exceptional attack closes frame once");
        }
        System.out.println("VelocityTransformTest: "+checks+" passed, 0 failed");
    }
    static byte[] read(InputStream stream)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=stream.read(chunk))!=-1)b.write(chunk,0,n);stream.close();return b.toByteArray();}
}
