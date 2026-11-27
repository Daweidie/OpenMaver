import net.minecraft.util.AxisAlignedBB;

/** net/47GX. Its queue is independent of common Lag and preserves source exemptions. */
final class AugustusFlag extends AugustusMode {
    boolean flagged;
    double savedY;
    int transactions;
    AugustusFlag() { super("Intave/PolarFlag"); }
    @Override void onEnable() { super.onEnable();flagged=false;savedY=0;transactions=0; }
    @Override void onDisable() { super.onDisable();flagged=false;transactions=0; }
    // Source excludes 47vS, its private no-op packet marker (both codec methods are empty).
    // Vanilla inbound packets cannot be that marker, so every captured packet is replayed.
    void flushFlag() { AttackReduceFeature.Packet p;while((p=packetQueue.poll())!=null)p.handle(); }
    @Override void release() { flushFlag();super.release(); }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) {
        if(AugustusVelocity.ticks()<100) {packetQueue.clear();return;}
        AttackReduceFeature.Packet p=e.packet;
        if(p.is("S32PacketConfirmTransaction")&&!flagged&&++transactions>(b("Polar")?2:0)) {transactions=0;flushFlag();}
        if(p.is("S19PacketEntityStatus")) {
            Object entity=AttackReduceFeature.call(p.raw,"getEntity","func_149161_a",AugustusVelocity.world());
            Object opcode=AttackReduceFeature.call(p.raw,"getOpCode","func_149160_c");
            if(entity==AugustusVelocity.p()&&opcode instanceof Number&&((Number)opcode).intValue()==2&&AttackReduceFeature.onGround()) {if(!flagged)savedY=AugustusVelocity.n("posY","field_70163_u");flagged=true;}
        }
        if(!(p.is("S02PacketChat")||p.is("S19PacketEntityStatus")||p.is("S40PacketDisconnect")||p.is("S0BPacketAnimation")))delay(e);
        if(p.is("S08PacketPlayerPosLook"))flagged=false;
    }
    @Override void onTick() {
        if(flagged)return;AxisAlignedBB b=AugustusVelocity.box();if(b==null)return;
        double ceil=Math.ceil(b.minY),floor=Math.floor(b.minY);
        if(Math.abs(ceil-b.minY)<1e-4)AugustusVelocity.position(ceil);else if(Math.abs(floor-b.minY)<1e-4)AugustusVelocity.position(floor);
    }
    @Override void postMotion() {
        double y=AugustusVelocity.n("posY","field_70163_u");
        if(flagged)AttackReduceFeature.set(AugustusVelocity.p(),"posY","field_70163_u",y-1e-7);
        if(savedY-AugustusVelocity.n("posY","field_70163_u")>.03||!AugustusVelocity.air(AugustusVelocity.pos(0)))flagged=false;
    }
    void outgoingPosition() { AugustusVelocity.position(AugustusVelocity.n("posY","field_70163_u")); }
}
