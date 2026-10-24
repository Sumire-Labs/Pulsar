# Deferred packet retry evidence

`comparison.json` records an unassisted failing baseline and passing repair
using the same disposable instance and exact diagnostic artifact. The repair
artifact contains only changes to CommonProxy, WorldLightManager and
RgbNetwork. All 232 unit tests pass in that clean replay worktree.

`native-evidence.zip` retains native logs, reports, staging/status records,
diagnostic sources, replay scripts, the minimal production diff and JUnit XML.
No game worlds, account data, Minecraft binaries or third-party mod jars are
distributed. Private paths in the scripts must be adjusted for replay.

The probe is read-only. Its optional `assist` command exists solely to diagnose
the original failure; both final runs use `parityaudit ready`, and explicitly
record `forced_refresh=false`. A populated-fixture wait alone did not resolve
the baseline failure. The repair delivers a normal partial chunk packet.
