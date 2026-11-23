package net.minecraftforge.client.event;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;

/** Forge 1.8.9 label event API; compile-time only. */
public abstract class RenderLivingEvent<T extends EntityLivingBase> {
    public final EntityLivingBase entity;
    public final RendererLivingEntity<T> renderer;
    public final double x, y, z;
    private boolean canceled;

    protected RenderLivingEvent(EntityLivingBase entity, RendererLivingEntity<T> renderer,
            double x, double y, double z) {
        this.entity = entity;
        this.renderer = renderer;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean isCanceled() { return canceled; }
    public void setCanceled(boolean value) { canceled = value; }

    public abstract static class Specials<T extends EntityLivingBase> extends RenderLivingEvent<T> {
        protected Specials(EntityLivingBase entity, RendererLivingEntity<T> renderer,
                double x, double y, double z) {
            super(entity, renderer, x, y, z);
        }

        public static class Pre<T extends EntityLivingBase> extends Specials<T> {
            public Pre(EntityLivingBase entity, RendererLivingEntity<T> renderer,
                    double x, double y, double z) {
                super(entity, renderer, x, y, z);
            }
        }
    }
}
