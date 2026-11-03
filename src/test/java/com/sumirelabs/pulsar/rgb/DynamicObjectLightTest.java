package com.sumirelabs.pulsar.rgb;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.client.renderer.OpenGlHelper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicObjectLightTest {
    @SuppressWarnings("unchecked") private static ThreadLocal<DynamicObjectLight.Sample> context()throws Exception{
        var f=DynamicObjectLight.class.getDeclaredField("CURRENT");f.setAccessible(true);return (ThreadLocal<DynamicObjectLight.Sample>)f.get(null);
    }
    private static DynamicObjectLight.Sample sample()throws Exception{
        var type=Class.forName("sun.misc.Unsafe");var field=type.getDeclaredField("theUnsafe");field.setAccessible(true);
        var entity=(EntityItem)type.getMethod("allocateInstance",Class.class).invoke(field.get(null),EntityItem.class);
        var sample=new DynamicObjectLight.Sample(entity);java.util.Arrays.fill(sample.light,12.5f);return sample;
    }
    @Test void fractionalCoordinatesPreserveBrighterAndFullbrightDraws()throws Exception{
        var context=context();context.set(sample());
        try {
            int unit=OpenGlHelper.lightmapTexUnit;
            assertEquals(200f,DynamicObjectLight.coordinate(unit,192,0));
            assertEquals(224f,DynamicObjectLight.coordinate(unit,224,0));
            assertEquals(240f,DynamicObjectLight.coordinate(unit,240,240));
            assertEquals(16f,DynamicObjectLight.coordinate(unit+1,16,0));
        } finally {context.remove();}
    }
    @Test void restorationCannotReapplyTheLightOfTheObjectBeingClosed()throws Exception{
        var context=context();var parent=sample();context.set(parent);
        try {
            try(var ignored=DynamicObjectLight.suspend()){
                assertNull(DynamicObjectLight.current());
                assertEquals(32f,DynamicObjectLight.coordinate(OpenGlHelper.lightmapTexUnit,32,0));
                try(var nested=DynamicObjectLight.suspend()){assertNull(DynamicObjectLight.current());}
                throw new IllegalStateException("renderer failure");
            } catch(IllegalStateException expected){assertEquals("renderer failure",expected.getMessage());}
            assertSame(parent,DynamicObjectLight.current());
        } finally {context.remove();}
    }
    @Test void unsupportedDrawCannotInheritAnotherObjectsSample()throws Exception{
        var context=context();var parent=sample();context.set(parent);
        try {
            try(var ignored=DynamicObjectLight.begin(null,0)){assertNull(DynamicObjectLight.current());}
            assertSame(parent,DynamicObjectLight.current());
        } finally {context.remove();}
    }
}
