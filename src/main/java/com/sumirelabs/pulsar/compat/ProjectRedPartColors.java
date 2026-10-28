package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.item.EnumDyeColor;
import java.lang.reflect.Method;
import java.util.Optional;

/** ProjectRed 1.12.2: use the part's own scalar emission for power/inversion/volume.
 * Only its dye index is adapted; no world callbacks or duplicated redstone logic.
 */
final class ProjectRedPartColors {
    private static final ClassValue<Optional<Method>> COLOR = new ClassValue<>() {
        protected Optional<Method> computeValue(Class<?> type) {
            String getter=null;
            for(Class<?> c=type;c!=null;c=c.getSuperclass()) {
                if(c.getName().equals("mrtjp.projectred.illumination.BaseLightPart")
                        || c.getName().equals("mrtjp.projectred.illumination.LightButtonPart"))getter="getColor";
                if(c.getName().equals("mrtjp.projectred.illumination.LightMicroMaterial"))getter="colour";
            }
            if(getter!=null)try{return Optional.of(type.getMethod(getter));}
            catch(ReflectiveOperationException | LinkageError ignored){}
            return Optional.empty();
        }
    };
    private ProjectRedPartColors() {}
    static int color(Object partOrMaterial,int level) throws ReflectiveOperationException {
        if(partOrMaterial==null)return -1;
        Method getter=COLOR.get(partOrMaterial.getClass()).orElse(null);
        if(getter==null)return -1;
        int dye=((Number)getter.invoke(partOrMaterial)).intValue();
        if(dye<0 || dye>15)return -1;
        int hue=RgbDefaultPalette.glassTransmittance(EnumDyeColor.byMetadata(dye));
        return RgbLight.scale(RgbLight.normalize(hue),level);
    }
}
