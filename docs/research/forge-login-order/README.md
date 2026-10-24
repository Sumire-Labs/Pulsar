# Forge LOGIN/PLAY ordering regression

Date: 2026-10-19. Follow-up to shader/RGB completion commit `d1a4008` on
`feat/rgb-engine`. Historical RGB acceptance evidence is unchanged; its failed
logins refer to the earlier artifact, not this correction.

## Cause and correction

`NetworkManager.dispatchPacket` schedules an off-thread write on the channel's
Netty event loop. The queued login-success write captures LOGIN as its current
protocol. Forge `NetworkDispatcher.serverInitiateHandshake` can change the same
channel to PLAY on the server thread before that task runs. The encoder then
looks up `SPacketLoginSuccess` in the PLAY packet table and throws
"Can't serialize unregistered packet". This was observed in SCALAR as well as RGB.

The correction intercepts only that initial server-side state-change call.
On an event-loop caller it stays inline; otherwise the state change joins the
same FIFO as the earlier packet write. A closed channel is skipped. Forge's
player/world initialization and handshake logic stay on their existing threads.
The packet encoder never changes protocol state to hide an encoding mismatch.
No blocking wait, sleep, retry, executor thread or lighting-engine change is
introduced. The common mixin is selected for both lighting modes.

The released [Cleanroom 0.7.7 dispatcher source](https://github.com/CleanroomMC/Cleanroom/blob/0.7.7-alpha/module/forge/src/main/java/net/minecraftforge/fml/common/network/handshake/NetworkDispatcher.java)
was audited for the call order. The build uses Cleanroom 0.6.13; native TCP
acceptance uses 0.7.7-alpha with Java 25, Fugue 0.25.4 and relauncher 1.2.1.
The remapped production artifact's refmap resolves the state-change target to
`NetworkManager.func_150723_a` and retains the Forge method selector.

## Tests and interpretation

Four new JUnit tests use an actual Netty `DefaultEventLoop`/`LocalChannel`.
They demonstrate the original wrong-state observation, then check 100 ordered
trials, in-loop ordering, already closed channels and closure before a queued
transition. The full unit suite has 274 passing tests. The 80 lighting-package
class bytecodes match the clean baseline, so no lighting timing benchmark is
used to claim a speedup or regression.

The private native fixture holds the server event loop immediately before
`LoginSuccess` dispatch and releases it after Forge's `serverToClientHandshake`
returns. It never changes the protocol state. This deterministically exposes
the exact race instead of hoping for a slow network schedule. The gate is
bounded to five seconds and any gate timeout invalidates the result. The
observer reports the packet class, actual protocol and required protocol.

The clean baseline reproduces the specific encoding failure in all six remote
Forge connection attempts, with six held gates and zero gate timeouts. That is
an expected-failure control, not a successful client run. Candidate acceptance
requires zero login-state mismatches, no failed/retried login (exactly initial
connection plus intentional rejoin), and valid remote-world lighting checks.
The mode matrix includes RGB/RGB, RGB/SCALAR, SCALAR/RGB and SCALAR/SCALAR,
Overworld/Nether/Overworld and a new connection on rejoin. An ordinary ungated
RGB/RGB run separately checks the normal path.

A first vanilla probe was rejected before login by diagnostic mods that require
Forge. It does not count as a login-order test. The separate vanilla fixture
marks only the four diagnostic mod containers optional via their @Mod
`acceptableRemoteVersions="*"` annotation. Production Pulsar and Forge's
validation/encoding are unchanged. The metadata transformation and fixture hashes
are recorded. Unmarked protocol-340 clients then exercise the vanilla branch and
compression negotiation; SCALAR is used so this does not imply vanilla clients
support RGB_MAX gameplay lighting. External authenticated servers and unrelated
pre-login mods are outside this offline loopback matrix.


| Connection path | Baseline under held-write condition | Corrected under same condition |
|---|---|---|
| Forge clients | 6/6 login-state encoding failures | 8/8 connections, no failed login, across four RGB/SCALAR combinations |
| Unmarked vanilla protocol-340 clients | 8/8 login-state encoding failures | 8/8 LOGIN/PLAY joins, compression threshold 256 |

An additional ungated RGB/RGB run accepts its initial connection and intentional
rejoin without a login retry. The correction therefore passes 16 deliberately
contended connections plus two ordinary connections in the recorded fixture.
These are causal regression checks, not an estimate of the spontaneous failure
rate in arbitrary modpacks.

Rapid intentional disconnects can retain unrelated vanilla queued-packet errors;
acceptance checks the identified LOGIN/PLAY encoding failure, not a claim that
every transport log is error free. Test-only gates/metadata tools are not in the
production mod. The Pulsar license remains LGPL-3.0.

## Evidence

`summary.json` records each run's original status, peer results and stage hashes.
`raw/` retains reports and compressed host/client logs. `engine-byte-proof.json`
and `unit-tests.json` record the isolated engine and unit checks. `reproduce.zip`
contains only the original diagnostic helper/source and local scripts, not
Minecraft, Forge or other mod implementations. It requires disposable Prism
profiles/dependencies and is not a portable one-click test installer. The
controlled Netty regression also runs directly in the normal Gradle test suite.
