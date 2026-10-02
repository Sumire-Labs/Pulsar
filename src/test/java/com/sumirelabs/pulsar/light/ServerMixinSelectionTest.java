package com.sumirelabs.pulsar.light;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class ServerMixinSelectionTest {
    @Test void integratedServerReceivesTheSamePacketAndTickHooksAsDedicatedServer() throws Exception {
        try(var stream=getClass().getResourceAsStream("/pulsar.mixin.json")) {
            assertNotNull(stream);
            var config=new JsonParser().parse(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var common=new HashSet<String>(); config.getAsJsonArray("mixins").forEach(value->common.add(value.getAsString()));
            assertTrue(common.contains("MixinPlayerChunkMapEntry"),"Physical CLIENT also hosts a logical server");
            assertTrue(common.contains("MixinWorldServer"),"Deferred sends must tick in singleplayer");
        }
    }
}
