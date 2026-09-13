# Experimental BLOCK-lane continuations

`WorldLightingBackend.bindBlockContinuation` receives a thread-safe, coalescing
request after the BLOCK worker has been constructed. The worker invokes
`runBlockContinuation` once after a scalar drain when a request is pending.
No scalar block position or chunk task is invented by this signal.

The backend may request another turn from within its callback. Its pending
request survives the current invocation, and an idle server worker resumes
without another block update. The server checks ordinary scalar work before
the next continuation. A thin client performs at most one continuation per
`processPending` invocation; it continues at a later client drain.

This is a scheduling hook, not a generic executor or a preemptive time limit.
The backend must limit the work performed in one callback. An individual
scalar drain retains Pulsar's existing priority/budget behavior; this API does
not promise that arbitrary initial-lighting work finishes within a fixed time.

The signal is consumed before calling the backend, so a new request made while
the callback runs is not cleared accidentally. Requests coalesce until then.
The worker also drains surplus wake permits while runnable, preventing repeated
self-continuations from growing the semaphore count indefinitely. Pending queue
and continuation state remain separately observable, so this does not discard
actual work. Shutdown closes the signal and wakes a waiting worker; late requests
cannot reopen it. Already claimed/in-flight work may outlive `close`, so the
backend must invalidate its own publications during world shutdown.

The callback executes outside a scalar task's completion bookkeeping. Extra
channels must expose their own readiness state. A scalar completion future
does not imply that deferred RGB work has completed. The backend must never wait
on the current lane, force-load absent chunks or mark scalar chunks ready.

Defaults are no-ops, preserving existing backend implementations. Native SKY
work does not gain an extra backend callback. A pure continuation does not
acquire or construct a scalar propagation engine.

Tests cover request coalescing, requests during a callback, exception state,
shutdown, the empty-check/wait race, an idle real worker with no scalar tasks,
10,000 self-continuations without permit accumulation, and thin-client drains.
