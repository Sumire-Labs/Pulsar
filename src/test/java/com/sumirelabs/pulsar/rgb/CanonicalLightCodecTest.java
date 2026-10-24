package com.sumirelabs.pulsar.rgb;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalLightCodecTest {
    @Test void requestCarriesItsOwnVersionWithoutChangingTheRgbHandshake() {
        var bytes = Unpooled.buffer(); new CanonicalLightNetwork.Hello().toBytes(bytes);
        assertEquals(4, bytes.readableBytes());
        var request = new CanonicalLightNetwork.Hello(); request.fromBytes(bytes);
        assertEquals(1, request.version);
        assertEquals(2, RgbHello.VERSION);
    }
    @Test void bothAcknowledgementValuesRoundTrip() {
        for (boolean enabled : new boolean[]{false, true}) {
            var bytes = Unpooled.buffer(); new CanonicalLightNetwork.Ack(enabled).toBytes(bytes);
            var response = new CanonicalLightNetwork.Ack(); response.fromBytes(bytes);
            assertEquals(enabled, response.enabled); assertEquals(0, bytes.readableBytes());
        }
    }
    @Test void rejectsTruncatedAndTrailingDataAsACapability() {
        for (int length : new int[]{0,1,3,5}) {
            var bytes = Unpooled.buffer(); bytes.writeZero(length);
            assertThrows(IllegalArgumentException.class, () -> new CanonicalLightNetwork.Hello().fromBytes(bytes));
        }
        for (int length : new int[]{0,2,4}) {
            var bytes = Unpooled.buffer(); bytes.writeZero(length);
            assertThrows(IllegalArgumentException.class, () -> new CanonicalLightNetwork.Ack().fromBytes(bytes));
        }
    }
}
