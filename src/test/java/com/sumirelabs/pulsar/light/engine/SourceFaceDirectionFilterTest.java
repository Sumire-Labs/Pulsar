package com.sumirelabs.pulsar.light.engine;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class SourceFaceDirectionFilterTest {
    @Test
    void prefilterPreservesTheOriginalNeighborMembershipAndOrderForEveryMask() {
        for(int directions=0;directions<64;directions++) {
            for(int blocked=0;blocked<64;blocked++) {
                var expected=new ArrayList<PulsarEngine.AxisDirection>();
                for(var direction:PulsarEngine.OLD_CHECK_DIRECTIONS[directions]) {
                    if((blocked&(1<<direction.ordinal()))==0) expected.add(direction);
                }
                assertArrayEquals(expected.toArray(PulsarEngine.AxisDirection[]::new),
                        ScalarSkyEngine.filterDirections(directions,blocked),
                        "directions="+directions+", blocked="+blocked);
            }
        }
    }
}
