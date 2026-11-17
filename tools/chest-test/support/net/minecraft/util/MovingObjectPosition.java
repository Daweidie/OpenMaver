package net.minecraft.util;

/** 测试替身：typeOfHit 在真机上是枚举，String.valueOf 后都是 "BLOCK"。 */
public class MovingObjectPosition {
    public String typeOfHit = "BLOCK";
    public BlockPos blockPos;
    public Object sideHit = "up";
    public Object hitVec = new Object();

    public MovingObjectPosition(BlockPos blockPos) {
        this.blockPos = blockPos;
    }
}
