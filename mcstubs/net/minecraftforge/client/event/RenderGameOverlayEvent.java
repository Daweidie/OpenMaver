package net.minecraftforge.client.event;
import net.minecraft.client.gui.ScaledResolution;
public class RenderGameOverlayEvent {
    public enum ElementType { ALL, TEXT, EXPERIENCE }
    public final ScaledResolution resolution = new ScaledResolution();
    public final ElementType type;
    public final float partialTicks = 0f;
    public RenderGameOverlayEvent(ElementType type) { this.type = type; }
    public static class Post extends RenderGameOverlayEvent { public Post(ElementType t) { super(t); } }
    public static class Pre extends RenderGameOverlayEvent { public Pre(ElementType t) { super(t); } }
}
