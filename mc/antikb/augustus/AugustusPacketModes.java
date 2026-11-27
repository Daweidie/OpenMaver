import net.minecraft.util.AxisAlignedBB;

/** net/47GN; S12 preserve-axis flags and S27 reset-axis flags have different consumers. */
final class AugustusBasic extends AugustusMode {
    int velocities, explosions;
    AugustusBasic() { super("Basic"); }
    @Override void onEnable() { super.onEnable(); velocities=explosions=0; }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) {
        if(!allowed())return;
        AttackReduceFeature.Packet p=e.packet;
        boolean ground=AttackReduceFeature.onGround(); String phase=ground?"OnGround":"InAir";
        if(AttackReduceFeature.ownVelocity(p)) {
            if(++velocities%v("IntervalVelocity")!=0&&b("Intervals"))return;
            double xz=v("XZVelocity"+phase),y=v("YVelocity"+phase);
            if(b(ground?"CancelGround":"CancelAir")) { if(xz==0)p.horizontal=false;if(y==0)p.vertical=false; }
            AugustusVelocity.scale(p,xz,y);
        } else if(p.is("S27PacketExplosion")&&b("Explosion")) {
            if(++explosions%v("IntervalExplosion")!=0&&b("Intervals"))return;
            double xz=v("XZExplosion"+phase),y=v("YExplosion"+phase);
            AttackReduceFeature.set(p.raw,"field_149152_f","field_149152_f",(float)(AttackReduceFeature.number(p.raw,"field_149152_f","field_149152_f")*xz/100));
            AttackReduceFeature.set(p.raw,"field_149153_g","field_149153_g",(float)(AttackReduceFeature.number(p.raw,"field_149153_g","field_149153_g")*y/100));
            AttackReduceFeature.set(p.raw,"field_149159_h","field_149159_h",(float)(AttackReduceFeature.number(p.raw,"field_149159_h","field_149159_h")*xz/100));
            if(!b(ground?"CancelExplosionGround":"CancelExplosionAir")) { p.resetHorizontal=xz==0;p.resetVertical=y==0; }
        }
    }
}
/** net/47Gl. No local application of the cancelled S12. */
final class AugustusSpoof extends AugustusMode {
    AugustusSpoof() { super("Spoof"); }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) {
        if(!allowed()||!AttackReduceFeature.ownVelocity(e.packet))return;
        e.cancel(); AugustusVelocity.send(AugustusVelocity.construct("network.play.client.C03PacketPlayer$C04PacketPlayerPosition",
            AugustusVelocity.n("posX","field_70165_t")+e.packet.x()/8000.0,
            AugustusVelocity.n("posY","field_70163_u")+e.packet.y()/8000.0,
            AugustusVelocity.n("posZ","field_70161_v")+e.packet.z()/8000.0,false));
    }
}
/** net/47G6 has an impossible vertical range after cancellation; keep the source behavior. */
final class AugustusHypixel extends AugustusMode {
    AugustusHypixel() { super("HypixelTest"); }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) { if(allowed()&&AttackReduceFeature.ownVelocity(e.packet))e.cancel(); }
}
/** net/47GH deliberately observes every S12 and does not use 47GJ's common filter. */
final class AugustusPolarBlock extends AugustusMode {
    AugustusPolarBlock() { super("PolarBlock"); }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) {
        if(!e.packet.is("S12PacketEntityVelocity")||AugustusVelocity.p()==null||AugustusVelocity.world()==null||!AttackReduceFeature.onGround())return;
        AxisAlignedBB box=AugustusVelocity.box(); if(box==null)return;
        double dy=2-AugustusVelocity.n("height","field_70131_O")+.05;
        AxisAlignedBB shifted=new AxisAlignedBB(box.minX,box.minY+dy,box.minZ,box.maxX,box.maxY+dy,box.maxZ);
        if(AugustusVelocity.intersects(shifted,AugustusVelocity.blockBox(AugustusVelocity.pos(2)))) e.packet.horizontal=false;
    }
}
/** net/47Gn. Executed immediately before onUpdateWalkingPlayer, once per movement frame. */
final class AugustusGrim extends AugustusMode {
    int correctionTick, state;
    AugustusGrim() { super("Grim-1.17"); }
    @Override void onEnable() { super.onEnable();correctionTick=0;state=1; }
    @Override void onDisable() { super.onDisable();state=0; }
    @Override void onReceivePacket(AttackReduceFeature.ReceivePacketEvent e) {
        if(!allowed())return;
        if(e.packet.is("S08PacketPlayerPosLook"))correctionTick=AugustusVelocity.ticks();
        else if(AttackReduceFeature.ownVelocity(e.packet)&&correctionTick!=AugustusVelocity.ticks()) {e.cancel();state=1;}
    }
    @Override void preMotion() {
        if(!allowed()||state<=0)return;
        if(++state!=2) {state=0;return;}
        Object p=AugustusVelocity.p(); double y=AttackReduceFeature.number(p,"lastReportedPosY","field_175166_bJ");
        AugustusVelocity.send(AugustusVelocity.construct("network.play.client.C03PacketPlayer$C06PacketPlayerPosLook",
            AttackReduceFeature.number(p,"lastReportedPosX","field_175172_bI"),y,AttackReduceFeature.number(p,"lastReportedPosZ","field_175167_bK"),
            (float)AttackReduceFeature.number(p,"lastReportedYaw","field_175164_bL"),(float)AttackReduceFeature.number(p,"lastReportedPitch","field_175165_bM"),y%.015625==0));
        try {
            ClassLoader loader=p.getClass().getClassLoader();
            Object start=Enum.valueOf((Class)Class.forName("net.minecraft.network.play.client.C07PacketPlayerDigging$Action",true,loader),"START_DESTROY_BLOCK");
            Object up=Enum.valueOf((Class)Class.forName("net.minecraft.util.EnumFacing",true,loader),"UP");
            AugustusVelocity.send(AugustusVelocity.construct("network.play.client.C07PacketPlayerDigging",start,AugustusVelocity.pos(0),up));
        } catch(Exception ex) { OpenMaverDebug.noticeOnce("velocity.grim","Grim digging packet: "+ex); }
    }
}
