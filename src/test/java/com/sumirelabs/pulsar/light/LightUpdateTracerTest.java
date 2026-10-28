package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.config.PulsarConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightUpdateTracerTest {
    @Test void boundedConcurrentSamplesKeepExactCountsAndResetWindows() throws Exception {
        boolean old=PulsarConfig.debug.traceLightUpdates;
        try {
            LightUpdateTracer.drain();PulsarConfig.debug.traceLightUpdates=true;
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
                var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int t=0;t<4;t++){int offset=t*100000;jobs.add(pool.submit(()->{
                    for(int i=0;i<100000;i++)LightUpdateTracer.record(LightUpdateTracer.Kind.LIGHT_ENQUEUE,-1,offset+i,-4096,17);
                }));}
                for(var job:jobs)job.get();
            }
            var result=LightUpdateTracer.drain();
            assertEquals(400000,result.counts()[0]);
            assertTrue(result.positions().size()<=1025);assertTrue(result.origins().size()<=65);
            assertTrue(result.positions().keySet().stream().anyMatch(key->key.contains(",-4096,")));
            assertEquals(0,LightUpdateTracer.drain().counts()[0]);
            PulsarConfig.debug.traceLightUpdates=false;
            LightUpdateTracer.record(LightUpdateTracer.Kind.RENDER_REQUEST,0,1,2,3);
            assertEquals(0,LightUpdateTracer.drain().counts()[1]);
        } finally {PulsarConfig.debug.traceLightUpdates=old;LightUpdateTracer.drain();}
    }
}
