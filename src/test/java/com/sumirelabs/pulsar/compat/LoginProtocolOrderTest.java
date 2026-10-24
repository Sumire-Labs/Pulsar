package com.sumirelabs.pulsar.compat;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LoginProtocolOrderTest {
    private static final AttributeKey<String> PROTOCOL = AttributeKey.valueOf("pulsar-test-protocol");

    @Test void queuedLoginWritesReproduceTheOldRaceAndStayInLoginWithTheFix() throws Exception {
        try (var fixture = new Fixture()) {
            assertEquals("PLAY", observedByQueuedLoginWrite(fixture, false));
            for (int i = 0; i < 100; i++)
                assertEquals("LOGIN", observedByQueuedLoginWrite(fixture, true));
        }
    }

    private static String observedByQueuedLoginWrite(Fixture fixture, boolean ordered) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var encoded = new CompletableFuture<String>();
        fixture.channel.attr(PROTOCOL).set("LOGIN");
        fixture.loop.execute(() -> {
            started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test gate timed out"); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            fixture.loop.execute(() -> encoded.complete(fixture.channel.attr(PROTOCOL).get()));
            Runnable transition = () -> fixture.channel.attr(PROTOCOL).set("PLAY");
            if (ordered) LoginProtocolOrder.advance(fixture.channel, transition); else transition.run();
        } finally { release.countDown(); }
        String observed = encoded.get(5, TimeUnit.SECONDS);
        fixture.loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals("PLAY", fixture.channel.attr(PROTOCOL).get());
        return observed;
    }

    @Test void anEventLoopCallerTransitionsInlineBeforeItsNextWrite() throws Exception {
        try (var fixture = new Fixture()) {
            List<String> events = new ArrayList<>();
            fixture.loop.submit(() -> {
                events.add("before");
                LoginProtocolOrder.advance(fixture.channel, () -> {
                    assertTrue(fixture.loop.inEventLoop());
                    events.add("transition");
                });
                events.add("write");
            }).get(5, TimeUnit.SECONDS);
            assertEquals(List.of("before", "transition", "write"), events);
        }
    }

    @Test void aClosedConnectionIsNotReactivated() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.channel.close().syncUninterruptibly();
            AtomicInteger calls = new AtomicInteger();
            LoginProtocolOrder.advance(fixture.channel, calls::incrementAndGet);
            fixture.loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, calls.get());
        }
    }

    @Test void closingBeforeAQueuedTransitionAlsoSkipsIt() throws Exception {
        try (var fixture = new Fixture()) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            fixture.loop.execute(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                fixture.loop.execute(() -> fixture.channel.close());
                LoginProtocolOrder.advance(fixture.channel, calls::incrementAndGet);
            } finally { release.countDown(); }
            fixture.loop.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, calls.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DefaultEventLoop loop = new DefaultEventLoop();
        final LocalChannel channel = new LocalChannel();
        Fixture() { loop.register(channel).syncUninterruptibly(); }
        public void close() {
            channel.close().syncUninterruptibly();
            loop.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
