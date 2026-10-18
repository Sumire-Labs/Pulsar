# Retry completed chunk updates before fresh tick work

SCALAR could finish lighting a large block edit without delivering the edited
section to an existing client. The server retained the correct blocks and
light; the client kept air. The ordinary world notification had been issued,
but its section packet was deferred while lighting was pending. Retrying only
at the world-tick tail could repeatedly encounter new lighting work. RGB
already had an additional server-tick-start retry, masking this common-path
problem in RGB tests.

The repair moves that start-of-tick retry into `CommonProxy`, after initial
chunk-send retries, so it covers both engines. The existing world-tail retry
remains. It preserves chunk identity, lighting-readiness, watch-event and
entity ordering checks. An empty-queue guard avoids constructing drain
callbacks when no watch, section or range refresh is waiting. No propagation
algorithm or SCALAR renderer changes are involved.

## Native causal replay

The control builds a sealed room using ordinary stone, glass and glowstone.
It waits for a populated chunk, issues normal world block notifications and
checks the actual client block state and light. Read-only probes record packet
construction/receipt and notifications. They do not alter packet content or
call a diagnostic resend in the accepted comparison.

| Artifact | Outcome | Server source | Client source | Forced refresh |
|---|---|---:|---|---|
| `8c5454c` baseline | Fails to deliver the edit | Light 15 | Air, light 0 | No |
| Baseline plus only this three-file repair | Passes | Light 15 | Glowstone, light 15 | No |

The final comparison uses the same instance, runner and diagnostic hash.
The repaired artifact was built in a clean worktree containing only the three
production-file changes, separate from the in-progress RGB feature work. All
232 existing unit tests pass there. Initial investigation also reproduced the
failure with the RGB feature candidate in SCALAR; the failure is not attributed
to its new APIs or packed propagation.

The old run records a populated chunk, completed lighting, an already-sent
entry, real `markAndNotifyBlock` calls and a retained section mask. A diagnostic
full resend corrected the client immediately in an earlier investigation,
but that assisted attempt is not counted as a production pass. The unassisted
repair emits the normal partial section packet with mask 32, retaining the
client's chunk lifecycle.

Platform: Minecraft 1.12.2, Cleanroom 0.6.12-alpha, Java 25, fixed SCALAR mode.
These are delivery correctness tests, not performance measurements. See
[reports, hashes and replay sources](deferred-packet-retry/README.md).
