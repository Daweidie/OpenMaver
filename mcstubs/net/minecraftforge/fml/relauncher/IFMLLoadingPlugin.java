package net.minecraftforge.fml.relauncher;
import java.util.Map;
public interface IFMLLoadingPlugin {
    String[] getASMTransformerClass(); String getModContainerClass();String getSetupClass();void injectData(Map<String,Object> data);String getAccessTransformerClass();
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface MCVersion {String value();}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface SortingIndex {int value();}
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface TransformerExclusions {String[] value();}
}
