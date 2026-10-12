from pathlib import Path
R=Path(__file__).resolve().parents[1]
p=R/'repo/src/main/java/com/sumirelabs/pulsar/light/engine/LightEngineCache.java'
t=(R/'sources/LightEngineCache.java').read_text(encoding='utf-8')
t=t.replace('    private final boolean plainAir;','    private final boolean plainAir;\n    private final boolean cacheEmptyAir;')
needle='        this.heightContext = heightContext;';assert t.count(needle)==1
t=t.replace(needle,'''        this.cacheEmptyAir = this.plainAir && !FluidLightBridge.LOADED
                && Blocks.AIR.getBlockState().getValidStates().size() == 1
                && this.airState == Blocks.AIR.getBlockState().getBaseState();
'''+needle)
needle='this.chunkSectionIndexOffset] = section;';assert t.count(needle)==2
t=t.replace(needle,'this.chunkSectionIndexOffset] = normalizeBlockSection(section, this.cacheEmptyAir);')
needle='    protected final SWMRNibbleArray getNibbleFromCache('
assert t.count(needle)==1
t=t.replace(needle,'''    /** Only the block-read view is normalized; live sections and their light arrays stay intact. */
    static ExtendedBlockStorage normalizeBlockSection(final ExtendedBlockStorage section, final boolean allowed) {
        return allowed && section != null && section.getClass() == ExtendedBlockStorage.class && section.isEmpty()
                ? null : section;
    }

'''+needle)
p.write_bytes(t.encode())
print('Prepared guarded normalization of canonical empty block sections')
