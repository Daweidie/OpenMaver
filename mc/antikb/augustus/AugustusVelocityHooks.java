import java.util.List;
import java.util.ArrayDeque;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

/** Bridges only the local, live session. All callers are on Minecraft's main thread. */
public final class AugustusVelocityHooks {
    static boolean moveHook, collisionHook, motionHook, updateHook, attackHook;
    private AugustusVelocityHooks() { }
    static AugustusMode active(Object entity) {
        if(!AttackReduceFeature.livePlayer(entity)||AttackReduceFeature.getMode()<3)return null;
        return (AugustusMode)AttackReduceFeature.current();
    }
    static final class AttackFrame {
        final Object player;
        boolean first;
        double coefficient=.6;
        AttackFrame(Object p) {player=p;}
    }
    private static final ThreadLocal<ArrayDeque<AttackFrame>> attacks=new ThreadLocal<ArrayDeque<AttackFrame>>() {
        @Override protected ArrayDeque<AttackFrame> initialValue() {return new ArrayDeque<AttackFrame>();}
    };
    public static void beginAttack(Object entity) {attacks.get().push(new AttackFrame(entity));}
    public static void endAttack(Object entity) {
        ArrayDeque<AttackFrame> frames=attacks.get();
        if(!frames.isEmpty()&&frames.peek().player==entity)frames.pop();
        if(frames.isEmpty())attacks.remove();
    }
    public static double[] move(Object entity,double x,double y,double z) {
        moveHook=true; AugustusMode m=active(entity); if(m==null)return new double[]{x,y,z};
        double[] v=m.move(x,y,z);
        if(m instanceof AugustusSafeWalk&&((AugustusSafeWalk)m).safe()) {
            AxisAlignedBB box=AugustusVelocity.box();
            if(box!=null) {
                while(v[0]!=0&&empty(entity,box,v[0],-1,0))v[0]=trim(v[0]);
                while(v[2]!=0&&empty(entity,box,0,-1,v[2]))v[2]=trim(v[2]);
                while(v[0]!=0&&v[2]!=0&&empty(entity,box,v[0],-1,v[2])) {v[0]=trim(v[0]);v[2]=trim(v[2]);}
            }
        }
        return v;
    }
    static double trim(double d) { return d<.05&&d>=-.05?0:d>0?d-.05:d+.05; }
    static boolean empty(Object entity,AxisAlignedBB b,double x,double y,double z) {
        Object v=AttackReduceFeature.call(AugustusVelocity.world(),"getCollidingBoundingBoxes","func_72945_a",entity,new AxisAlignedBB(b.minX+x,b.minY+y,b.minZ+z,b.maxX+x,b.maxY+y,b.maxZ+z));
        return v instanceof List&&((List)v).isEmpty();
    }
    public static List collision(List boxes,Object entity,Object bounds) {
        collisionHook=true; AugustusMode m=active(entity);
        if(!(m instanceof AugustusBoundingBox)||!(bounds instanceof AxisAlignedBB))return boxes;
        AxisAlignedBB q=(AxisAlignedBB)bounds;
        // Source only adds air boxes at playerY+1, and only for intersecting collision queries.
        int y=(int)AugustusVelocity.n("posY","field_70163_u")+1;
        if(y<q.minY-1||y>q.maxY)return boxes;
        for(int x=(int)Math.floor(q.minX);x<=(int)Math.floor(q.maxX);x++)for(int z=(int)Math.floor(q.minZ);z<=(int)Math.floor(q.maxZ);z++) {
            AxisAlignedBB b=m.collision(new BlockPos(x,(int)y,z));
            if(AugustusVelocity.intersects(b,q))boxes.add(b);
        }
        return boxes;
    }
    public static void preMotion(Object entity) { motionHook=true;AugustusMode m=active(entity);if(m!=null)m.preMotion(); }
    public static void preUpdate(Object entity) { updateHook=true;AugustusMode m=active(entity);if(m!=null)m.onTick(); }
    public static void postUpdate(Object entity) {
        AugustusMode m=active(entity);if(m==null)return;
        AugustusVelocity.groundTicks=AttackReduceFeature.onGround()?AugustusVelocity.groundTicks+1:0;
        m.postMotion();
    }
    public static double attackSlowdown(Object entity,Object target) {
        attackHook=true;AugustusMode m=active(entity);
        AttackFrame f=attacks.get().peek();
        if(f==null||f.player!=entity)return .6;
        if(!f.first) {
            f.first=true;
            f.coefficient=m instanceof AugustusIntave?((AugustusIntave)m).firstAttack():.6;
        }
        return f.coefficient;
    }
    public static void attackMerge(Object entity,Object target) {
        AttackFrame f=attacks.get().peek();if(f==null||f.player!=entity)return;
        AugustusMode m=active(entity);if(m==null)return;
        if(f.first) {AugustusVelocity.attackReductions++;return;}
        if(m instanceof AugustusIntave&&((AugustusIntave)m).secondAttack(target))AugustusVelocity.attackReductions++;
    }
}
