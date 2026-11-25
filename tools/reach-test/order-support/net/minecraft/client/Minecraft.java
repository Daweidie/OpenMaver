package net.minecraft.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Separates scheduled packet processing from the Netty write queue. */
public final class Minecraft {
    private static Minecraft instance;
    public final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
    public Minecraft() { instance = this; }
    public static Minecraft getMinecraft() { return instance; }
    public Object addScheduledTask(Runnable task) { tasks.add(task); return null; }
}
