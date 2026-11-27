import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.lang.reflect.*;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;

/** Source: 47Cc / 47GJ. Settings never share the legacy velocity.zen namespace. */
public final class AugustusVelocity {
    static final LinkedHashMap<String, Option> OPTIONS = new LinkedHashMap<String, Option>();
    static final Random RANDOM = new Random();
    static LongSupplier clock=System::currentTimeMillis;
    static long now() {return clock.getAsLong();}
    static int groundTicks;
    // 47Cc.42 is reset by module initialization, not each tick or each knockback.
    static int attackReductions;
    static boolean backwards;
    static final int[] cancelRemaining = new int[3];
    private static final Deque<AttackReduceFeature.Packet> lagQueue = new ArrayDeque<AttackReduceFeature.Packet>();
    interface Outbound {Object packet();void send();void discard();}
    private static final Deque<Outbound> outgoingQueue=new ArrayDeque<Outbound>();
    private static boolean flushing;
    private static long lagStart = Long.MIN_VALUE;
    public static final class Option {
        public final String key, label, mode;
        public final double initial, min, max;
        public final int decimals;
        public final String[] choices;
        public final boolean toggle;
        public double value;
        BooleanSupplier condition;
        Option(String m, String k, String l, double v, double lo, double hi, int d, boolean b, String[] c) {
            mode=m; key=m+"."+k; label=sourceLabel(m,l);
            initial=value=v; min=lo; max=hi; decimals=d; toggle=b; choices=c;
            OPTIONS.put(key,this);
        }
        Option when(BooleanSupplier test) { condition=test; return this; }
        public boolean visible() { return AttackReduceFeature.getMode()>=3 && (mode.equals("common") || AttackReduceFeature.getSummary().equals(mode)) && (condition==null || condition.getAsBoolean()); }
        public void set(double v) { value=Double.isNaN(v)||Double.isInfinite(v)?initial:Math.max(min,Math.min(max,Math.rint(v*Math.pow(10,decimals))/Math.pow(10,decimals))); OpenMaverFeatures.markDirty();
            AntiKBMode active=AttackReduceFeature.current();if(active instanceof AugustusJumpMode)((AugustusJumpMode)active).settingChanged(key);
        }
    }
    static String sourceLabel(String mode,String label) {
        if(mode.equals("CustomMotion"))return "XZ"+label;
        if(mode.equals("Basic")) {
            if(label.startsWith("XZExplosion"))return label.replace("XZExplosion","XZVelocity")+"Explosion";
            if(label.startsWith("YExplosion"))return label.replace("YExplosion","YVelocity")+"Explosion";
            if(label.startsWith("CancelExplosion"))return label.replace("CancelExplosion","Cancel")+"Explosion";
        }
        return label;
    }
    static Option num(String m,String k,double v,double lo,double hi,int d) { return new Option(m,k,k,v,lo,hi,d,false,null); }
    static Option bool(String m,String k,boolean v) { return new Option(m,k,k,v?1:0,0,1,0,true,null); }
    static Option select(String m,String k,int v,String... c) { return new Option(m,k,k,v,0,c.length-1,0,false,c); }
    static double value(String m,String k) { return OPTIONS.get(m+"."+k).value; }
    static boolean enabled(String m,String k) { return value(m,k)>0.5; }
    public static Collection<Option> options() { return Collections.unmodifiableCollection(OPTIONS.values()); }
    static {
        bool("common","NotWhileSPressed",false); bool("common","NotWhileLongJump",false);
        num("common","Lag",0,0,400,0); bool("common","IgnoreFire",true);
        bool("common","CancelDamageSoundsFromOtherPlayers",false);
        for(String p:new String[]{"S00PacketKeepAlive","S32PacketConfirmTransaction","S48PacketResourcePackSend"}) {
            select("common",p+"CancelCondition",0,"None","OnlyOnGround","OnlyInAir","Always");
            num("common",p+"PacketCount",6,1,10,0).when(()->value("common",p+"CancelCondition")!=0);
        }
        for(String phase:new String[]{"InAir","OnGround"}) {
            num("Basic","XZVelocity"+phase,0,-100,100,0); num("Basic","YVelocity"+phase,0,-100,100,0);
            String cancel=phase.equals("InAir")?"CancelAir":"CancelGround";
            bool("Basic",cancel,true).when(()->value("Basic","XZVelocity"+phase)==0||value("Basic","YVelocity"+phase)==0);
        }
        bool("Basic","Explosion",true);
        for(String phase:new String[]{"InAir","OnGround"}) {
            num("Basic","XZExplosion"+phase,0,-100,100,0).when(()->enabled("Basic","Explosion"));
            num("Basic","YExplosion"+phase,0,-100,100,0).when(()->enabled("Basic","Explosion"));
            bool("Basic",phase.equals("InAir")?"CancelExplosionAir":"CancelExplosionGround",true).when(()->enabled("Basic","Explosion") && (value("Basic","XZExplosion"+phase)==0||value("Basic","YExplosion"+phase)==0));
        }
        bool("Basic","Intervals",false);
        num("Basic","IntervalVelocity",1,1,10,0).when(()->enabled("Basic","Intervals"));
        num("Basic","IntervalExplosion",1,1,10,0).when(()->enabled("Basic","Intervals")&&enabled("Basic","Explosion"));
        jumpSettings("Legit",true); jumpSettings("Intave",false);
        bool("Legit","JumpIntervalNotInARow",false).when(()->value("Legit","JumpDelayMode")==0);
        bool("Legit","JumpIntervalOutliers",false).when(()->value("Legit","JumpDelayMode")==0);
        num("Legit","JumpIntervalOutlierValueMin",0,0,5,0).when(()->value("Legit","JumpDelayMode")==0&&enabled("Legit","JumpIntervalOutliers"));
        num("Legit","JumpIntervalOutlierValueMax",3,0,5,0).when(()->value("Legit","JumpDelayMode")==0&&enabled("Legit","JumpIntervalOutliers"));
        num("Legit","JumpIntervalOutlierDelayMin",0,0,100,0).when(()->value("Legit","JumpDelayMode")==0&&enabled("Legit","JumpIntervalOutliers"));
        num("Legit","JumpIntervalOutlierDelayMax",30,0,100,0).when(()->value("Legit","JumpDelayMode")==0&&enabled("Legit","JumpIntervalOutliers"));
        bool("Legit","JumpIntervalNormalDistribution",false).when(()->value("Legit","JumpDelayMode")==0);
        num("Legit","JumpIntervalSigma",1,.5,5,2).when(()->value("Legit","JumpDelayMode")==0&&enabled("Legit","JumpIntervalNormalDistribution"));
        num("Legit","JumpIntervalCenter",50,0,100,0).when(()->value("Legit","JumpDelayMode")==0&&enabled("Legit","JumpIntervalNormalDistribution"));
        num("PushGround","HurtTimeMin",1,1,10,0); num("PushGround","HurtTimeMax",10,1,10,0);
        select("Push","PushMode",0,"Simple","Expert");
        num("Push","PushMotion",1.1,.01,20,2).when(()->value("Push","PushMode")==0);
        num("Push","PushStart",9,1,10,0).when(()->value("Push","PushMode")==0);
        num("Push","PushEnd",2,1,10,0).when(()->value("Push","PushMode")==0);
        bool("Push","OnGround",false).when(()->value("Push","PushMode")==0);
        for(int h=10;h>0;h--) {
            num("Push","PushMotion"+h,0,0,20,2).when(()->value("Push","PushMode")==1);
            bool("Push","PushOnGround"+h,false).when(()->value("Push","PushMode")==1);
        }
        bool("Intave/PolarFlag","Polar",false);
        num("Intave","XZOnHit",.6,-1,1,2); num("Intave","XZOnHitSprint",.6,-1,1,2);
        bool("Intave","IntaveSmart",false); bool("Intave","IntaveBoost",false);
        num("Intave","IntaveBoostDelay",2000,0,10000,0).when(()->enabled("Intave","IntaveBoost"));
        num("Intave","IntaveBoostOnGroundTicks",2,0,10,0).when(()->enabled("Intave","IntaveBoost"));
        num("Intave","XZVelocity",100,-300,300,0).when(()->enabled("Intave","IntaveBoost"));
        num("Intave","YVelocity",100,-100,200,0).when(()->enabled("Intave","IntaveBoost"));
        bool("Intave","SneakInAir",false).when(()->enabled("Intave","IntaveBoost"));
        bool("Intave","Water",true); num("Intave","WaterInterval",1,0,3,0).when(()->enabled("Intave","Water"));
        bool("Intave","Cobweb",true); bool("Intave","Ladder",true);
        bool("Intave","LadderXZOnly",true).when(()->enabled("Intave","Ladder"));
        num("Reverse","XZ-Velocity Strength",100,0,100,0); num("Reverse","Y-Velocity Strength",100,0,100,0);
        num("Reverse","ReverseStart",9,1,10,0); bool("Reverse","ReverseStrafe",false);
        num("Reverse","ReverseStrafeStart",7,1,10,0).when(()->enabled("Reverse","ReverseStrafe"));
        num("Reverse","ReverseStrafeEnd",9,1,10,0).when(()->enabled("Reverse","ReverseStrafe"));
        num("CustomMotion","MotionOnGround",.2,0,1,4); num("CustomMotion","MotionInAir",.2,0,1,4);
        num("BoundingBox","HurtTimeMin",0,0,10,0); num("BoundingBox","HurtTimeMax",10,0,10,0);
        num("BoundingBox","Height",1,0,1,2);
    }
    static void jumpSettings(final String mode, boolean legit) {
        if(!legit) bool(mode,"Jump",false);
        bool(mode,"JumpInInv",false).when(()->legit||enabled(mode,"Jump"));
        select(mode,"JumpDelayMode",0,legit?new String[]{"IntervalDelay","Chance"}:new String[]{"Delay","Tick","Chance"}).when(()->legit||enabled(mode,"Jump"));
        num(mode,"JumpChance",50,0,100,0).when(()->value(mode,"JumpDelayMode")== (legit?1:2));
        String interval=legit?"JumpInterval":"JumpTicks";
        num(mode,interval+"Min",legit?0:1,0,legit?5:10,0).when(()->(legit||enabled(mode,"Jump"))&&value(mode,"JumpDelayMode")== (legit?0:1));
        num(mode,interval+"Max",legit?0:3,0,legit?5:10,0).when(()->(legit||enabled(mode,"Jump"))&&value(mode,"JumpDelayMode")== (legit?0:1));
        String delay=legit?"TickDelayBetweenJumps":"JumpDelay";
        num(mode,delay+"Min",legit?10:0,0,legit?100:2000,0).when(()->(legit||enabled(mode,"Jump"))&&value(mode,"JumpDelayMode")==0);
        num(mode,delay+"Max",legit?10:0,0,legit?100:2000,0).when(()->(legit||enabled(mode,"Jump"))&&value(mode,"JumpDelayMode")==0);
        bool(mode,"CheckFallDamage",true).when(()->legit||enabled(mode,"Jump"));
        num(mode,"FallDistance",legit?3:3.5,1,10,2).when(()->(legit||enabled(mode,"Jump"))&&enabled(mode,"CheckFallDamage"));
        num(mode,"FallDistanceBufferSize",2,1,5,0).when(()->(legit||enabled(mode,"Jump"))&&enabled(mode,"CheckFallDamage"));
        bool(mode,"NotWhileSpeed",true); bool(mode,"NotWhileJumpBoost",true);
    }
    static void load(Properties p) {
        for(Option o:OPTIONS.values()) {
            double v=o.initial;
            try { String s=p.getProperty("velocity.augustus."+o.key); if(s!=null) v=o.toggle?(Boolean.parseBoolean(s)?1:0):Double.parseDouble(s); } catch(RuntimeException ignored) { }
            o.value=Double.isNaN(v)||Double.isInfinite(v)?o.initial:Math.max(o.min,Math.min(o.max,v));
        }
    }
    static String save() { StringBuilder b=new StringBuilder(); for(Option o:OPTIONS.values()) b.append("velocity.augustus.").append(o.key.replace(" ","\\ ")).append('=').append(o.toggle?Boolean.toString(o.value>.5):Double.toString(o.value)).append('\n'); return b.toString(); }
    static void reset() {
        groundTicks=attackReductions=0; backwards=false; lagQueue.clear(); lagStart=Long.MIN_VALUE; Arrays.fill(cancelRemaining,0);
        Outbound out;while((out=outgoingQueue.poll())!=null)out.discard();
    }
    static void tick() {
        if(AttackReduceFeature.getMode()<3) return;
        if(!holding()) flushLag();
    }
    static void input(MovementInput input) { backwards=input.moveForward<0; }
    static boolean allowed() {
        return p()!=null && !(enabled("common","NotWhileSPressed")&&backwards)
            && !(enabled("common","IgnoreFire")&&truth("isBurning","func_70027_ad"));
    }
    static boolean allowedMain() { return allowed() && !(enabled("common","NotWhileLongJump")&&AttackReduceFeature.moduleEnabled("LongJump")); }
    static void receive(AttackReduceFeature.ReceivePacketEvent e) {
        // Normal receive listeners run only when the network lag event has released a packet.
        if(allowedMain() && AttackReduceFeature.ownVelocity(e.packet) && value("common","Lag")>0) lagStart=now();
        if(AttackReduceFeature.current() instanceof AugustusIntave)((AugustusIntave)AttackReduceFeature.current()).lagEvent(e.packet);
        if(holding()) { lagQueue.add(e.packet); e.cancel(); if(lagQueue.size()>=AttackReduceFeature.MAX_PENDING) flushLag(); return; }
        flushLag(); dispatch(e);
    }
    static boolean holding() {
        if(flushing||ticks()<=20)return false;
        boolean common=allowedMain()&&elapsed(lagStart)<value("common","Lag");
        AntiKBMode mode=AttackReduceFeature.current();
        boolean boost=mode instanceof AugustusIntave&&((AugustusIntave)mode).holdingBoost();
        return common||boost;
    }
    static void outbound(Outbound out) {
        Object raw=out.packet();
        if(AttackReduceFeature.current() instanceof AugustusFlag&&AttackReduceFeature.packetName(raw,"C03PacketPlayer")
            &&AttackReduceFeature.flag(raw,"moving","field_149480_h"))((AugustusFlag)AttackReduceFeature.current()).outgoingPosition();
        // A replayed inbound packet may generate an acknowledgement synchronously.
        // Append it behind previously held outbound packets, including during a flush.
        if(flushing||holding()) {outgoingQueue.add(out);if(outgoingQueue.size()>=AttackReduceFeature.MAX_PENDING)flushLag();}
        else {flushLag();out.send();}
    }
    static void dispatch(AttackReduceFeature.ReceivePacketEvent e) {
        AttackReduceFeature.current().onReceivePacket(e);
        if(!allowedMain()) return;
        String[] names={"S00PacketKeepAlive","S32PacketConfirmTransaction","S48PacketResourcePackSend"};
        if(ticks()>20) {
            if(AttackReduceFeature.ownVelocity(e.packet)&&e.packet.x()!=0&&e.packet.z()!=0) {
                for(int i=0;i<3;i++) if(cancelCondition(names[i])) cancelRemaining[i]=(int)value("common",names[i]+"PacketCount");
            } else for(int i=0;i<3;i++) if(e.packet.is(names[i])&&cancelCondition(names[i])&&cancelRemaining[i]>0) { cancelRemaining[i]--;e.cancel(); }
        }
        if(enabled("common","CancelDamageSoundsFromOtherPlayers")&&e.packet.is("S29PacketSoundEffect")) {
            Object name=AttackReduceFeature.call(e.packet.raw,"getSoundName","func_149212_c");
            if("game.player.hurt".equalsIgnoreCase(String.valueOf(name))||"game.player.die".equalsIgnoreCase(String.valueOf(name))) e.cancel();
        }
    }
    static boolean cancelCondition(String n) { int v=(int)value("common",n+"CancelCondition"); return v==3||v==1&&AttackReduceFeature.onGround()||v==2&&!AttackReduceFeature.onGround(); }
    static void flushLag() {
        if(flushing)return;flushing=true;
        try {
            AttackReduceFeature.Packet p;while((p=lagQueue.poll())!=null) { AttackReduceFeature.ReceivePacketEvent e=new AttackReduceFeature.ReceivePacketEvent(p);dispatch(e);if(!e.cancelled)p.handle(); }
            Outbound out;while((out=outgoingQueue.poll())!=null)out.send();
        } finally {flushing=false;}
    }
    static void releaseLag() { flushLag(); lagStart=Long.MIN_VALUE; }
    static long elapsed(long since) { return since==Long.MIN_VALUE?Long.MAX_VALUE:Math.max(0,now()-since); }
    static Object p() { return AttackReduceFeature.player(); }
    static Object world() { return AttackReduceFeature.read(AttackReduceFeature.mc(),"theWorld","field_71441_e"); }
    static double n(String m,String s) { return AttackReduceFeature.number(p(),m,s); }
    static int hurt() { return (int)n("hurtTime","field_70737_aN"); }
    static int ticks() { return (int)n("ticksExisted","field_70173_aa"); }
    static boolean truth(String m,String s) { return AttackReduceFeature.truth(p(),m,s); }
    static void motion(double x,double z) { AttackReduceFeature.set(p(),"motionX","field_70159_w",x); AttackReduceFeature.set(p(),"motionZ","field_70179_y",z); }
    static void ground() { write(p(),"onGround","field_70122_E",true); }
    static void write(Object obj,String m,String s,Object v) { Field f=OpenMaverFeatures.findField(obj,new String[]{m,s}); try { if(f!=null)f.set(obj,v); }catch(Exception ignored) { } }
    static MovementInput input() { Object in=AttackReduceFeature.read(p(),"movementInput","field_71158_b");return in instanceof MovementInput?(MovementInput)in:null; }
    static double direction() {
        MovementInput in=input(); float yaw=(float)n("rotationYaw","field_70177_z");
        if(in==null)return Math.toRadians(yaw);
        float forward=in.moveForward, strafe=in.moveStrafe;
        if(forward<0) yaw+=180;
        float f=forward<0?-.5f:forward>0?.5f:1;
        if(strafe>0)yaw-=90*f; if(strafe<0)yaw+=90*f;
        return Math.toRadians(yaw);
    }
    static void strafe(double speed) { double d=direction();motion(-Math.sin(d)*speed,Math.cos(d)*speed); }
    static void push(double amount) { float d=(float)Math.toDegrees(direction())*((float)Math.PI/180); motion(n("motionX","field_70159_w")-AugustusPrediction.sin(d)*amount,n("motionZ","field_70179_y")+AugustusPrediction.cos(d)*amount); }
    static boolean moving() { return n("moveForward","field_70701_bs")!=0 || n("moveStrafing","field_70702_br")!=0; }
    static boolean collided() { return AttackReduceFeature.flag(p(),"isCollidedHorizontally","field_70123_F"); }
    static BlockPos pos(double dy) { return new BlockPos(n("posX","field_70165_t"),n("posY","field_70163_u")+dy,n("posZ","field_70161_v")); }
    static Object block(BlockPos pos) { Object state=AttackReduceFeature.call(world(),"getBlockState","func_180495_p",pos);return AttackReduceFeature.call(state,"getBlock","func_177230_c"); }
    static boolean air(BlockPos pos) { return AttackReduceFeature.truth(world(),"isAirBlock","func_175623_d",pos); }
    static AxisAlignedBB box() { Object v=AttackReduceFeature.call(p(),"getEntityBoundingBox","func_174813_aQ");return v instanceof AxisAlignedBB?(AxisAlignedBB)v:null; }
    static AxisAlignedBB blockBox(BlockPos pos) {
        Object state=AttackReduceFeature.call(world(),"getBlockState","func_180495_p",pos);
        Object b=AttackReduceFeature.call(state,"getBlock","func_177230_c");
        Object bb=AttackReduceFeature.call(b,"getCollisionBoundingBox","func_180646_a",world(),pos,state);return bb instanceof AxisAlignedBB?(AxisAlignedBB)bb:null;
    }
    static boolean intersects(AxisAlignedBB a,AxisAlignedBB b) { return a!=null&&b!=null&&a.maxX>b.minX&&a.minX<b.maxX&&a.maxY>b.minY&&a.minY<b.maxY&&a.maxZ>b.minZ&&a.minZ<b.maxZ; }
    static void position(double y) { AttackReduceFeature.call(p(),"setPosition","func_70107_b",n("posX","field_70165_t"),y,n("posZ","field_70161_v")); }
    static Object construct(String name,Object... args) {
        try { Class<?> c=Class.forName("net.minecraft."+name,true,p().getClass().getClassLoader());
            for(Constructor<?> ct:c.getConstructors()) if(ct.getParameterTypes().length==args.length)try{return ct.newInstance(args);}catch(IllegalArgumentException ignored) { }
        } catch(Exception ex) { OpenMaverDebug.noticeOnce("velocity.packet."+name,"Velocity packet unavailable: "+ex); } return null;
    }
    static void send(Object packet) { if(packet!=null) { Object q=AttackReduceFeature.read(p(),"sendQueue","field_71174_a");AttackReduceFeature.call(q,"addToSendQueue","func_147297_a",packet); } }
    static void scale(AttackReduceFeature.Packet p,double xz,double y) { AttackReduceFeature.set(p.raw,"motionX","field_149415_b",(int)(p.x()*xz/100));AttackReduceFeature.set(p.raw,"motionY","field_149416_c",(int)(p.y()*y/100));AttackReduceFeature.set(p.raw,"motionZ","field_149414_d",(int)(p.z()*xz/100)); }
}

abstract class AugustusMode extends AntiKBMode {
    final String name;
    AugustusMode(String name) { this.name=name; }
    double v(String k) { return AugustusVelocity.value(name,k); }
    boolean b(String k) { return AugustusVelocity.enabled(name,k); }
    boolean allowed() { return AugustusVelocity.allowed(); }
    @Override void onDisable() {packetQueue.clear();isSuspending=false;}
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) { }
    @Override void release() { AugustusVelocity.releaseLag(); super.release(); }
    void preMotion() { }
    void postMotion() { }
    double[] move(double x,double y,double z) { return new double[]{x,y,z}; }
    AxisAlignedBB collision(BlockPos pos) { return null; }
}
