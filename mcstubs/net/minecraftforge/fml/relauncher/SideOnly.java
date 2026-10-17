package net.minecraftforge.fml.relauncher;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface SideOnly { Side value(); }
