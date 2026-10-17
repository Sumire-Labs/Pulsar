# Initial chunk send retries

An independent-JVM SCALAR/SCALAR test exposed an initial chunk that remained
unsent after travel to the Nether. The source chunk was populated, its canonical
source light was 15, its own and neighboring lighting were ready, and the entry
contained the player. Actual WorldTicks continued, and the entry remained in
Vanilla's pending-send list. All readiness conditions were true at the server
tick tail. Calling the original send method there succeeded immediately.

A control using the exact final diagnostic helper and the cached-rendering
artifact reproduced the stall without a diagnostic send. The production retry
artifact passed Overworld -> Nether -> Overworld -> disconnect/rejoin with that
same helper, without its initial-send assistance.

## Change

When Pulsar blocks a fresh entry's initial send for lighting, the world manager
remembers the entry and chunk. A server-tick-start callback retries completed
work before the next tick adds more lighting. It calls Vanilla's existing
`sendToPlayers` method and retains every population, own-light, neighbor-light
and pending-work check. This also preserves the original chunk-packet,
entity-tracking and Forge-watch-event ordering.

Retries validate both entry and chunk identity against the current world maps.
Repeated deferrals coalesce, replaced/unloaded chunks are dropped, and a failed
send can be deferred again. Callback replacement takes precedence over an old
request; invalidation during a callback cannot resurrect it. Chunk unregister
and world shutdown cancel outstanding requests. The retry table is allocated
lazily, and each world attempts at most 81 sends per pass to bound cold-load work.

The independent tests cover coalescing, readiness waits, stale identities,
failed sends, callback replacement, callback invalidation, cancellation and the
send limit. The final build passes 232 JUnit tests. Four native two-JVM mode
combinations and ten final single-JVM regression cases pass. Server Lightbench
and client rendering measurements are recorded separately in the
[performance report](rgb-render-performance.md); no consistent SCALAR latency
regression is established by the four-pair server comparison.

## Transport test boundary

The native test uses two actual Minecraft JVMs and loopback TCP. An independent
transport defect occurs before RGB negotiation in this legacy runtime:
`SPacketLoginSuccess` sometimes reaches the encoder in PLAY state even though
it requires LOGIN. This also reproduces with both sides in SCALAR. The private
test mod temporarily supplies LOGIN only around that packet's encoding and
restores the previous state; it does not alter gameplay packets or Pulsar RGB
payloads. This transport adjustment is not part of the production mod.

The final production retry tests disable the separate tick-tail initial-send
diagnostic. Their reports explicitly record `test_only_initial_send_retry=false`.
