package com.sumirelabs.pulsar.light;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublishedIntTableTest {
    @Test void deletionChurnAndSignedKeysMatchReferenceMap() {
        var actual = new PublishedIntTable<Integer>();
        var expected = new HashMap<Integer,Integer>();
        var random = new Random(20261030);
        for (int i=0;i<100_000;i++) {
            int key = random.nextInt(2048)-1024;
            if (random.nextBoolean()) { actual.put(key,i); expected.put(key,i); }
            else { actual.remove(key); expected.remove(key); }
            assertEquals(expected.get(key),actual.get(key));
            assertEquals(expected.size(),actual.size());
        }
        actual.put(Integer.MIN_VALUE,1); expected.put(Integer.MIN_VALUE,1);
        actual.put(Integer.MAX_VALUE,2); expected.put(Integer.MAX_VALUE,2);
        var keys = new HashSet<Integer>();actual.forEachKey(keys::add);
        assertEquals(expected.keySet(),keys);
        expected.forEach((k,v)->assertEquals(v,actual.get(k)));
        keys.forEach(actual::remove);
        assertTrue(actual.isEmpty());
    }

    @Test void readersNeverAssociateAValueWithAnotherKeyDuringResizeAndDeletion() throws Exception {
        var table = new PublishedIntTable<Integer>();
        for (int i=0;i<256;i++) table.put(i,i);
        var running=new AtomicBoolean(true);
        try (var executor=Executors.newFixedThreadPool(2)) {
            var reader=executor.submit(()->{
                int iteration=0;
                while(running.get() || iteration<100_000) {
                    int key=iteration++ & 255;
                    assertEquals(key,table.get(key));
                    int negative=-key-1;
                    Integer value=table.get(negative);
                    if(value!=null)assertEquals(negative,value);
                }
            });
            try {
                for(int i=0;i<100_000;i++) {
                    int key=-(i & 8191)-1;
                    table.put(key,key);
                    if((i&1)==0)table.remove(key);
                }
            } finally {running.set(false);}
            reader.get(10,TimeUnit.SECONDS);
        }
    }

    @Test void pooledSamplesUseIdentityAndPreserveBothLayers() {
        var samples=new ContextualLightSnapshot.Samples<Object>();
        Object a=new String("same"),b=new String("same");
        assertSame(samples.sample(a,3,b,8),samples.sample(a,3,b,8));
        assertNotSame(samples.sample(a,3,b,8),samples.sample(b,3,a,8));
        assertNotSame(samples.sample(a,3,b,8),samples.sample(a,4,b,8));
        var first=new ContextualLightSnapshot<>(samples);
        var second=new ContextualLightSnapshot<>(samples);
        first.publish(4,a,3,b,8);second.publish(4,a,3,b,8);
        first.publish(4,a,15,b,1);
        assertEquals(3,second.read(4,a,-1));assertEquals(8,second.read(4,b,-1));
    }
}
