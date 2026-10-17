package net.minecraftforge.fml.common;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
public @interface Mod {
    String modid();
    String name() default "";
    String version() default "";
    boolean clientSideOnly() default false;
    String acceptedMinecraftVersions() default "";
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @interface EventHandler {}
}
