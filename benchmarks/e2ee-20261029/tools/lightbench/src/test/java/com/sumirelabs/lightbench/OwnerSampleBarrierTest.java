package com.sumirelabs.lightbench;
import com.sumirelabs.pulsar.world.PulsarWorld;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OwnerSampleBarrierTest {
    public static class Manager {
        int publications, completed;
        boolean queued, sample=true;
        public void publishContextualLight(){publications++;if(sample){queued=true;sample=false;}}
        public boolean hasUpdates(){if(!queued)return false;queued=false;completed++;if(completed==1)sample=true;return true;}
        public boolean awaitPendingWork(int x,int z){return true;}
    }
    public static class Owner implements PulsarWorld {
        final Manager manager=new Manager();
        public Object pulsar$getLightManager(){return manager;}
    }
    @Test void lateOwnerSamplesCannotBeMistakenForCompletion()throws Exception {
        var owner=new Owner();var probe=LightProbe.createForWorld(owner);
        assertEquals(LightProbe.Engine.PULSAR,probe.engine());probe.drainLight();
        assertEquals(2,owner.manager.completed);assertTrue(owner.manager.publications>=3);
        assertEquals("pulsar_owner_samples_then_global_completion_e2ee1",probe.completionBarrierName());
    }
}
