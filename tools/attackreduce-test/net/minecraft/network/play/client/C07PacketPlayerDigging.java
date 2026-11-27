package net.minecraft.network.play.client;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
public class C07PacketPlayerDigging {
    public enum Action {START_DESTROY_BLOCK}
    public final Action action;public final BlockPos pos;public final EnumFacing facing;
    public C07PacketPlayerDigging(Action a,BlockPos p,EnumFacing f){action=a;pos=p;facing=f;}
}
