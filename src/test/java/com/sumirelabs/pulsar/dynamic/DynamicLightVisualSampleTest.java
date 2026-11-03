package com.sumirelabs.pulsar.dynamic;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightVisualSampleTest {
    private static DynamicLightField field(int x,int emission,boolean filtered){
        int radius=DynamicLightField.CAPTURE_RADIUS,side=radius*2+1,n=DynamicLightField.cube(radius);
        short[] cells=new short[n],loss=new short[n*4];cells[radius+side*(radius+side*radius)]=(short)emission;
        if(filtered)for(int i=0;i<n;i++){loss[i*4]=(short)DynamicLightField.BLOCKED;loss[i*4+1]=(short)DynamicLightField.BLOCKED;}
        return new DynamicLightField(x,0,0,radius,cells,loss);
    }
    @Test void movingEmitterKeepsItsOwnStrengthAcrossFieldAndCellBoundaries(){
        for(int frame=0;frame<240;frame++){
            double x=-2+frame/60.0;float[] light=new float[4];
            DynamicLightVisualSample.merge(field((int)Math.floor(x-.7),0xEEB8,false),x,.125,.5,x,.125,.5,true,light);
            assertArrayEquals(new float[]{14,11,8,14},light);
        }
    }
    @Test void nearbyObjectsUseContinuousDistanceInsteadOfQuantizedCells(){
        float previous=0;
        for(int frame=0;frame<240;frame++){
            double sx=-2+frame/240.0;float[] light=new float[4];
            DynamicLightVisualSample.merge(field((int)Math.floor(sx),0xEEB8,false),sx,.5,.5,1.25,.5,.5,false,light);
            assertEquals(14*(1-(1.25-sx)/DynamicLightField.RANGE),light[0],1e-5);
            if(frame>0)assertTrue(Math.abs(light[0]-previous)<.01);previous=light[0];
        }
    }
    @Test void filteredChannelsAndUnknownGeometryCannotAddEnergy(){
        float[] light=new float[4];
        DynamicLightVisualSample.merge(field(0,0xFFFF,true),.5,.5,.5,1.25,.5,.5,false,light);
        assertTrue(light[0]>0);assertEquals(0,light[1]);assertEquals(0,light[2]);assertTrue(light[3]>0);
        Arrays.fill(light,0);var missing=new DynamicLightField(0,0,0,0,new short[]{(short)0xFFFF});
        DynamicLightVisualSample.merge(missing,.5,.5,.5,1,.5,.5,false,light);assertArrayEquals(new float[4],light);
        DynamicLightVisualSample.merge(field(0,0xFFFF,false),.5,.5,.5,20,.5,.5,false,light);assertArrayEquals(new float[4],light);
    }
    @Test void blendingSourcesUsesMaximumNotAddition(){
        float[] light=new float[4];var red=field(0,0xFF00,false);var blue=field(0,0xF00F,false);
        DynamicLightVisualSample.merge(red,.5,.5,.5,.5,.5,.5,false,light);
        DynamicLightVisualSample.merge(blue,.5,.5,.5,.5,.5,.5,false,light);
        assertArrayEquals(new float[]{15,0,15,15},light);
    }
}
