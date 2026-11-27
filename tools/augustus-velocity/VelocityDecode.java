import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;


/** Offline analysis only. Resolves encrypted member tables; never runs game methods. */
public class VelocityDecode implements Opcodes {
 static final Object NIL=new Object();
 static Loader loader;
 static Map<String,Long> keys=new LinkedHashMap<>();
 static Map<String,String> decodeCache=new HashMap<>();
 static Map<AbstractInsnNode,String> targets=new IdentityHashMap<>();
 static Set<String> errors=new HashSet<>();
 static class Loader extends ClassLoader {
  JarFile jar;
  Loader(String file)throws Exception {super(ClassLoader.getPlatformClassLoader());jar=new JarFile(file);}
  protected Class<?> findClass(String name)throws ClassNotFoundException {
   try {
    ClassNode c=read(name.replace('.','/'));
    if(name.equals("net.47mZ"))Class.forName("net.47ma",true,this);
    if (name.startsWith("net.47")) {
     c.access=(c.access&~(ACC_INTERFACE|ACC_ABSTRACT))|ACC_PUBLIC;
     c.superName="java/lang/Object";c.interfaces.clear();
     c.fields.removeIf(f->(f.access&ACC_STATIC)==0||f.desc.contains("Lnet/"));
     c.methods.removeIf(m->(m.access&ACC_STATIC)==0||m.desc.contains("Lnet/"));
     if (Arrays.asList("net.47uP","net.47TT","net.47gg","net.47Bj","net.47OT","net.47Ts","net.47GJ","net.47mZ").contains(name))
      c.methods.removeIf(m->m.name.startsWith("4") && !(m.desc.equals("()[Ljava/lang/String;") || m.desc.equals("([Ljava/lang/String;)V") || m.desc.equals("()I") || m.desc.equals("(I)V")));
     for(MethodNode m:c.methods)if(m.name.equals("<clinit>")&&!name.matches("net\\.47G[67ACHJNOQTWX_klmnzw]|net\\.47Ts|net\\.47TU|net\\.47mZ|net\\.47cu")) {
      // Other event classes may instantiate game singletons. Stop after the first table initializer.
      AbstractInsnNode cut=null;
      for(AbstractInsnNode n:m.instructions)if(n instanceof MethodInsnNode){MethodInsnNode a=(MethodInsnNode)n;if(a.owner.equals(c.name)&&a.desc.equals("()V")){cut=n;break;}}
      if(cut!=null){while(cut.getNext()!=null)m.instructions.remove(cut.getNext());if(name.equals("net.47uP")){m.instructions.add(new InsnNode(ICONST_4));m.instructions.add(new TypeInsnNode(ANEWARRAY,"java/lang/String"));m.instructions.add(new FieldInsnNode(PUTSTATIC,c.name,"42","[Ljava/lang/String;"));}m.instructions.add(new InsnNode(RETURN));m.tryCatchBlocks.clear();}
     }
    }
    ClassWriter w=new ClassWriter(0);c.accept(w);byte[] b=w.toByteArray();return defineClass(name,b,0,b.length);
   }catch(Throwable e){throw new ClassNotFoundException(name,e);}
  }
  ClassNode read(String name)throws Exception{JarEntry e=jar.getJarEntry(name+".class");JarFile src=jar;if(e==null){src=new JarFile(Paths.get(jar.getName()).getParent().getParent().resolve("lib/runtime-additions.jar").toFile());e=src.getJarEntry(name+".class");}ClassNode c=new ClassNode();new ClassReader(src.getInputStream(e)).accept(c,0);return c;}
 }
 static Class<?> cls(String n)throws Exception{return Class.forName(n.replace('/','.'),true,loader);}
 static Object field(String owner,String name)throws Exception{Field f=cls(owner).getDeclaredField(name);f.setAccessible(true);return f.get(null);}
 static String decode(String owner,long a,long b)throws Exception {
  String cacheKey=owner+":"+a+":"+b;String cached=decodeCache.get(cacheKey);if(cached!=null)return cached;
  Class<?> c=cls(owner);Method d=null;
  for(Method m:c.getDeclaredMethods())if(m.getReturnType()==int.class&&Arrays.equals(m.getParameterTypes(),new Class[]{long.class,long.class})){d=m;break;}
  if(d==null)return "?"; d.setAccessible(true);int i=(Integer)d.invoke(null,a,b);
  String table=null;
  ClassNode cn=loader.read(owner);
  for(MethodNode m:cn.methods)if(m.name.equals(d.getName())&&m.desc.equals("(JJ)I"))for(AbstractInsnNode n:m.instructions)if(n instanceof FieldInsnNode&&((FieldInsnNode)n).desc.equals("[Ljava/lang/String;")){table=((FieldInsnNode)n).name;break;}
  String s=((String[])field(owner,table))[i];
  if(s==null)return "?";
  if(s.indexOf('\b')>=0){String[] parts=s.split("\b");StringBuilder out=new StringBuilder();out.append(decode(owner,Long.parseLong(parts[0],36),0)).append('.').append(parts[1]);for(int k=2;k<parts.length;k++)out.append(' ').append(decode(owner,Long.parseLong(parts[k],36),0));String result=out.toString();decodeCache.put(cacheKey,result);return result;}
  decodeCache.put(cacheKey,s);return s;
 }
 static Object string(String owner,int a,long b)throws Exception{for(Method m:cls(owner).getDeclaredMethods())if(m.getReturnType()==String.class&&Arrays.equals(m.getParameterTypes(),new Class[]{int.class,long.class})){m.setAccessible(true);return m.invoke(null,a,b);}return null;}
 static class Val extends BasicValue {
  final Object value;
  Val(Type t,Object v){super(t);value=v;}
  public boolean equals(Object x){return x instanceof Val&&super.equals(x)&&Objects.equals(value,((Val)x).value);}
  public int hashCode(){return super.hashCode()+Objects.hashCode(value);}
 }
 static Object val(BasicValue v){return v instanceof Val?((Val)v).value:null;}
 static BasicValue cv(BasicValue t,Object c){return t==null?null:new Val(t.getType(),c);}
 static class Const extends BasicInterpreter {
  final String owner; final Map<Integer,Object> params;
  Const(String o,Map<Integer,Object> p){super(ASM9);owner=o;params=p;}
  public BasicValue newValue(Type t){return cv(super.newValue(t),null);}
  public BasicValue newParameterValue(boolean inst,int local,Type t){return new Val(t,params.get(local));}
  public BasicValue newOperation(AbstractInsnNode n)throws AnalyzerException {
   BasicValue base=super.newOperation(n);Object v=null;int op=n.getOpcode();
   if(op==ACONST_NULL)v=NIL;
   else if(op>=ICONST_M1&&op<=ICONST_5)v=op-ICONST_0;
   else if(op>=LCONST_0&&op<=LCONST_1)v=(long)(op-LCONST_0);
   else if(op>=DCONST_0&&op<=DCONST_1)v=(double)(op-DCONST_0);
   else if(op>=FCONST_0&&op<=FCONST_2)v=(float)(op-FCONST_0);
   else if(n instanceof LdcInsnNode)v=((LdcInsnNode)n).cst;
   else if(n instanceof IntInsnNode)v=((IntInsnNode)n).operand;
   else if(n instanceof FieldInsnNode){FieldInsnNode f=(FieldInsnNode)n;if(f.owner.equals(owner)&&f.desc.equals("J"))try{v=field(f.owner,f.name);}catch(Exception ignored){}}
   return cv(base,v);
  }
  public BasicValue copyOperation(AbstractInsnNode n,BasicValue v){return v;}
  public BasicValue unaryOperation(AbstractInsnNode n,BasicValue a)throws AnalyzerException {
   BasicValue base=super.unaryOperation(n,a);Object x=val(a),v=null;
   if(x instanceof Number){Number k=(Number)x;switch(n.getOpcode()){case INEG:v=-k.intValue();break;case LNEG:v=-k.longValue();break;case I2L:v=k.longValue();break;case L2I:v=k.intValue();break;case I2S:v=(int)(short)k.intValue();break;case I2C:v=(int)(char)k.intValue();break;case I2B:v=(int)(byte)k.intValue();break;case I2D:case L2D:v=k.doubleValue();break;}}
   if(n.getOpcode()==CHECKCAST)v=x;
   return cv(base,v);
  }
  public BasicValue binaryOperation(AbstractInsnNode n,BasicValue a,BasicValue b)throws AnalyzerException {
   BasicValue base=super.binaryOperation(n,a,b);Object x=val(a),y=val(b),v=null;
   if(x instanceof Number&&y instanceof Number){Number p=(Number)x,q=(Number)y;switch(n.getOpcode()){
    case LXOR:v=p.longValue()^q.longValue();break;case LOR:v=p.longValue()|q.longValue();break;case LAND:v=p.longValue()&q.longValue();break;
    case LSHL:v=p.longValue()<<q.intValue();break;case LSHR:v=p.longValue()>>q.intValue();break;case LUSHR:v=p.longValue()>>>q.intValue();break;
    case IXOR:v=p.intValue()^q.intValue();break;case IOR:v=p.intValue()|q.intValue();break;case IAND:v=p.intValue()&q.intValue();break;
    case ISHL:v=p.intValue()<<q.intValue();break;case IUSHR:v=p.intValue()>>>q.intValue();break;
    case LCMP:v=Long.compare(p.longValue(),q.longValue());break;case IADD:v=p.intValue()+q.intValue();break;case ISUB:v=p.intValue()-q.intValue();break;
    case LADD:v=p.longValue()+q.longValue();break;case LSUB:v=p.longValue()-q.longValue();break;
   }}return cv(base,v);
  }
  public BasicValue naryOperation(AbstractInsnNode n,List<? extends BasicValue> args)throws AnalyzerException {
   BasicValue base=super.naryOperation(n,args);Object v=null;
   if(n instanceof InvokeDynamicInsnNode){InvokeDynamicInsnNode d=(InvokeDynamicInsnNode)n;
    try{
     if(d.desc.equals("(IJ)Ljava/lang/String;")&&val(args.get(0)) instanceof Integer&&val(args.get(1)) instanceof Long)v=string(owner,(Integer)val(args.get(0)),(Long)val(args.get(1)));
     else if(d.bsmArgs.length==0&&args.size()>=2){Object a=val(args.get(args.size()-2)),b=val(args.get(args.size()-1));if(a instanceof Long&&b instanceof Long){String target=decode(owner,(Long)a,(Long)b);targets.put(n,target);
     }}
     String target=targets.get(n);
     // Guard values differ between classes. In particular 47cu initializes a NON-null
     // array; treating every String[] guard as the parent's null array corrupts 47TT.
     if(target!=null && args.size()==2 && (target.endsWith("java/lang/Integer")||target.endsWith("[Ljava.lang.String;")) && (target.startsWith("net.47mZ.")||target.startsWith("net.47Ts.")||target.startsWith("net.47GJ.")||target.startsWith("net.47cu.")||target.startsWith("net.47uP."))) {
      String q=target.split(" ")[0];String mn=q.substring(q.lastIndexOf('.')+1);
      Method mm=cls(q.substring(0,q.lastIndexOf('.')).replace('.','/')).getDeclaredMethod(mn);mm.setAccessible(true);
      if(mm.getReturnType()==String[].class){Object array=mm.invoke(null);v=array==null?NIL:"NONNULL";}
      else if(mm.getReturnType()==int.class)v=mm.invoke(null);
     }
    }catch(Throwable t){String target=targets.get(n);if(errors.add(owner+":"+target+":"+t))System.err.println("DECODE GUARD "+owner+" "+target+" "+t+" cause="+t.getCause());if(target==null)targets.put(n,"UNRESOLVED:"+t.getClass().getSimpleName());}
   }return cv(base,v);
  }
  public BasicValue merge(BasicValue a,BasicValue b){if(a.equals(b))return a;return cv(super.merge(a,b),null);}
 }
 static Frame<BasicValue>[] frames(ClassNode c,MethodNode m,Map<Integer,Object> params)throws Exception{return new Analyzer<BasicValue>(new Const(c.name,params)).analyze(c.name,m);}
 static AbstractInsnNode literal(Object v){return v==NIL?new InsnNode(ACONST_NULL):new LdcInsnNode(v);}
 static Boolean branch(int op,Object x,Object y){if(x==null)return null;if(op==IFNULL)return x==NIL;if(op==IFNONNULL)return x!=NIL;if(!(x instanceof Number))return null;int a=((Number)x).intValue();switch(op){case IFEQ:return a==0;case IFNE:return a!=0;case IFLT:return a<0;case IFLE:return a<=0;case IFGT:return a>0;case IFGE:return a>=0;}
 if(y instanceof Number){int b=((Number)y).intValue();switch(op){case IF_ICMPEQ:return a==b;case IF_ICMPNE:return a!=b;case IF_ICMPLT:return a<b;case IF_ICMPGE:return a>=b;case IF_ICMPGT:return a>b;case IF_ICMPLE:return a<=b;}}return null;}
 static void simplify(ClassNode c,MethodNode m,Map<Integer,Object> params)throws Exception {
  m.tryCatchBlocks.clear();
  for(int round=0;round<7;round++){
   Frame<BasicValue>[] fs=frames(c,m,params);AbstractInsnNode[] ns=m.instructions.toArray();boolean changed=false;
   for(int i=0;i<ns.length;i++){AbstractInsnNode n=ns[i];Frame<BasicValue> f=fs[i];if(f==null){if(n.getOpcode()>=0){m.instructions.remove(n);changed=true;}continue;}
    int op=n.getOpcode();Object v=null;
    if(n instanceof VarInsnNode&&op>=ILOAD&&op<=ALOAD)v=val(f.getLocal(((VarInsnNode)n).var));
    else if(op==GETSTATIC&&n instanceof FieldInsnNode&&((FieldInsnNode)n).owner.equals(c.name)&&((FieldInsnNode)n).desc.equals("J"))try{v=field(c.name,((FieldInsnNode)n).name);}catch(Exception ignored){}
    if(v!=null){m.instructions.set(n,literal(v));changed=true;continue;}
    if(n instanceof JumpInsnNode&&op!=GOTO&&op!=JSR){boolean two=op>=IF_ICMPEQ&&op<=IF_ACMPNE;int s=f.getStackSize();Boolean take=branch(op,val(f.getStack(s-(two?2:1))),two?val(f.getStack(s-1)):null);if(take!=null){m.instructions.insertBefore(n,new InsnNode(two?POP2:POP));if(take)m.instructions.set(n,new JumpInsnNode(GOTO,((JumpInsnNode)n).label));else m.instructions.remove(n);changed=true;}}
    if(n instanceof InvokeDynamicInsnNode){InvokeDynamicInsnNode d=(InvokeDynamicInsnNode)n;Type[] at=Type.getArgumentTypes(d.desc);List<BasicValue> as=new ArrayList<>();for(int k=0;k<at.length;k++)as.add(f.getStack(f.getStackSize()-at.length+k));BasicValue bv=new Const(c.name,params).naryOperation(n,as);Object value=val(bv);if(value!=null){for(int k=at.length-1;k>=0;k--)m.instructions.insertBefore(n,new InsnNode(at[k].getSize()==2?POP2:POP));m.instructions.set(n,literal(value));changed=true;}}
   }if(!changed)break;
  }
 }
 static String name(String s){return s.replaceAll("[^a-zA-Z0-9_]","_");}
 static Map<String,Map<Integer,Object>> eventSeeds=new HashMap<>();
 static Map<String,Map<Integer,Object>> helperSeeds=new HashMap<>();
 static String descriptor(String target){String[] p=target.split(" ");StringBuilder b=new StringBuilder("(");for(int i=1;i<p.length;i++){String t=p[i];String d;switch(t){case "java/lang/Long":d="J";break;case "java/lang/Integer":d="I";break;case "java/lang/Short":d="S";break;case "java/lang/Byte":d="B";break;case "java/lang/Character":d="C";break;case "java/lang/Float":d="F";break;case "java/lang/Double":d="D";break;case "java/lang/Boolean":d="Z";break;case "java/lang/Void":d="V";break;default:d=t.startsWith("[")?t.replace('.','/'):"L"+t.replace('.','/')+";";}if(i==p.length-1)b.append(')');b.append(d);}return b.toString();}
 static void trace(String owner,MethodNode method,Map<Integer,Object> params,int depth)throws Exception {
  if(depth>5)return;
  ClassNode c=loader.read(owner);simplify(c,method,params);Frame<BasicValue>[] f=frames(c,method,params);AbstractInsnNode[] ns=method.instructions.toArray();
  for(int i=0;i<ns.length;i++)if(f[i]!=null&&ns[i] instanceof InvokeDynamicInsnNode){InvokeDynamicInsnNode d=(InvokeDynamicInsnNode)ns[i];String t=targets.get(d);if(t==null||t.startsWith("UNRESOLVED")||!t.startsWith("net."))continue;
   String qualified=t.split(" ")[0];int dot=qualified.lastIndexOf('.');String to=qualified.substring(0,dot).replace('.','/'),mn=qualified.substring(dot+1),desc=descriptor(t);Type[] types=Type.getArgumentTypes(d.desc);int count=types.length-2;
   if(count==0)continue;
   int local=1;Map<Integer,Object> values=new HashMap<>();boolean has=false;
   for(int k=1;k<count;k++){Object v=val(f[i].getStack(f[i].getStackSize()-types.length+k));if(v!=null){values.put(local,v);has=true;}local+=types[k].getSize();}
   if(!has)continue;
   String id=to+"/"+mn+desc;
   if(to.equals(owner)){for(MethodNode mm:c.methods)if(mm.name.equals(mn)&&mm.desc.equals(desc))trace(to,mm,values,depth+1);}
   else if(Arrays.asList("47hi","47Vc","47rn","47mz","47od","47Rz","47FN","41Q").contains(to.substring(4))){eventSeeds.put(mn+desc,values);System.out.println("EVENT "+owner+" -> "+id+" "+values);for(MethodNode mm:loader.read(to).methods)if(mm.name.equals(mn)&&mm.desc.equals(desc)&&(mm.access&ACC_ABSTRACT)==0)trace(to,mm,values,depth+1);}
  }
 }
 static boolean keyInt(String owner,String name,String desc,int local) {
  try {for(MethodNode m:loader.read(owner).methods)if(m.name.equals(name)&&m.desc.equals(desc)) {
   for(AbstractInsnNode n:m.instructions)if(n instanceof VarInsnNode&&n.getOpcode()==ILOAD&&((VarInsnNode)n).var==local) {
    AbstractInsnNode next=n.getNext();while(next!=null&&next.getOpcode()<0)next=next.getNext();
    if(next!=null&&next.getOpcode()==I2L)return true;
   }
  }}catch(Exception ignored){}return false;
 }
 static void findEvents()throws Exception {
  Set<String> interfaces=new HashSet<>(Arrays.asList("47hi","47Vc","47rn","47mz","47od","47Rz","47FN","41Q"));
  for(JarEntry e:Collections.list(loader.jar.entries()))if(e.getName().startsWith("net/")&&e.getName().endsWith(".class")){
   ClassNode c=new ClassNode();new ClassReader(loader.jar.getInputStream(e)).accept(c,0);boolean match=false;
   for(MethodNode m:c.methods)for(String iface:interfaces)if(m.desc.contains("Lnet/"+iface+";"))match=true;
   if(!match)continue;
   for(MethodNode m:c.methods)if(m.name.equals("call")&&m.desc.equals("(Ljava/lang/Object;)V"))try{trace(c.name,m,new HashMap<>(),0);}catch(Throwable error){System.out.println("EVENT ERROR "+c.name+" "+error);}
  }
 }
 public static void main(String[] args)throws Exception {
  loader=new Loader(args[0]);for(String n:new String[]{"4f","4d","47Gw","47GJ"})cls("net/"+n);
  findEvents();
  String[] classes={"47GN","47Gk","47GT","47GA","47GX","47GO","47G7","47Gz","47Gl","47G_","47GQ","47GC","47GW","47Gm","47Gn","47G6","47GH","47GJ","47Gw","47ob","47Cc"};
  if(args.length>2){String[] extra=args[2].split(",");String[] combined=Arrays.copyOf(classes,classes.length+extra.length);System.arraycopy(extra,0,combined,classes.length,extra.length);classes=combined;}
  Path out=Paths.get(args[1]);Files.createDirectories(out);
  long obKey=122251103110617L^123519551447496L;
  System.out.println("event dispatcher effective key="+obKey);
  System.out.println("47 event target="+decode("net/47ob",-6624544549341309208L,obKey));
  System.out.println("41 event target="+decode("net/47ob",-6624158773412364409L,obKey));
  System.out.println("40 event target="+decode("net/47ob",-6624211254331053941L,obKey));
  System.out.println("48 event target="+decode("net/47ob",-6624056759907748655L,obKey));
  System.out.println("42 event target="+decode("net/47ob",-6623949346357783676L,obKey));
  for(int pass=0;pass<3;pass++)for(String shortName:classes) {
   String owner="net/"+shortName;System.out.println("PREPASS "+pass+" "+shortName);
   if(shortName.equals("47Ts")){cls("net/47OX");cls("net/47Ti");}
   cls(owner);ClassNode preClass=loader.read(owner);
   for(MethodNode method:preClass.methods) {
    if(method.name.equals("<clinit>")||((method.access&ACC_STATIC)!=0&&!method.name.startsWith("4")))continue;
    Map<Integer,Object> seeds=new HashMap<>(eventSeeds.getOrDefault(method.name+method.desc,Collections.emptyMap()));
    seeds.putAll(helperSeeds.getOrDefault(owner+"/"+method.name+method.desc,Collections.emptyMap()));
    simplify(preClass,method,seeds);
    Frame<BasicValue>[] pre=frames(preClass,method,seeds);AbstractInsnNode[] ns=method.instructions.toArray();
    for(int i=0;i<ns.length;i++)if(pre[i]!=null&&ns[i] instanceof InvokeDynamicInsnNode) {
     InvokeDynamicInsnNode call=(InvokeDynamicInsnNode)ns[i];String target=targets.get(call);
     if(target==null||!target.startsWith("net."))continue;
     String qualified=target.split(" ")[0];int dot=qualified.lastIndexOf('.');String to=qualified.substring(0,dot).replace('.','/'),mn=qualified.substring(dot+1),desc=descriptor(target);
     Type[] at=Type.getArgumentTypes(call.desc);boolean stat=Type.getArgumentTypes(desc).length==at.length-2;int local=stat?0:1;
     Map<Integer,Object> values=new HashMap<>();
     for(int k=stat?0:1;k<at.length-2;k++) {
      Object value=val(pre[i].getStack(pre[i].getStackSize()-at.length+k));
      if(value instanceof Long || (value instanceof Integer&&at[k].getSort()!=Type.BOOLEAN&&keyInt(to,mn,desc,local)))values.put(local,value);
      local+=at[k].getSize();
     }
     if(!values.isEmpty())helperSeeds.put(to+"/"+mn+desc,values);
    }
   }
  }
  for(String shortName:classes){String owner="net/"+shortName;ClassNode c=loader.read(owner);if(shortName.equals("47Ts")){cls("net/47OX");cls("net/47Ti");}cls(owner);List<MethodNode> added=new ArrayList<>();StringBuilder log=new StringBuilder();
   for(int seedPass=0;seedPass<3;seedPass++)for(MethodNode m:c.methods){if(m.name.equals("<clinit>")||((m.access&ACC_STATIC)!=0&&!m.name.startsWith("4")))continue;Map<Integer,Object> p=new HashMap<>(eventSeeds.getOrDefault(m.name+m.desc,Collections.emptyMap()));
    p.putAll(helperSeeds.getOrDefault(owner+"/"+m.name+m.desc,Collections.emptyMap()));
    if(m.desc.equals("(JLnet/47ob;)V")&&m.name.equals("47"))p.put(1,obKey^51599676047634L);
    if(m.desc.equals("(JLnet/47ob;)V")&&m.name.equals("41"))p.put(1,obKey^30264193923112L);
    if(m.desc.equals("(JLnet/47ob;)V")&&m.name.equals("42"))p.put(1,obKey^136870551007572L);
    if(m.desc.equals("(Lnet/47ob;J)V")&&m.name.equals("48"))p.put(2,obKey^129722613145023L);
    Frame<BasicValue>[] pre=frames(c,m,p);AbstractInsnNode[] preN=m.instructions.toArray();
    for(int i=0;i<preN.length;i++)if(pre[i]!=null&&preN[i] instanceof InvokeDynamicInsnNode){InvokeDynamicInsnNode d=(InvokeDynamicInsnNode)preN[i];String t=targets.get(d);if(t==null||!t.startsWith("net."))continue;String qualified=t.split(" ")[0];int dot=qualified.lastIndexOf('.');String to=qualified.substring(0,dot).replace('.','/'),mn=qualified.substring(dot+1),desc=descriptor(t);Type[] at=Type.getArgumentTypes(d.desc);boolean stat=Type.getArgumentTypes(desc).length==at.length-2;int local=stat?0:1;Map<Integer,Object> values=new HashMap<>();for(int k=stat?0:1;k<at.length-2;k++){Object v=val(pre[i].getStack(pre[i].getStackSize()-at.length+k));if(v instanceof Long || (v instanceof Integer && at[k].getSort()!=Type.BOOLEAN&&keyInt(to,mn,desc,local)))values.put(local,v);local+=at[k].getSize();}if(!values.isEmpty())helperSeeds.put(to+"/"+mn+desc,values);}
    simplify(c,m,p);
    log.append("\nMETHOD ").append(m.name).append(m.desc).append("\n");
    for(AbstractInsnNode n:m.instructions.toArray())if(n instanceof InvokeDynamicInsnNode){InvokeDynamicInsnNode d=(InvokeDynamicInsnNode)n;String target=targets.get(n);log.append(target).append(' ').append(d.desc).append('\n');if(target!=null&&!target.startsWith("UNRESOLVED")){String nm="resolved_"+name(target);MethodNode stub=new MethodNode(ACC_PUBLIC|ACC_STATIC|ACC_NATIVE,nm,d.desc,null,null);if(added.stream().noneMatch(a->a.name.equals(nm)&&a.desc.equals(d.desc)))added.add(stub);m.instructions.set(n,new MethodInsnNode(INVOKESTATIC,owner,nm,d.desc,false));}}
   }
   c.methods.addAll(added);Files.writeString(out.resolve(shortName+".targets.txt"),log.toString());
   // Analysis output only: no class initialization or crypto helpers are retained.
   c.methods.removeIf(m->m.name.equals("<clinit>")||((m.access&ACC_STATIC)!=0&&!m.name.startsWith("resolved_")&&!m.name.startsWith("4")));
   for(MethodNode mm:c.methods)for(AbstractInsnNode nn:mm.instructions.toArray())if(nn instanceof FrameNode)mm.instructions.remove(nn);
   ClassWriter w=new ClassWriter(0);c.accept(new ClassRemapper(w,new Remapper(){ public String map(String n){return n.startsWith("net/")?"ref/C_"+n.substring(4):n;} public String mapFieldName(String o,String n,String d){return o.startsWith("net/")?"f_"+name(n):n;}public String mapMethodName(String o,String n,String d){return o.startsWith("net/")&&!n.startsWith("<")&&!n.startsWith("resolved_")?"m_"+name(n):n;}}));Files.write(out.resolve(shortName+".class"),w.toByteArray());
   System.out.println(shortName+" "+added.size()+" resolved targets");
  }
 }
}
