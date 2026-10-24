# Rendering milestone evidence

`results.json` lists 15 accepted native runs and their exact production artifact
hashes. `native-evidence.zip` retains their logs, reports, two representative screenshots, 220 JUnit
results, source hashes, SCALAR bytecode comparison and owned diagnostic sources.
The 13-run matrix used the initial rendering artifact. Two final daytime actor
runs used the artifact with signed-lightmap and null-logout repairs.

The actor probe measures actual GPU RGBA, full-bright handling, display-list
replay, exception restoration, regular/Fast TESRs, hand scopes, particle and bulk
vertex colors, reserved texture-state restoration and empty scope state after a
frame. Final runs additionally verify negative signed lightmap coordinates and
a cyan daytime sky filter. Alpha must remain unchanged. SCALAR actors retain
neutral colors without opening RGB scopes.

Failed diagnostic attempts are retained separately. The early renderer factory
was registered too late, a post-draw test read used a reset buffer limit, and
the first daytime test confused filter absorption with transmission. These test
setup mistakes were repaired before accepting the corresponding replay.

A separate two-JVM attempt exposed a real null connection during RGB logout;
the repair removes stale/identity-matched clients while keeping live peers. Its
native regression flag is retained. Initial remote login also fails in the
SCALAR/SCALAR control: `SPacketLoginSuccess` reaches the encoder in PLAY state
although it requires LOGIN. This happens before RGB negotiation. The complete
remote matrix and client performance measurements remain pending; these files
do not claim full engine or release readiness.

Diagnostic sources require a normal Pulsar development workspace and native
Minecraft/Forge libraries, which are not included. The runner paths are the
original private study paths and must be adjusted for replay. World fixtures,
account files and third-party mod jars are not distributed.
