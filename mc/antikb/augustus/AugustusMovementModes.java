import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;

final class AugustusPushGround extends AugustusMode {
    AugustusPushGround() { super("PushGround"); }
    @Override void onTick() { if(allowed()&&AugustusVelocity.hurt()>=Math.min(v("HurtTimeMin"),v("HurtTimeMax"))&&AugustusVelocity.hurt()<=Math.max(v("HurtTimeMin"),v("HurtTimeMax")))AugustusVelocity.ground(); }
}
final class AugustusPush extends AugustusMode {
    AugustusPush() { super("Push"); }
    @Override void onTick() {
        if(!allowed())return; int h=AugustusVelocity.hurt();
        if(v("PushMode")==0) {
            if(h<Math.min(v("PushStart"),v("PushEnd"))||h>Math.max(v("PushStart"),v("PushEnd")))return;
            AugustusVelocity.push(v("PushMotion")/100);if(b("OnGround"))AugustusVelocity.ground();
        } else if(h>=1&&h<=10) { AugustusVelocity.push(v("PushMotion"+h));if(b("PushOnGround"+h))AugustusVelocity.ground(); }
    }
}
final class AugustusIntave13 extends AugustusMode {
    AugustusIntave13() { super("Intave13"); }
    @Override void onTick() { if(allowed()&&AugustusVelocity.hurt()!=0&&AugustusVelocity.n("fallDistance","field_70143_R")<3&&AugustusVelocity.collided())AugustusVelocity.strafe((double).195f); }
}
final class AugustusOldMinemen extends AugustusMode {
    int count;
    AugustusOldMinemen() { super("OldMinemen"); }
    @Override void onEnable() { super.onEnable();count=0; }
    @Override void onDisable() { super.onDisable();count=0; }
    @Override void onStrafe(MovementInput input) {
        if(AugustusVelocity.hurt()==9&&count++%4==0)AugustusVelocity.strafe((double).2f);
        if(AugustusVelocity.hurt()==5)AugustusVelocity.strafe((double).1f);
    }
}
final class AugustusKarhu extends AugustusMode {
    int count;
    AugustusKarhu() { super("Karhu"); }
    @Override void onEnable() { super.onEnable();count=0; }
    @Override void onTick() {
        if(!allowed())return;
        if(AugustusVelocity.hurt()>0&&AugustusVelocity.n("motionY","field_70181_x")<=0 && (solid(AugustusVelocity.pos(-1))||solid(AugustusVelocity.pos(-2)))) AttackReduceFeature.set(AugustusVelocity.p(),"motionY","field_70181_x",-10);
        if(AugustusVelocity.hurt()==5&&count++%2==0)AugustusVelocity.strafe((double).15f);
    }
    boolean solid(BlockPos pos) { return AugustusVelocity.blockBox(pos)!=null; }
}
final class AugustusReverse extends AugustusMode {
    AugustusReverse() { super("Reverse"); }
    boolean strafe() { int h=AugustusVelocity.hurt();return b("ReverseStrafe")&&h>0&&h>=Math.min(v("ReverseStrafeStart"),v("ReverseStrafeEnd"))&&h<=Math.max(v("ReverseStrafeStart"),v("ReverseStrafeEnd")); }
    @Override void onTick() {
        if(!allowed()||AugustusVelocity.hurt()!=v("ReverseStart"))return;
        AugustusVelocity.motion(-AugustusVelocity.n("motionX","field_70159_w"),-AugustusVelocity.n("motionZ","field_70179_y"));
        if(strafe())AugustusVelocity.strafe((float)Math.hypot(AugustusVelocity.n("motionX","field_70159_w"),AugustusVelocity.n("motionZ","field_70179_y")));
    }
    @Override void preMotion() { if(allowed()&&strafe())AugustusVelocity.strafe(Math.hypot(AugustusVelocity.n("motionX","field_70159_w"),AugustusVelocity.n("motionZ","field_70179_y"))); }
    // 47Gz registers events 43 and 18, not event 26. Its receive method is unreachable.
}
final class AugustusCustomMotion extends AugustusMode {
    boolean pending;
    AugustusCustomMotion() { super("CustomMotion"); }
    @Override void onEnable() { super.onEnable();pending=true; }
    @Override void onDisable() { super.onDisable();pending=false; }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) { if(allowed()&&AttackReduceFeature.ownVelocity(e.packet))pending=true; }
    @Override double[] move(double x,double y,double z) {
        if(!pending)return new double[]{x,y,z};pending=false;
        double d=AugustusVelocity.direction(),a=v(AttackReduceFeature.onGround()?"MotionOnGround":"MotionInAir");return new double[]{-Math.sin(d)*a,y,Math.cos(d)*a};
    }
}
final class AugustusSafeWalk extends AugustusMode {
    AugustusSafeWalk() { super("SafeWalk"); }
    boolean safe() { return allowed()&&AugustusVelocity.hurt()>0&&AugustusVelocity.air(AugustusVelocity.pos(-.2)); }
}
final class AugustusBoundingBox extends AugustusMode {
    AugustusBoundingBox() { super("BoundingBox"); }
    @Override AxisAlignedBB collision(BlockPos pos) {
        int h=AugustusVelocity.hurt();
        if(!allowed()||h>Math.max(v("HurtTimeMin"),v("HurtTimeMax"))||h<Math.min(v("HurtTimeMin"),v("HurtTimeMax"))+1||pos.getY()!=(int)AugustusVelocity.n("posY","field_70163_u")+1||!AugustusVelocity.air(pos))return null;
        return new AxisAlignedBB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+v("Height"),pos.getZ()+1);
    }
}
