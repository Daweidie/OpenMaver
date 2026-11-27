package net.minecraft.util;
public class BlockPos {
    private final int x,y,z;
    public BlockPos(double x,double y,double z) { this.x=(int)Math.floor(x);this.y=(int)Math.floor(y);this.z=(int)Math.floor(z); }
    public BlockPos(int x,int y,int z) { this.x=x;this.y=y;this.z=z; }
    public int getX() {return x;} public int getY(){return y;} public int getZ(){return z;}
}
