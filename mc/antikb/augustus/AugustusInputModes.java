import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.util.MovementInput;

/** Separate state machines; Legit's interval+tick gate is not OpenZen JumpReset. */
abstract class AugustusJumpMode extends AugustusMode {
    final Deque<Double> falls=new ArrayDeque<Double>();
    int eligible, inputTicks, interval, delay, bufferSize;
    long lastJump;
    final AugustusRange intervalRange,delayRange;
    AugustusJumpMode(String name) { super(name);intervalRange=new AugustusRange(name,name.equals("Legit")?"JumpInterval":"JumpTicks");delayRange=new AugustusRange(name,name.equals("Legit")?"TickDelayBetweenJumps":"JumpDelay"); }
    @Override void onEnable() { super.onEnable();falls.clear();eligible=inputTicks=0;lastJump=AugustusVelocity.now();intervalRange.resetOutliers();sample(); }
    @Override void onDisable() { super.onDisable();falls.clear();eligible=inputTicks=0; }
    void sample() { interval=intervalRange.sample();delay=delayRange.sample(); }
    void settingChanged(String key) {
        if(key.startsWith(name+"."+intervalRange.key)) {if(key.contains("OutlierDelay"))intervalRange.resetOutliers();interval=intervalRange.sample();}
        if(key.startsWith(name+"."+delayRange.key))delay=delayRange.sample();
    }
    void recordFall() {
        refreshBuffer();
        falls.add(AugustusVelocity.n("fallDistance","field_70143_R"));while(falls.size()>bufferSize)falls.poll();
    }
    void refreshBuffer() {int size=(int)v("FallDistanceBufferSize");if(bufferSize!=size){falls.clear();bufferSize=size;}}
    boolean eligible() {
        Object mc=AttackReduceFeature.mc();
        boolean focused=AttackReduceFeature.flag(mc,"inGameHasFocus","field_71415_G");
        double max=Double.NaN;for(double d:falls)max=Double.isNaN(max)?d:Math.max(max,d);
        return AttackReduceFeature.sprinting()&&(focused||b("JumpInInv"))
            &&(!b("CheckFallDamage")||max<v("FallDistance"))
            &&!AugustusVelocity.truth("isBurning","func_70027_ad")&&AttackReduceFeature.onGround()&&AugustusVelocity.hurt()==9
            &&(!b("NotWhileSpeed")||!potion(1))&&(!b("NotWhileJumpBoost")||!potion(8));
    }
    boolean potion(int id) { return AttackReduceFeature.truth(AugustusVelocity.p(),"isPotionActive","func_82165_m",id); }
    void jump(MovementInput in) { in.jump=true;AttackReduceFeature.claimMovementInput(in);eligible=0;inputTicks=0;lastJump=AugustusVelocity.now();sample(); }
}
final class AugustusLegit extends AugustusJumpMode {
    AugustusLegit() { super("Legit"); }
    @Override void onStrafe(MovementInput in) {
        refreshBuffer();
        inputTicks++;
        if(allowed()&&eligible()) {
            boolean ready=v("JumpDelayMode")==1?AugustusVelocity.RANDOM.nextInt(100)<v("JumpChance"):inputTicks>=delay&&eligible>interval;
            if(ready) {jump(in);eligible++;return;}
            eligible++;
        }
        recordFall();
    }
}
/** net/47GO: independent water/web/ladder packet paths, boost and jump gates. */
final class AugustusIntave extends AugustusJumpMode {
    int waterCount;
    boolean pendingBoost;
    long boostTimer;
    AugustusIntave() { super("Intave");boostTimer=AugustusVelocity.now(); }
    @Override void onEnable() { super.onEnable();waterCount=0;pendingBoost=false;boostTimer=AugustusVelocity.now(); }
    @Override void onDisable() { super.onDisable();pendingBoost=false; }
    boolean boostEligible() {
        return allowed()&&AttackReduceFeature.onGround()&&AugustusVelocity.moving()&&!AugustusVelocity.collided()&&b("IntaveBoost")
            &&AugustusVelocity.groundTicks>v("IntaveBoostOnGroundTicks")&&AugustusVelocity.elapsed(boostTimer)>=v("IntaveBoostDelay");
    }
    void lagEvent(AttackReduceFeature.Packet p) {
        if(boostEligible()&&AttackReduceFeature.ownVelocity(p)) {boostTimer=AugustusVelocity.now();pendingBoost=true;}
    }
    boolean holdingBoost() {return allowed()&&b("IntaveBoost")&&pendingBoost;}
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) {
        if(!allowed()||!AttackReduceFeature.ownVelocity(e.packet))return;
        AttackReduceFeature.Packet p=e.packet;
        if(b("Water")&&AugustusVelocity.truth("isInWater","func_70090_H")&&waterCount%v("WaterInterval")==0) {zeroXZ(p);waterCount++;}
        if(b("Cobweb")&&AttackReduceFeature.flag(AugustusVelocity.p(),"isInWeb","field_70134_J"))zeroXZ(p);
        if(b("Ladder")&&AugustusVelocity.truth("isOnLadder","func_70617_f_")) {zeroXZ(p);if(!b("LadderXZOnly"))AttackReduceFeature.set(p.raw,"motionY","field_149416_c",0);}
        if(boostEligible()) { if(v("XZVelocity")==0&&v("YVelocity")==0)e.cancel();else AugustusVelocity.scale(p,v("XZVelocity"),v("YVelocity")); }
    }
    void zeroXZ(AttackReduceFeature.Packet p) {AttackReduceFeature.set(p.raw,"motionX","field_149415_b",0);AttackReduceFeature.set(p.raw,"motionZ","field_149414_d",0);}
    @Override void onStrafe(MovementInput in) {
        refreshBuffer();
        if(!allowed()) {recordFall();return;}
        if(eligible()) {
            int choice=(int)v("JumpDelayMode");
            boolean ready=choice==0?AugustusVelocity.elapsed(lastJump)>=delay:choice==1?eligible>interval:AugustusVelocity.RANDOM.nextInt(100)<v("JumpChance");
            if(b("Jump")&&ready)jump(in);eligible++;
        }
        recordFall();
        if(!b("IntaveBoost")) {pendingBoost=false;return;}
        if(pendingBoost) { if(AttackReduceFeature.onGround()){in.sneak=true;in.moveForward=0;AttackReduceFeature.claimMovementInput(in);}pendingBoost=false; }
        if(b("SneakInAir")&&AugustusVelocity.hurt()>2) {in.sneak=true;AttackReduceFeature.claimMovementInput(in);}
    }
    double firstAttack() {
        if(!allowed()||AugustusVelocity.hurt()==0||AugustusVelocity.attackReductions!=0)return .6;
        // FIRST is selected by knockback level, including the enchantment branch.
        return v("XZOnHitSprint");
    }
    boolean secondAttack(Object target) {
        if(!allowed()||AugustusVelocity.hurt()==0||AugustusVelocity.attackReductions!=0)return false;
        if(b("IntaveSmart")&&!AugustusPrediction.improves(v("XZOnHit")))return false;
        AugustusVelocity.motion(AugustusVelocity.n("motionX","field_70159_w")*v("XZOnHit"),AugustusVelocity.n("motionZ","field_70179_y")*v("XZOnHit"));
        AttackReduceFeature.call(AugustusVelocity.p(),"setSprinting","func_70031_b",false);
        return true;
    }
}
