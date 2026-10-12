# Follow-up investigations

Requested by the user on 2026-10-10 after the late-partner scheduler study.

Completed: isolate asynchronous dispatch and completion waiting using the current main scheduler (3347942). [Results and raw data](handoff/README.md): 48 primary trials and12 sampled trials, all light checks passed. With notifications held until complete, worker/poll versus caller placement was66.65µs versus63.60µs; Sky opening was610.04µs versus622.08µs. Placement shows a small median difference with overlapping run ranges; Sky does not improve consistently when handoff is removed. The remaining Sky gap is not explained by handoff alone. All2,838 compared captured phase/index pairs matched task, position and propagation-entry counts. Production runtime is unchanged.

Study workspace: `E:\Github\pulsar-handoff-20261010`. Completed measurements on2026-10-10; analyzed and recorded on2026-10-12.

Resumed2026-10-12 at the user's request. [The controlled hot-path investigation](../2026-10-12-lighting-hotpaths/README.md) identifies source-face checks within every Sky neighbor visit and generic handling after verified plain-air checks as actionable implementation costs. The original held-control's1ms inter-edit wait was corrected. Empty-section cache normalization was tested and rejected for this fixture: runtime inspection found no non-null empty sections. Production changes retain sided/contextual/fluid behavior and parallel count.

Further work should use these results rather than assuming the older held controls isolate transfer cost. Previously deferred items were:

- Propagation-loop implementation differences, including data access and queue processing, after controlling batching and execution placement.
- Direction-dependent opacity and face checks: preserve their supported semantics; quantify their cost independently.
- Other extra bookkeeping and publication paths, only where measurements identify remaining time.

Do not treat these deferred items as proven causes of the remaining gap. Do not remove fallback notifications or compatibility features solely to match the reference engine.
