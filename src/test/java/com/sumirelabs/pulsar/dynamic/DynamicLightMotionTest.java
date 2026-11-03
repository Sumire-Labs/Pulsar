package com.sumirelabs.pulsar.dynamic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicLightMotionTest {
    @Test void renderedEntityPositionIsFollowedWithoutASecondInterpolationDelay() {
        var motion=new DynamicLightMotion();
        for(int frame=0;frame<120;frame++) {
            double x=frame<60?frame/60.0:(120-frame)/60.0;
            motion.update(x,.2,-.4,0,0,0,true,1.0/120);
            assertEquals(x,motion.x(),1e-12);assertEquals(.2,motion.y(),1e-12);assertEquals(-.4,motion.z(),1e-12);
        }
    }
    @Test void delayedServerFieldsStillAllowFastMovementWithinTheSampledGeometry() {
        var motion=new DynamicLightMotion();
        for(int frame=0;frame<300;frame++) {
            double position=frame/10.0;
            double sampledCenter=Math.floor(Math.max(0,position-1.2))+.5;
            motion.update(position,6.62,6, sampledCenter,6.5,6.5,true,1.0/120);
            assertEquals(position,motion.x(),1e-12);
        }
        motion.update(3.2,.12,-.5,0,0,0,true,1.0/120);
        assertEquals(3.2,motion.x(),1e-12,"A delayed field clamped a valid fast-flight position");
        motion.update(4.4,.12,-.5,0,0,0,true,1.0/120);
        assertEquals(4.4,motion.x(),1e-12,"Remote velocity correction exceeded the sampled margin");
    }
    @Test void predictionCannotLeaveItsOcclusionPaddingAndTeleportsDoNotLeaveATrail() {
        var motion=new DynamicLightMotion();motion.update(0,0,0,0,0,0,true,.05);
        motion.update(20,0,0,0,0,0,true,.05);
        assertTrue(motion.x()<=DynamicLightField.MAX_RENDER_OFFSET);
        motion.update(100,0,0,100,0,0,true,.05);assertEquals(100,motion.x());
        assertTrue(DynamicLightField.RANGE+DynamicLightField.MAX_RENDER_OFFSET+.501+Math.sqrt(3)*.5<DynamicLightField.VISUAL_RANGE,
                "Mask volume cannot cover surface offset and trilinear sampling at the prediction limit");
    }
}
