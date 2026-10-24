# RGB engine completion record

The SuperNova-style adaptation is implemented and validated for the scoped
Vanilla/Forge and Celeritas targets. The [source comparison](supernova-parity.md)
and [native/performance evidence](supernova-parity/README.md) are authoritative.

Completed work includes packed RGB block/sky queues, immutable publication and
persistent caches, static/positional emission and transmittance APIs, validated
user palettes, five SuperNova blends, runtime terrain refresh, and terrain,
fluid, entity, normal/Fast TESR, particle and held-item rendering.

RGB/SQUARED_WEIGHT/RGB_MAX are the new-configuration defaults. Valid explicit
SCALAR settings are retained. VANILLA preserves independent gameplay light;
RGB_MAX projects the brightest component and requires compatible clients.
SCALAR retains its engine classes and excludes RGB chunk/state/render fields.
LGPL-3.0 is unchanged.

The original acceptance includes 260 unit tests, 43 native runs across recorded stages, four
unassisted two-JVM mode combinations, config/cache transitions, current Cleanroom
0.7.7 checks, CQR/Thaumcraft/Fluidlogged regressions, the initial 28-JVM experiment
and the 16-JVM server retest after addressing the observed regression. Client
measurements retain their original artifact attribution and bytecode proof.

Versioned Demonica/Actinium shader support is documented in the
[shader validation record](rgb-shaders/README.md). Arbitrary replacement
renderers and other third-party integrations still require separate validation.
The original acceptance retains failed Forge pre-negotiation login attempts;
a subsequent [login-order correction](forge-login-order/README.md) orders the
initial PLAY transition behind queued LOGIN writes. Historical evidence keeps
its original artifact attribution.
This record does not authorize a release or a merge into main.
