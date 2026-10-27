package com.sumirelabs.pulsar.compat;

import com.sumirelabs.pulsar.rgb.RgbDefaultPalette;
import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import java.lang.reflect.Method;
import java.util.Optional;

/** ProjectRed 1.12.x TileLamp.getColor(), verified at 11da8db1f23ca9659f81ad6427e4f489b6dd82b5.
 * Called only by the RGB positional sampler on the world thread, never by BFS workers.
 */
public final class ProjectRedLampColors {
    private static final ClassValue<Optional<Method>> GET_COLOR=new ClassValue<>() {
        protected Optional<Method> computeValue(Class<?> type) {
            try {return Optional.of(type.getMethod("getColor"));}
            catch(NoSuchMethodException absent){return Optional.empty();}
        }
    };
    private ProjectRedLampColors() {}
    public static int emission(IBlockState state,IBlockAccess world,BlockPos pos) {
        int level=Math.max(0,Math.min(15,state.getLightValue(world,pos)));
        if(level==0)return 0; // Includes normal and inverted lamps' current power state.
        var tile=world.getTileEntity(pos);
        if(tile!=null)try {
            var method=GET_COLOR.get(tile.getClass());
            Object value=method.isEmpty()?null:method.get().invoke(tile);
            if(value instanceof Number number && number.intValue()>=0 && number.intValue()<16) {
                int hue=RgbDefaultPalette.glassTransmittance(EnumDyeColor.byMetadata(number.intValue()));
                return RgbLight.scale(RgbLight.normalize(hue),level);
            }
        } catch(ReflectiveOperationException unavailable) { /* Unknown replacement tile: preserve neutral scalar light. */ }
        return level*0x111;
    }
}
