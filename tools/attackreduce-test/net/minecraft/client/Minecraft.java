package net.minecraft.client;

import java.util.concurrent.ConcurrentLinkedQueue;

public class Minecraft {
    public Object thePlayer;
    public Object theWorld;
    public Object currentScreen;
    public Object objectMouseOver;
    public Object playerController;
    public final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
    public Object addScheduledTask(Runnable task) { tasks.add(task); return null; }
    public Object func_152344_a(Runnable task) { tasks.add(task); return null; }
}
