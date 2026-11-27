package net.minecraft.client;

import java.util.concurrent.ConcurrentLinkedQueue;

public class Minecraft {
    public static Minecraft instance;
    public static Minecraft getMinecraft() { return instance; }
    public Object thePlayer;
    public Object theWorld;
    public Object currentScreen;
    public Object objectMouseOver;
    public Object playerController;
    public int leftClickCounter;
    public boolean inGameHasFocus=true;
    public net.minecraft.client.settings.GameSettings gameSettings = new net.minecraft.client.settings.GameSettings();
    public final Timer timer = new Timer();
    public static final class Timer { public float timerSpeed = 1; }
    public final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();
    private final Thread mainThread = Thread.currentThread();
    public Object addScheduledTask(Runnable task) {
        if (Thread.currentThread() == mainThread) task.run();
        else tasks.add(task);
        return null;
    }
    public Object func_152344_a(Runnable task) { return addScheduledTask(task); }
}
