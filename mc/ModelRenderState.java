import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Saves the driver state AND Minecraft's cached state around an extra model render. */
final class ModelRenderState implements AutoCloseable {
    private static final List<CachedField> CACHE = findCache();
    private final Object[] values = new Object[CACHE.size()];
    private final int matrixMode;

    ModelRenderState() {
        try {
            for (int i = 0; i < values.length; i++) values[i] = CACHE.get(i).get();
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot save Minecraft render state", error);
        }
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        // CURRENT_BIT saves each texture unit's lightmap coordinates. This is safe
        // because the matching color/lightmap caches are restored as well.
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
    }

    @Override
    public void close() {
        try {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            GL11.glMatrixMode(matrixMode);
        } finally {
            try {
                for (int i = 0; i < values.length; i++) CACHE.get(i).set(values[i]);
            } catch (IllegalAccessException error) {
                throw new IllegalStateException("Cannot restore Minecraft render state", error);
            }
        }
    }

    private static List<CachedField> findCache() {
        List<CachedField> result = new ArrayList<CachedField>();
        try {
            collect(GlStateManager.class, null, result);
            // RenderManager updates these brightness mirrors as well as GL coordinates.
            // Discover by type so both MCP and SRG names work.
            Class<?> helper = Class.forName("net.minecraft.client.renderer.OpenGlHelper");
            for (Field field : helper.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())
                        && field.getType() == float.class) {
                    field.setAccessible(true);
                    result.add(new CachedField(field, null));
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot inspect Minecraft render cache", error);
        }
        return result;
    }

    private static void collect(Class<?> type, Object owner, List<CachedField> result)
            throws IllegalAccessException {
        for (Field field : type.getDeclaredFields()) {
            int flags = field.getModifiers();
            if (Modifier.isStatic(flags) != (owner == null)) continue;
            Class<?> fieldType = field.getType();
            if (fieldType.isPrimitive()) {
                if (!Modifier.isFinal(flags)) {
                    field.setAccessible(true);
                    result.add(new CachedField(field, owner));
                }
            } else if (fieldType.getEnclosingClass() == GlStateManager.class) {
                field.setAccessible(true);
                Object child = field.get(owner);
                if (child != null) collect(fieldType, child, result);
            } else if (fieldType.isArray()
                    && fieldType.getComponentType().getEnclosingClass() == GlStateManager.class) {
                field.setAccessible(true);
                Object children = field.get(owner);
                if (children == null) continue;
                for (int i = 0; i < Array.getLength(children); i++) {
                    Object child = Array.get(children, i);
                    if (child != null) collect(child.getClass(), child, result);
                }
            }
        }
    }

    private static final class CachedField {
        final Field field;
        final Object owner;

        CachedField(Field field, Object owner) {
            this.field = field;
            this.owner = owner;
        }

        Object get() throws IllegalAccessException { return field.get(owner); }
        void set(Object value) throws IllegalAccessException { field.set(owner, value); }
    }
}
