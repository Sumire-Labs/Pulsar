package com.sumirelabs.pulsar.dynamic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightHistoryTest {
    private static DynamicLightField field(int x,int emission){return new DynamicLightField(x,0,0,0,new short[]{(short)emission});}
    @Test void remoteInterpolationUsesRecentCoveredGeometryWithoutIncreasingPredictionBounds(){
        var history=new DynamicLightHistory();var motion=new DynamicLightMotion();
        for(int tick=0;tick<100;tick++){
            var current=field(tick,0xEEEE);
            for(int frame=0;frame<6;frame++){
                double target=Math.max(.5,tick-4+frame/6.0);
                var selected=history.select(current,tick,target,.5,.5);
                motion.update(target,.5,.5,selected.x+.5,.5,.5,true,1.0/120);
                assertEquals(target,motion.x(),1e-12);
            }
        }
    }
    @Test void staleGeometryColorsAndTeleportsCannotReviveOldSnapshots(){
        var h=new DynamicLightHistory();var old=field(0,0xEEEE);var next=field(5,0xEEEE);
        h.select(old,0,.5,.5,.5);assertSame(old,h.select(next,1,.5,.5,.5));
        assertSame(next,h.select(next,7,.5,.5,.5));
        h=new DynamicLightHistory();h.select(old,0,.5,.5,.5);
        var recolored=field(5,0xFFFF);assertSame(recolored,h.select(recolored,1,.5,.5,.5));
        h=new DynamicLightHistory();h.select(old,0,.5,.5,.5);
        var teleport=field(20,0xEEEE);assertSame(teleport,h.select(teleport,1,.5,.5,.5));
    }
    @Test void currentAndUpdatedStationaryOcclusionTakePrecedence(){
        var h=new DynamicLightHistory();var old=field(0,0xEEEE);var next=field(5,0xEEEE);
        h.select(old,0,.5,.5,.5);assertSame(next,h.select(next,1,3,.5,.5));
        var edited=field(5,0xEEEE);assertSame(edited,h.select(edited,2,.5,.5,.5));
    }
}
