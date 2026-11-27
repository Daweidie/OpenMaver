import java.io.StringReader;
import java.util.*;
import io.netty.channel.ChannelFuture;
import module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

/** Source-derived packet, input and collision boundaries, with a real Netty pipeline. */
public final class AugustusVelocityTest {
    static int checks;
    static long time=10000;
    static void check(String message,boolean condition) {checks++;if(!condition)throw new AssertionError(message);}
    static void eq(String message,double expected,double actual) {check(message+" expected="+expected+" actual="+actual,Math.abs(expected-actual)<1e-7);}
    static void set(String mode,String key,double value) {AugustusVelocity.OPTIONS.get(mode+"."+key).set(value);}
    public static class State {final Block block;State(Block b){block=b;}public Block getBlock(){return block;}}
    public static class Block {
        public float slipperiness=.6f;
        final AxisAlignedBB bounds;
        Block(AxisAlignedBB b){bounds=b;}
        public AxisAlignedBB getCollisionBoundingBox(Object world,BlockPos p,State state){return bounds;}
    }
    public static class World {
        final Map<String,Block> blocks=new HashMap<String,Block>();
        final List<AxisAlignedBB> obstacles=new ArrayList<AxisAlignedBB>();
        final Map<Integer,Object> entities=new HashMap<Integer,Object>();
        String key(BlockPos p){return p.getX()+","+p.getY()+","+p.getZ();}
        void block(int x,int y,int z,AxisAlignedBB box){blocks.put(x+","+y+","+z,new Block(box));if(box!=null)obstacles.add(box);}
        public State getBlockState(BlockPos p){Block b=blocks.get(key(p));return new State(b==null?new Block(null):b);}
        public boolean isAirBlock(BlockPos p){return !blocks.containsKey(key(p));}
        public List<AxisAlignedBB> getCollidingBoundingBoxes(Object entity,AxisAlignedBB q){List<AxisAlignedBB> result=new ArrayList<AxisAlignedBB>();for(AxisAlignedBB b:obstacles)if(AugustusVelocity.intersects(q,b))result.add(b);return result;}
        public Object getEntityByID(int id){return entities.get(id);}
    }
    public static class S27PacketExplosion extends ZenAntiKBTest.PacketStub {
        public float field_149152_f=.4f,field_149153_g=.3f,field_149159_h=-.2f;
    }
    public static class S48PacketResourcePackSend extends ZenAntiKBTest.PacketStub { }
    public static class S29PacketSoundEffect extends ZenAntiKBTest.PacketStub {
        String name;S29PacketSoundEffect(String n){name=n;}public String getSoundName(){return name;}
    }
    public static class S19PacketEntityStatus extends ZenAntiKBTest.PacketStub {
        final Object entity;S19PacketEntityStatus(Object p){entity=p;}
        public Object getEntity(Object world){return entity;}public byte getOpCode(){return 2;}
    }
    static ZenAntiKBTest.Fixture fixture(String name,World world) {
        int mode=Arrays.asList(AttackReduceFeature.MODE_NAMES).indexOf(name);
        ZenAntiKBTest.Fixture f=new ZenAntiKBTest.Fixture(mode,false,world);
        f.player.height=1.8f;world.entities.put(7,f.player);world.entities.put(8,f.target);
        return f;
    }
    static void packetModes() throws Exception {
        try(ZenAntiKBTest.Fixture f=fixture("Basic",new World())) {
            f.player.motionX=.7;f.player.motionY=.6;f.player.motionZ=.5;
            f.receive(f.velocity());eq("Basic cancelled zero X preserves prior motion",.7,f.player.motionX);eq("Basic cancelled zero Y preserves prior motion",.6,f.player.motionY);
            set("Basic","CancelGround",0);f.receive(f.velocity());eq("Basic uncancelled zero X replaces motion",0,f.player.motionX);
            set("Basic","XZVelocityOnGround",-33);set("Basic","YVelocityOnGround",50);
            S12PacketEntityVelocity p=new S12PacketEntityVelocity(7,.400125,.360125,-.200125);f.receive(p);
            check("Basic integer scale truncates toward zero",p.getMotionX()==(int)(3201*-.33));eq("Basic Y half",p.getMotionY()/8000.,f.player.motionY);
            set("Basic","Intervals",1);set("Basic","IntervalVelocity",2);f.receive(f.velocity());eq("Basic fourth interval scales",-.132,f.player.motionX);
            f.receive(f.velocity());eq("Basic fifth interval passes",.4,f.player.motionX);
            set("Basic","Intervals",0);set("Basic","XZExplosionOnGround",50);set("Basic","YExplosionOnGround",-100);
            S27PacketExplosion boom=new S27PacketExplosion();f.receive(boom);eq("explosion float scale",.2f,boom.field_149152_f);eq("explosion negative Y",-.3f,boom.field_149153_g);
            f.player.onGround=false;set("Basic","CancelAir",0);set("Basic","XZVelocityInAir",25);f.receive(f.velocity());eq("Basic independent air parameters",.1,f.player.motionX);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Spoof",new World())) {
            f.player.posX=1;f.player.posY=2;f.player.posZ=3;f.receive(f.velocity());
            check("Spoof cancels application",f.applied.isEmpty());Object out=f.channel.readOutbound();check("Spoof emits C04",out instanceof C03PacketPlayer.C04PacketPlayerPosition);
            eq("Spoof X offset",1.4,AttackReduceFeature.number(out,"x","field_149479_a"));eq("Spoof local X unchanged",0,f.player.motionX);
        }
        try(ZenAntiKBTest.Fixture f=fixture("HypixelTest",new World())) {f.receive(f.velocity());check("Hypixel source cancellation",f.applied.isEmpty());eq("Hypixel impossible vertical path has no jump",0,f.player.motionY);}
        try(ZenAntiKBTest.Fixture f=fixture("Grim-1.17",new World())) {
            f.receive(f.velocity());check("Grim cancels velocity",f.applied.isEmpty());
            AugustusVelocityHooks.preMotion(f.player);Object movement=f.channel.readOutbound();check("Grim sends C06 first",movement instanceof C03PacketPlayer.C06PacketPlayerPosLook);
            eq("Grim real SRG previous X",10,AttackReduceFeature.number(movement,"x","field_149479_a"));eq("Grim real SRG previous Y",20,AttackReduceFeature.number(movement,"y","field_149477_b"));eq("Grim real SRG previous Z",30,AttackReduceFeature.number(movement,"z","field_149478_c"));
            check("Grim sends digging second",f.channel.readOutbound() instanceof net.minecraft.network.play.client.C07PacketPlayerDigging);
            AugustusVelocityHooks.preMotion(f.player);check("Grim pulse is one frame",f.channel.readOutbound()==null);
            f.receive(new ZenAntiKBTest.S08PacketPlayerPosLook());f.receive(f.velocity());check("Grim same-tick correction velocity passes",f.applied.get(f.applied.size()-1) instanceof S12PacketEntityVelocity);
        }
        World world=new World();world.block(0,2,0,new AxisAlignedBB(0,2,0,1,3,1));
        try(ZenAntiKBTest.Fixture f=fixture("PolarBlock",world)) {
            f.target.motionX=.8;f.target.motionZ=.9;
            f.player.sendQueue.inbound=p->{f.applied.add(p);if(p instanceof S12PacketEntityVelocity){S12PacketEntityVelocity v=(S12PacketEntityVelocity)p;f.target.motionX=v.getMotionX()/8000.;f.target.motionY=v.getMotionY()/8000.;f.target.motionZ=v.getMotionZ()/8000.;}};
            f.receive(new S12PacketEntityVelocity(8,.4,.36,-.2));eq("PolarBlock other-entity horizontal preservation",.8,f.target.motionX);eq("PolarBlock other-entity vertical application",.36,f.target.motionY);
        }
    }
    static void movementModes() {
        try(ZenAntiKBTest.Fixture f=fixture("PushGround",new World())) {
            f.player.onGround=false;f.player.hurtTime=1;AugustusVelocityHooks.preUpdate(f.player);check("PushGround min inclusive",f.player.onGround);
            f.player.onGround=false;f.player.hurtTime=0;AugustusVelocityHooks.preUpdate(f.player);check("PushGround outside range",!f.player.onGround);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Push",new World())) {
            f.player.motionZ=0;f.player.hurtTime=9;AugustusVelocityHooks.preUpdate(f.player);eq("Push simple percentage adds",.011,f.player.motionZ);
            set("Push","PushMode",1);set("Push","PushMotion9",.3);set("Push","PushOnGround9",1);f.player.onGround=false;
            AugustusVelocityHooks.preUpdate(f.player);eq("Push expert per-hurt motion",.311,f.player.motionZ);check("Push expert ground",f.player.onGround);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Intave13",new World())) {
            f.player.hurtTime=1;f.player.isCollidedHorizontally=true;AugustusVelocityHooks.preUpdate(f.player);eq("Intave13 float strafe",(double).195f,f.player.motionZ);
            f.player.fallDistance=3;f.player.motionZ=.7;AugustusVelocityHooks.preUpdate(f.player);eq("Intave13 fall strict boundary",.7,f.player.motionZ);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Reverse",new World())) {
            f.player.motionX=.4;f.player.motionZ=-.2;f.player.hurtTime=9;AugustusVelocityHooks.preUpdate(f.player);eq("Reverse negates X",-.4,f.player.motionX);eq("Reverse negates Z",.2,f.player.motionZ);
            set("Reverse","ReverseStrafe",1);f.player.hurtTime=7;AugustusVelocityHooks.preMotion(f.player);eq("Reverse strafe preserves speed",Math.hypot(.4,.2),f.player.motionZ);
            f.receive(f.velocity());eq("Reverse's unregistered receive path stays inactive",.4,f.player.motionX);
        }
        try(ZenAntiKBTest.Fixture f=fixture("CustomMotion",new World())) {
            double[] move=AugustusVelocityHooks.move(f.player,.8,-.1,.9);eq("CustomMotion enabled pulse",.2,move[2]);eq("CustomMotion preserves Y",-.1,move[1]);
            move=AugustusVelocityHooks.move(f.player,.8,-.1,.9);eq("CustomMotion consumes pulse",.9,move[2]);
            f.receive(f.velocity());f.player.onGround=false;set("CustomMotion","MotionInAir",.4);move=AugustusVelocityHooks.move(f.player,0,0,0);eq("CustomMotion packet rearms air pulse",.4,move[2]);
        }
        World edge=new World();edge.obstacles.add(new AxisAlignedBB(-1,-1,-1,.3,0,.3));
        try(ZenAntiKBTest.Fixture f=fixture("SafeWalk",edge)) {
            double[] move=AugustusVelocityHooks.move(f.player,1,0,0);check("SafeWalk .05 clipping",move[0]>.5&&move[0]<.65);
            f.player.hurtTime=0;eq("SafeWalk unhurt pass",1,AugustusVelocityHooks.move(f.player,1,0,0)[0]);
        }
        try(ZenAntiKBTest.Fixture f=fixture("OldMinemen",new World())) {
            f.player.burning=true;for(int i=0;i<5;i++){f.player.hurtTime=9;f.player.motionZ=1;AttackReduceFeature.afterMovementInput(f.player.movementInput);eq("OldMinemen fourth cycle incl ignored fire "+i,i%4==0?(double).2f:1,f.player.motionZ);}
            f.player.hurtTime=5;AttackReduceFeature.afterMovementInput(f.player.movementInput);eq("OldMinemen hurt5 unconditional",(double).1f,f.player.motionZ);
        }
        World floor=new World();floor.block(0,-2,0,new AxisAlignedBB(0,-2,0,1,-1,1));
        try(ZenAntiKBTest.Fixture f=fixture("Karhu",floor)) {
            f.player.motionY=0;f.player.hurtTime=5;AugustusVelocityHooks.preUpdate(f.player);eq("Karhu -2 block downward snap",-10,f.player.motionY);eq("Karhu first alternating strafe",(double).15f,f.player.motionZ);
            f.player.motionZ=1;AugustusVelocityHooks.preUpdate(f.player);eq("Karhu second skips strafe",1,f.player.motionZ);
        }
        try(ZenAntiKBTest.Fixture f=fixture("BoundingBox",new World())) {
            f.player.hurtTime=1;List boxes=AugustusVelocityHooks.collision(new ArrayList(),f.player,new AxisAlignedBB(-.2,1,-.2,.2,2,.2));check("BoundingBox injects queried air boxes",!boxes.isEmpty());
            f.player.hurtTime=0;check("BoundingBox lower bound excludes zero",AugustusVelocityHooks.collision(new ArrayList(),f.player,new AxisAlignedBB(-.2,1,-.2,.2,2,.2)).isEmpty());
            f.player.hurtTime=1;f.mc.theWorld=new World();check("stale-world movement hook has no effect",AugustusVelocityHooks.collision(new ArrayList(),f.player,new AxisAlignedBB(-.2,1,-.2,.2,2,.2)).isEmpty());
        }
    }
    static void inputAndAttack() {
        try(ZenAntiKBTest.Fixture f=fixture("Legit",new World())) {
            set("Legit","CheckFallDamage",0);set("Legit","JumpDelayMode",1);set("Legit","JumpChance",100);f.player.hurtTime=9;
            AttackReduceFeature.afterMovementInput(f.player.movementInput);check("Legit jumps on eligible input",f.player.movementInput.jump);check("Legit claims input",AttackReduceFeature.ownsMovementInput(f.player,f.player.movementInput));
            f.player.movementInput.jump=false;f.player.onGround=false;AttackReduceFeature.afterMovementInput(f.player.movementInput);check("Legit respects ground",!f.player.movementInput.jump);
            set("Legit","CheckFallDamage",1);f.player.onGround=true;f.player.fallDistance=4;f.player.hurtTime=0;AttackReduceFeature.afterMovementInput(f.player.movementInput);f.player.hurtTime=9;f.player.movementInput.jump=false;AttackReduceFeature.afterMovementInput(f.player.movementInput);check("Legit prior fall buffer rejects",!f.player.movementInput.jump);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Intave",new World())) {
            f.player.water=true;set("Intave","WaterInterval",2);f.receive(f.velocity());eq("Intave water first zero",0,f.player.motionX);f.receive(f.velocity());eq("Intave source counter only increments on match",.4,f.player.motionX);
            f.player.water=false;f.player.isInWeb=true;f.receive(f.velocity());eq("Intave cobweb path",0,f.player.motionX);f.player.isInWeb=false;f.player.ladder=true;set("Intave","LadderXZOnly",0);f.receive(f.velocity());eq("Intave ladder full zero",0,f.player.motionY);
            set("Intave","Jump",1);set("Intave","CheckFallDamage",0);set("Intave","JumpDelayMode",2);set("Intave","JumpChance",100);f.player.hurtTime=9;AttackReduceFeature.afterMovementInput(f.player.movementInput);check("Intave separate jump option",f.player.movementInput.jump);
            set("Intave","XZOnHitSprint",.25);f.player.motionX=1;
            AugustusVelocityHooks.beginAttack(f.player);double a=AugustusVelocityHooks.attackSlowdown(f.player,f.target),b=AugustusVelocityHooks.attackSlowdown(f.player,f.target);eq("Intave FIRST cached coefficient",.25,a);eq("Intave both axes same event",a,b);AugustusVelocityHooks.attackMerge(f.player,f.target);AugustusVelocityHooks.endAttack(f.player);
            f.player.hurtTime=9;AugustusVelocityHooks.preUpdate(f.player);AugustusVelocityHooks.beginAttack(f.player);eq("Intave counter persists between ticks",.6,AugustusVelocityHooks.attackSlowdown(f.player,f.target));AugustusVelocityHooks.endAttack(f.player);
            AugustusVelocity.attackReductions=0;set("Intave","XZOnHit",.5);f.player.motionX=1;f.player.motionZ=2;f.player.sprinting=false;
            AugustusVelocityHooks.beginAttack(f.player);AugustusVelocityHooks.attackMerge(f.player,f.target);AugustusVelocityHooks.endAttack(f.player);eq("Intave SECOND X",.5,f.player.motionX);eq("Intave SECOND Z",1,f.player.motionZ);
            f.player.movementInput.jump=false;f.player.movementInput.moveForward=0;f.player.motionX=0;f.player.motionZ=-.4;
            double[] predicted=AugustusPrediction.predict(.5);eq("prediction reduction position",-.2,predicted[2]);eq("prediction leaves live motion unchanged",-.4,f.player.motionZ);
            check("Smart reduction toward target is preferred",AugustusPrediction.improves(.5));f.player.motionZ=.4;check("Smart rejects reduction away from target",!AugustusPrediction.improves(.5));
            set("Intave","IntaveSmart",1);AugustusVelocity.attackReductions=0;AugustusVelocityHooks.beginAttack(f.player);AugustusVelocityHooks.attackMerge(f.player,f.target);AugustusVelocityHooks.endAttack(f.player);eq("Smart affects actual SECOND event",.4,f.player.motionZ);
        }
    }
    static void keepSprintCoexistence() throws Exception {
        module.movement.KeepSprint sprint=new module.movement.KeepSprint();
        ModuleManager.register(sprint);KeepSprintFeature.load(new Properties());sprint.setEnabled(true);
        try {
            for(String name:new String[]{"Basic","Legit","Intave"}) {
                try(ZenAntiKBTest.Fixture f=fixture(name,new World())) {
                    Minecraft.instance=f.mc;f.player.movementInput=new ScaffoldMovementInput(f.mc.gameSettings);
                    f.mc.objectMouseOver=null;f.player.hurtTime=0;f.start();
                    ZenAntiKBTest.coexistInput(f,-1,0,false);
                    check(name+" idle permits KeepSprint",!AttackReduceFeature.ownsMovementInput(f.player,f.player.movementInput)
                            &&"SILENT".equals(KeepSprintFeature.getStatus()));
                    ZenAntiKBTest.coexistEnd(f);
                    if(!name.equals("Basic")) {
                        set(name,"CheckFallDamage",0);set(name,"JumpDelayMode",name.equals("Legit")?1:2);set(name,"JumpChance",100);
                        if(name.equals("Intave"))set(name,"Jump",1);
                        f.player.hurtTime=9;f.start();ZenAntiKBTest.coexistInput(f,1,0,false);
                        check(name+" jump claims input before KeepSprint",f.player.movementInput.jump
                                &&AttackReduceFeature.ownsMovementInput(f.player,f.player.movementInput)
                                &&"YIELD VELOCITY".equals(KeepSprintFeature.getStatus()));
                        ZenAntiKBTest.coexistEnd(f);
                        check(name+" END clears input claim",!AttackReduceFeature.ownsMovementInput(f.player,f.player.movementInput));
                        f.player.hurtTime=0;f.start();ZenAntiKBTest.coexistInput(f,-1,0,false);
                        check(name+" idle resumes KeepSprint","SILENT".equals(KeepSprintFeature.getStatus()));
                        ZenAntiKBTest.coexistEnd(f);
                    }
                } finally {KeepSprintFeature.reset();Minecraft.instance=null;}
            }
        } finally {sprint.setEnabled(false);KeepSprintFeature.reset();}
    }
    static void flagAndQueues() throws Exception {
        try(ZenAntiKBTest.Fixture f=fixture("Intave/PolarFlag",new World())) {
            f.receive(new ZenAntiKBTest.S03PacketTimeUpdate());check("Flag holds ordinary incoming",f.applied.isEmpty());f.receive(new ZenAntiKBTest.S02PacketChat());check("Flag chat exemption",f.applied.size()==1);
            f.receive(new ZenAntiKBTest.S32PacketConfirmTransaction());check("Flag transaction releases preceding packet",f.applied.get(1) instanceof ZenAntiKBTest.S03PacketTimeUpdate);
            f.receive(new S19PacketEntityStatus(f.player));check("Flag own hurt status",((AugustusFlag)AttackReduceFeature.current()).flagged);
            double y=f.player.posY;AugustusVelocityHooks.postUpdate(f.player);eq("Flag POST epsilon",y-1e-7,f.player.posY);
            f.receive(new ZenAntiKBTest.S08PacketPlayerPosLook());check("Flag correction unflags",!((AugustusFlag)AttackReduceFeature.current()).flagged);
        }
        try(ZenAntiKBTest.Fixture f=fixture("PushGround",new World())) {
            set("common","Lag",100);f.receive(f.velocity());check("common Lag holds own S12",f.applied.isEmpty());
            Object move=new C03PacketPlayer.C04PacketPlayerPosition();ChannelFuture pending=f.channel.writeAndFlush(move);f.drain();check("Lag outbound promise pending",!pending.isDone());
            f.receive(new ZenAntiKBTest.S32PacketConfirmTransaction());time+=99;f.start();check("Lag strict elapsed boundary",f.applied.isEmpty());time++;f.start();f.drain();
            check("Lag inbound FIFO",f.applied.get(0) instanceof S12PacketEntityVelocity&&f.applied.get(1) instanceof ZenAntiKBTest.S32PacketConfirmTransaction);
            check("held move precedes replay-generated acknowledgement",f.channel.readOutbound()==move);check("ack follows held move",f.channel.readOutbound() instanceof net.minecraft.network.play.client.C0FPacketConfirmTransaction);check("released promise succeeds",pending.isSuccess());
            set("common","S00PacketKeepAliveCancelCondition",3);set("common","S00PacketKeepAlivePacketCount",2);set("common","Lag",0);f.receive(f.velocity());int size=f.applied.size();f.receive(new ZenAntiKBTest.S00PacketKeepAlive());f.receive(new ZenAntiKBTest.S00PacketKeepAlive());check("packet cancel count",f.applied.size()==size);f.receive(new ZenAntiKBTest.S00PacketKeepAlive());check("packet cancel exhausted passes",f.applied.size()==size+1);
            set("common","CancelDamageSoundsFromOtherPlayers",1);size=f.applied.size();f.receive(new S29PacketSoundEffect("game.player.hurt"));check("source damage sound name",f.applied.size()==size);f.receive(new S29PacketSoundEffect("random.explode"));check("other sound passes",f.applied.size()==size+1);
            set("common","Lag",100);f.receive(f.velocity());ChannelFuture held=f.channel.writeAndFlush(new C03PacketPlayer(false));f.drain();f.channel.close();f.drain();check("disconnect completes held promise with failure",held.isDone()&&!held.isSuccess());
        }
        try(ZenAntiKBTest.Fixture f=fixture("Intave",new World())) {
            set("Intave","IntaveBoost",1);set("Intave","IntaveBoostDelay",0);f.player.moveForward=1;AugustusVelocity.groundTicks=3;f.receive(f.velocity());check("IntaveBoost starts shared lag hold",f.applied.isEmpty());
            AttackReduceFeature.afterMovementInput(f.player.movementInput);check("boost ground input sneak+stop",f.player.movementInput.sneak&&f.player.movementInput.moveForward==0);f.start();check("boost flush after input transition",f.applied.size()==1);
        }
    }
    static void settings() throws Exception {
        for(int mode=0;mode<AttackReduceFeature.MODE_NAMES.length;mode++) {
            Properties p=new Properties();p.setProperty("velocity.zen.mode",AttackReduceFeature.MODE_NAMES[mode]);p.setProperty("velocity.zen.accuracy","37");p.setProperty("velocity.augustus.Reverse.XZ-Velocity Strength","53");AttackReduceFeature.load(p);
            Properties saved=new Properties();saved.load(new StringReader(AttackReduceFeature.save()));AttackReduceFeature.load(saved);
            check("mode stable round trip "+mode,AttackReduceFeature.getMode()==mode);check("legacy value survives all modes",AttackReduceFeature.getAccuracy()==37);eq("escaped space in option round trips",53,AugustusVelocity.value("Reverse","XZ-Velocity Strength"));
            for(AugustusVelocity.Option o:AugustusVelocity.options())check("visibility scopes mode "+o.key,!o.visible()||mode>=3&&(o.mode.equals("common")||o.mode.equals(AttackReduceFeature.getSummary())));
        }
    }
    static void predictionAndRanges() {
        World world=new World();world.obstacles.add(new AxisAlignedBB(-10,-1,-10,10,0,10));
        world.obstacles.add(new AxisAlignedBB(-1,0,.5,1,.5,2));
        try(ZenAntiKBTest.Fixture f=fixture("Intave",world)) {
            f.player.movementInput.moveForward=0;f.player.motionZ=1;f.player.motionY=-.08;
            double[] p=AugustusPrediction.predict(1);eq("prediction chooses vanilla step candidate Y",.5,p[1]);eq("prediction steps over half block",1,p[2]);
            f.player.stepHeight=0;p=AugustusPrediction.predict(1);eq("prediction clips to wall without step",.2,p[2]);eq("prediction clips downward motion to floor",0,p[1]);
            eq("prediction does not move live player",0,f.player.posY);eq("prediction does not alter live velocity",1,f.player.motionZ);check("prediction keeps live bounding box",f.player.bounds==null);
            world.obstacles.clear();f.player.motionZ=0;f.player.motionY=0;f.player.movementInput.jump=true;f.player.jumpTicks=-1;
            eq("negative jump cooldown does not jump",0,AugustusPrediction.predict(1)[1]);f.player.jumpTicks=1;
            eq("cooldown decrements before jump",(double).42f,AugustusPrediction.predict(1)[1]);
            eq("coefficient one still clears hypothetical sprint",0,AugustusPrediction.predict(1,true)[2]);
            eq("normal prediction retains sprint jump impulse",(double).2f,AugustusPrediction.predict(1,false)[2]);
            f.player.jumpBoost=true;eq("SRG potion registry preserves amplifier",(double).42f+(double)(3*.1f),AugustusPrediction.predict(.5)[1]);
            world.obstacles.add(new AxisAlignedBB(-1,2,-1,1,3,1));eq("prediction clips upward motion to ceiling",.2,AugustusPrediction.predict(.5)[1]);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Legit",new World())) {
            AugustusVelocity.RANDOM.setSeed(17391);set("Legit","JumpIntervalMin",0);set("Legit","JumpIntervalMax",1);set("Legit","JumpIntervalNotInARow",1);
            AugustusRange r=new AugustusRange("Legit","JumpInterval");int previous=r.sample();
            for(int i=0;i<100;i++){int next=r.sample();check("NotInARow resamples repeated integer",next!=previous);previous=next;}
            set("Legit","JumpIntervalNotInARow",0);set("Legit","JumpIntervalMin",0);set("Legit","JumpIntervalMax",5);set("Legit","JumpIntervalNormalDistribution",1);set("Legit","JumpIntervalSigma",.5);set("Legit","JumpIntervalCenter",80);
            double sum=0;for(int i=0;i<2000;i++){int next=r.sample();check("Gaussian range remains bounded",next>=0&&next<=5);sum+=next;}check("Gaussian center uses configured percentage",sum/2000>3.9&&sum/2000<4.1);
            set("Legit","JumpIntervalNormalDistribution",0);set("Legit","JumpIntervalMin",2);set("Legit","JumpIntervalMax",2);set("Legit","JumpIntervalOutlierValueMin",0);set("Legit","JumpIntervalOutlierValueMax",0);set("Legit","JumpIntervalOutlierDelayMin",0);set("Legit","JumpIntervalOutlierDelayMax",0);set("Legit","JumpIntervalOutliers",1);
            r.resetOutliers();check("source outlier counter starts with nominal",r.sample()==2);check("outlier uses strict samples greater than delay",r.sample()==0);check("outlier counter reset then increment retained",r.sample()==0);
        }
        try(ZenAntiKBTest.Fixture f=fixture("BoundingBox",new World())) {
            f.player.posY=.35;f.player.hurtTime=2;check("BoundingBox truncates fractional player Y",!AugustusVelocityHooks.collision(new ArrayList(),f.player,new AxisAlignedBB(-.2,1,-.2,.2,2,.2)).isEmpty());
            f.player.posY=-.35;check("BoundingBox uses Java integer truncation below zero",!AugustusVelocityHooks.collision(new ArrayList(),f.player,new AxisAlignedBB(-.2,1,-.2,.2,2,.2)).isEmpty());
        }
        try(ZenAntiKBTest.Fixture f=fixture("CustomMotion",new World())) {
            f.player.burning=true;eq("CustomMotion consumes an already armed pulse despite receive filter",.2,AugustusVelocityHooks.move(f.player,0,0,.7)[2]);
        }
    }
    static void lifecycle() throws Exception {
        for(boolean switchMode:new boolean[]{false,true})try(ZenAntiKBTest.Fixture f=fixture("PushGround",new World())) {
            set("common","Lag",100);Object inbound=f.velocity();f.receive(inbound);Object outbound=new C03PacketPlayer(false);ChannelFuture promise=f.channel.writeAndFlush(outbound);f.drain();
            if(switchMode)AttackReduceFeature.setMode(3);else ZenAntiKBTest.MODULE.setEnabled(false);f.start();f.drain();
            check("disable/switch releases queued inbound once",f.applied.size()==1&&f.applied.get(0)==inbound);check("disable/switch releases outbound promise",promise.isSuccess());check("disable/switch outbound FIFO",f.channel.readOutbound()==outbound);
        }
        try(ZenAntiKBTest.Fixture f=fixture("PushGround",new World())) {
            set("common","Lag",100);Object velocity=f.velocity();f.receive(velocity);
            Object nextWorld=new World();f.player.sendQueue.inbound=p->{f.applied.add(p);if(p instanceof ZenAntiKBTest.S07PacketRespawn)f.mc.theWorld=nextWorld;};
            Thread worker=new Thread(()->{f.channel.writeInbound(new ZenAntiKBTest.S07PacketRespawn());f.channel.writeInbound(new ZenAntiKBTest.S03PacketTimeUpdate());});worker.start();worker.join();
            check("worker ingress waits for main thread",f.applied.isEmpty());f.drain();check("world transition releases old lag before respawn",f.applied.size()==3&&f.applied.get(0)==velocity&&f.applied.get(1) instanceof ZenAntiKBTest.S07PacketRespawn&&f.applied.get(2) instanceof ZenAntiKBTest.S03PacketTimeUpdate);
            check("same connection receives following world packets",f.mc.theWorld==nextWorld);f.start();check("new world establishes fresh session",AttackReduceFeature.livePlayer(f.player));eq("new session resets attack counter",0,AugustusVelocity.attackReductions);
        }
        try(ZenAntiKBTest.Fixture f=fixture("Intave/PolarFlag",new World())) {
            set("Intave/PolarFlag","Polar",1);Object ordinary=new ZenAntiKBTest.S03PacketTimeUpdate();f.receive(ordinary);f.receive(new ZenAntiKBTest.S32PacketConfirmTransaction());f.receive(new ZenAntiKBTest.S32PacketConfirmTransaction());check("Polar holds through two transactions",f.applied.isEmpty());f.receive(new ZenAntiKBTest.S32PacketConfirmTransaction());check("Polar third transaction releases FIFO",f.applied.size()==3&&f.applied.get(0)==ordinary);
        }
        try(ZenAntiKBTest.Fixture f=fixture("PushGround",new World())) {
            set("common","Lag",400);f.receive(f.velocity());for(int i=1;i<AttackReduceFeature.MAX_PENDING;i++)f.receive(new ZenAntiKBTest.S03PacketTimeUpdate());check("inbound queue limit releases all packets",f.applied.size()==AttackReduceFeature.MAX_PENDING);
        }
        try(ZenAntiKBTest.Fixture f=fixture("PushGround",new World())) {
            AttackReduceFeature.setVelocityHooksAvailable(false);f.start();check("attach failure exposes unavailable state",AttackReduceFeature.getStatus().equals("HOOKS UNAVAILABLE"));check("attach failure suspends Augustus session",!AttackReduceFeature.livePlayer(f.player));
            AttackReduceFeature.setMode(1);f.start();check("legacy mode remains available after Augustus hook failure",AttackReduceFeature.livePlayer(f.player));AttackReduceFeature.setVelocityHooksAvailable(true);
        }
    }
    public static void main(String[] args) throws Exception {
        ModuleManager.register(ZenAntiKBTest.MODULE);ModuleManager.register(ZenAntiKBTest.AURA);AugustusVelocity.clock=()->time;
        packetModes();movementModes();inputAndAttack();keepSprintCoexistence();flagAndQueues();predictionAndRanges();lifecycle();settings();
        System.out.println("AugustusVelocityTest: "+checks+" passed, 0 failed");
    }
}
