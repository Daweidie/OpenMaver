import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovementInput;

/** 47TT.41(JZ,Entity,DF): one normal movement step on a private bounding box.
 * Only position is consumed by IntaveSmart. Post-move friction cannot affect it.
 * The accepted attack is supplied by the real attack hook; prediction never damages
 * an entity, dispatches an attack event, or consumes the shared attack allowance.
 */
final class AugustusPrediction {
    private AugustusPrediction() { }
    static boolean improves(double coefficient) {
        Object hit=AttackReduceFeature.read(AttackReduceFeature.mc(),"objectMouseOver","field_71476_x");
        Object target=AttackReduceFeature.read(hit,"entityHit","field_72308_g");
        if(!(target instanceof EntityLivingBase))return true;
        Object bounds=AttackReduceFeature.call(target,"getEntityBoundingBox","func_174813_aQ");
        if(!(bounds instanceof AxisAlignedBB))return false;
        AxisAlignedBB b=(AxisAlignedBB)bounds;
        double border=number(AttackReduceFeature.call(target,"getCollisionBorderSize","func_70111_Y"),.1);
        b=new AxisAlignedBB(b.minX-border,b.minY-border,b.minZ-border,b.maxX+border,b.maxY+border,b.maxZ+border);
        double[] reduced=predict(coefficient,true),normal=predict(1,false);
        if(reduced==null||normal==null)return false;
        double eye=number(AttackReduceFeature.call(AugustusVelocity.p(),"getEyeHeight","func_70047_e"),0);
        reduced[1]+=eye;normal[1]+=eye;
        return distance(reduced,b)<distance(normal,b);
    }
    static double distance(double[] p,AxisAlignedBB b) {
        double x=p[0]-clamp(p[0],b.minX,b.maxX),y=p[1]-clamp(p[1],b.minY,b.maxY),z=p[2]-clamp(p[2],b.minZ,b.maxZ);
        return x*x+y*y+z*z;
    }
    static double number(Object v,double fallback) {return v instanceof Number?((Number)v).doubleValue():fallback;}
    static double clamp(double v,double lo,double hi) {return Math.max(lo,Math.min(hi,v));}
    static double[] predict(double coefficient) {
        return predict(coefficient,true);
    }
    static double[] predict(double coefficient,boolean reduction) {
        Object player=AugustusVelocity.p();AxisAlignedBB box=AugustusVelocity.box();MovementInput input=AugustusVelocity.input();
        if(player==null||box==null||input==null)return null;
        double x=AugustusVelocity.n("motionX","field_70159_w")*coefficient,
                y=AugustusVelocity.n("motionY","field_70181_x"),z=AugustusVelocity.n("motionZ","field_70179_y")*coefficient;
        // 47AC.41 returns .005 for the 1.8 protocol (and .003 after 1.9).
        if(Math.abs(x)<.005)x=0;if(Math.abs(y)<.005)y=0;if(Math.abs(z)<.005)z=0;
        boolean ground=AttackReduceFeature.onGround();
        float strafe=input.moveStrafe,forward=input.moveForward;
        if(AugustusVelocity.truth("isUsingItem","func_71039_bw")&&!AugustusVelocity.truth("isRiding","func_70115_ae")) {strafe*=.2f;forward*=.2f;}
        double jumpTicks=AugustusVelocity.n("jumpTicks","field_70773_bE");
        if(jumpTicks>0)jumpTicks--;
        if(input.jump&&ground&&jumpTicks==0) {
            y=number(AttackReduceFeature.call(player,"getJumpUpwardsMotion","func_175134_bD"),(double).42f);
            if(AttackReduceFeature.truth(player,"isPotionActive","func_82165_m",8)) {
                Object effect=AttackReduceFeature.call(player,"getActivePotionEffect","func_70660_b",potion(8));
                y+=(number(AttackReduceFeature.call(effect,"getAmplifier","func_76458_c"),0)+1)*.1f;
            }
            // SECOND's hypothetical reduction clears sprint before the simulated jump.
            if(!reduction&&AttackReduceFeature.sprinting()) {
                float yaw=(float)AugustusVelocity.n("rotationYaw","field_70177_z")*.017453292f;
                x-=sin(yaw)*.2f;z+=cos(yaw)*.2f;
            }
        }
        strafe*=.98f;forward*=.98f;
        float friction=.91f;
        if(ground) {
            Object block=AugustusVelocity.block(new BlockPos(Math.floor(AugustusVelocity.n("posX","field_70165_t")),Math.floor(AugustusVelocity.n("posY","field_70163_u"))-1,Math.floor(AugustusVelocity.n("posZ","field_70161_v"))));
            friction=(float)number(AttackReduceFeature.read(block,"slipperiness","field_149765_K"),.6f)*.91f;
        }
        float speed=ground?(float)number(AttackReduceFeature.call(player,"getAIMoveSpeed","func_70689_ay"),.1f)*(.16277136f/(friction*friction*friction)):
                (float)number(AttackReduceFeature.read(player,"jumpMovementFactor","field_70747_aH"),.02f);
        float magnitude=strafe*strafe+forward*forward;
        if(magnitude>=1.0e-4f) {
            magnitude=(float)Math.sqrt(magnitude);if(magnitude<1)magnitude=1;
            magnitude=speed/magnitude;strafe*=magnitude;forward*=magnitude;
            float yaw=(float)AugustusVelocity.n("rotationYaw","field_70177_z")*(float)Math.PI/180;
            float s=sin(yaw),c=cos(yaw);x+=strafe*c-forward*s;z+=forward*c+strafe*s;
        }
        if(ground&&AugustusVelocity.truth("isSneaking","func_70093_af")) {
            while(x!=0&&empty(player,offset(box,x,-1,0)))x=AugustusVelocityHooks.trim(x);
            while(z!=0&&empty(player,offset(box,0,-1,z)))z=AugustusVelocityHooks.trim(z);
            while(x!=0&&z!=0&&empty(player,offset(box,x,-1,z))) {x=AugustusVelocityHooks.trim(x);z=AugustusVelocityHooks.trim(z);}
        }
        double dx=x,dy=y,dz=z;AxisAlignedBB original=box;
        List<AxisAlignedBB> collisions=collisions(player,expand(box,x,y,z));if(collisions==null)return null;
        for(AxisAlignedBB b:collisions)y=clipY(b,box,y);box=offset(box,0,y,0);
        boolean canStep=ground||(dy!=y&&dy<0);
        for(AxisAlignedBB b:collisions)x=clipX(b,box,x);box=offset(box,x,0,0);
        for(AxisAlignedBB b:collisions)z=clipZ(b,box,z);box=offset(box,0,0,z);
        double step=AugustusVelocity.n("stepHeight","field_70138_W");
        if(step>0&&canStep&&(dx!=x||dz!=z)) {
            AxisAlignedBB flat=box;double flatX=x,flatY=y,flatZ=z;
            collisions=collisions(player,expand(original,dx,step,dz));if(collisions==null)return null;
            double up1=step,up2=step;AxisAlignedBB expanded=expand(original,dx,0,dz);
            for(AxisAlignedBB b:collisions)up1=clipY(b,expanded,up1);
            AxisAlignedBB a=offset(original,0,up1,0);double ax=dx,az=dz;
            for(AxisAlignedBB b:collisions)ax=clipX(b,a,ax);a=offset(a,ax,0,0);
            for(AxisAlignedBB b:collisions)az=clipZ(b,a,az);a=offset(a,0,0,az);
            for(AxisAlignedBB b:collisions)up2=clipY(b,original,up2);
            AxisAlignedBB c=offset(original,0,up2,0);double cx=dx,cz=dz;
            for(AxisAlignedBB b:collisions)cx=clipX(b,c,cx);c=offset(c,cx,0,0);
            for(AxisAlignedBB b:collisions)cz=clipZ(b,c,cz);c=offset(c,0,0,cz);
            if(ax*ax+az*az>cx*cx+cz*cz) {box=a;x=ax;z=az;y=-up1;}else {box=c;x=cx;z=cz;y=-up2;}
            for(AxisAlignedBB b:collisions)y=clipY(b,box,y);box=offset(box,0,y,0);
            if(flatX*flatX+flatZ*flatZ>=x*x+z*z) {box=flat;x=flatX;y=flatY;z=flatZ;}
        }
        return new double[]{(box.minX+box.maxX)/2,box.minY,(box.minZ+box.maxZ)/2};
    }
    static Object potion(int id) {
        try {
            Class<?> type=Class.forName("net.minecraft.potion.Potion",true,AugustusVelocity.p().getClass().getClassLoader());
            java.lang.reflect.Field field;
            try {field=type.getField("potionTypes");}catch(NoSuchFieldException e){field=type.getField("field_76425_a");}
            return java.lang.reflect.Array.get(field.get(null),id);
        }catch(Exception ignored){return null;}
    }
    // Match MathHelper's float sine lookup rather than double precision trigonometry.
    static float sin(float f) {return (float)Math.sin((double)(((int)(f*10430.378f))&65535)*Math.PI*2/65536);}
    static float cos(float f) {return (float)Math.sin((double)(((int)(f*10430.378f+16384))&65535)*Math.PI*2/65536);}
    static boolean empty(Object p,AxisAlignedBB b) {List<AxisAlignedBB> v=collisions(p,b);return v!=null&&v.isEmpty();}
    static List<AxisAlignedBB> collisions(Object p,AxisAlignedBB b) {
        Object v=AttackReduceFeature.call(AugustusVelocity.world(),"getCollidingBoundingBoxes","func_72945_a",p,b);
        if(!(v instanceof List))return null;
        List<AxisAlignedBB> result=new ArrayList<AxisAlignedBB>();for(Object box:(List)v)if(box instanceof AxisAlignedBB)result.add((AxisAlignedBB)box);return result;
    }
    static AxisAlignedBB offset(AxisAlignedBB b,double x,double y,double z) {return new AxisAlignedBB(b.minX+x,b.minY+y,b.minZ+z,b.maxX+x,b.maxY+y,b.maxZ+z);}
    static AxisAlignedBB expand(AxisAlignedBB b,double x,double y,double z) {return new AxisAlignedBB(b.minX+Math.min(0,x),b.minY+Math.min(0,y),b.minZ+Math.min(0,z),b.maxX+Math.max(0,x),b.maxY+Math.max(0,y),b.maxZ+Math.max(0,z));}
    static double clipX(AxisAlignedBB a,AxisAlignedBB b,double x) {
        if(b.maxY>a.minY&&b.minY<a.maxY&&b.maxZ>a.minZ&&b.minZ<a.maxZ) {
            if(x>0&&b.maxX<=a.minX)x=Math.min(x,a.minX-b.maxX);else if(x<0&&b.minX>=a.maxX)x=Math.max(x,a.maxX-b.minX);
        }return x;
    }
    static double clipY(AxisAlignedBB a,AxisAlignedBB b,double y) {
        if(b.maxX>a.minX&&b.minX<a.maxX&&b.maxZ>a.minZ&&b.minZ<a.maxZ) {
            if(y>0&&b.maxY<=a.minY)y=Math.min(y,a.minY-b.maxY);else if(y<0&&b.minY>=a.maxY)y=Math.max(y,a.maxY-b.minY);
        }return y;
    }
    static double clipZ(AxisAlignedBB a,AxisAlignedBB b,double z) {
        if(b.maxX>a.minX&&b.minX<a.maxX&&b.maxY>a.minY&&b.minY<a.maxY) {
            if(z>0&&b.maxZ<=a.minZ)z=Math.min(z,a.minZ-b.maxZ);else if(z<0&&b.minZ>=a.maxZ)z=Math.max(z,a.maxZ-b.minZ);
        }return z;
    }
}
