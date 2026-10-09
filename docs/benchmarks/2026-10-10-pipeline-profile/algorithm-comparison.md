# Moonrise, original Starlight and Starlight-1.12

The lighting implementation in Moonrise belongs to the Starlight lineage. It is not an unrelated replacement lighting algorithm. Its basic method remains queue-based breadth-first light propagation, separate decrease/restoration and increase passes, packed queue entries containing coordinates/level/directions, column-aware direct skylight processing, and SWMR nibble publication.

That does not imply identical source, identical operation counts, identical scheduling, or identical speed. Moonrise also contains chunk-system and other server optimizations outside lighting.

## Sources inspected

- [Original Starlight Fabric snapshot cca03d6](https://github.com/PaperMC/Starlight/tree/cca03d62da48e876ac79196bad16864e8a96bbeb/src/main/java/ca/spottedleaf/starlight/common/light)
- [Moonrise Minecraft1.21.1 branch snapshot 2eae1b1](https://github.com/Tuinity/Moonrise/tree/2eae1b1f7318907cd9b6552e59156f6dc619b6cc/src/main/java/ca/spottedleaf/moonrise/patches/starlight/light)
- [Measured Starlight-1.12 snapshot 0a7d94e](https://github.com/geyesborg/starlight-1.12/blob/0a7d94e7e3fd7333f0e31e896e8a5ff57fb57e4e/src/main/java/com/github/starlight/light/StarLightEngine.java)
- [Moonrise project scope](https://github.com/Tuinity/Moonrise)

The 1.12 port's core source header explicitly identifies Moonrise as its source. The inspected header does not pin the precise Moonrise source commit. The Moonrise1.21.1 snapshot above is an explicit comparison sample, not a claim that it is the port's exact base or the newest Moonrise release.

## Concrete differences in the inspected upstream snapshots

1. The original base propagation loops have a cached-opacity fast path and a separate context-sensitive branch. The compared Moonrise loops consolidate that path and perform contextual opacity/shape checks. This is a change in the implementation of propagation, even though the increase/decrease propagation model remains the same.
2. Block emission in Moonrise passes through platform hooks with world and position context. The original code at the compared sites reads emission directly from the state. Platform-specific behavior and work performed per check can therefore differ.
3. Chunk/state interfaces, status checks, source enumeration and empty-section handling have platform/version adaptations. These affect runtime work and compatibility independently of the broad lighting algorithm.
4. After normalizing only the package-prefix difference, the two inspected SWMRNibbleArray source files are identical. The three inspected engine files are not identical under that same normalization. This is deliberately a narrow source comparison, not a full equivalence proof.
5. The 1.12 port adapts the engine to its LightWorld/LightChunk wrappers and Minecraft1.12 storage and rules. A modern Minecraft implementation cannot simply be inserted unchanged into Pulsar's Cleanroom1.12 integration.

Therefore, “Moonrise-based” by itself does not explain the roughly7µs placement difference measured previously, and does not establish that replacing Pulsar's propagation code would remove it. The new pipeline diagnostics measure time inside Pulsar; a causal cross-engine attribution would additionally require comparable instrumentation in the measured Starlight port and controls for its instrumentation overhead.

Reference Java files were read only in the external research directory. No reference-engine GPL Java is included in Pulsar or in the diagnostic publication. Pulsar's license is unchanged. No optimization or replacement is performed in this task.
