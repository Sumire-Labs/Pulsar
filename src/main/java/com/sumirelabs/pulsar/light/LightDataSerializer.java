package com.sumirelabs.pulsar.light;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.util.WorldHeightContext;
import com.sumirelabs.pulsar.util.WorldUtil;
import com.sumirelabs.pulsar.world.PulsarWorld;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Persists Pulsar's SWMR light data in the chunk NBT so that chunks loaded
 * from disk do not need a full relight. Port of Starlight's
 * {@code SaveUtil.saveLightHook}/{@code loadLightHook} to the 1.12.2 Forge
 * {@link ChunkDataEvent} pair.
 *
 * <p>Save: only chunks whose initial propagation and edge reconciliation have
 * completed ({@code pulsar$isLightReady}) get the tag. A chunk saved
 * mid-relight simply carries no tag and is relit on next load — same fail-safe
 * Starlight uses.
 *
 * <p>Load: fires during chunk deserialisation, before {@code Chunk.onLoad}.
 * On a valid restore {@code pulsar$setSavedLightValid(true)} is set and
 * {@code MixinChunk.pulsar$onLoad} skips the initial-light queue entirely,
 * scheduling only a cheap nibble/emptiness-map init
 * ({@link WorldLightManager#queueChunkLoadInit}).
 */
public final class LightDataSerializer {

    /**
     * Bump when the on-disk layout or BFS semantics change incompatibly.
     */
    // v9: invalidates v8 caches computed without Forge's contextual block
    // opacity/emission values.
    // v8: invalidates v7 caches that may have been written with vanilla-only
    // bounds when the published Depths Update a12 bridge failed to resolve.
    // v7: persists full per-world height ranges and invalidates caches that
    // omitted Depths Update's negative/upper sections.
    // v6: invalidated light computed before the 2026-07-26 correctness batch
    // (UNINIT-as-15 sync, missing extrude, decrease re-seed/continuation
    // fixes) — old data relights once on load.
    public static final int LIGHT_VERSION = 9;

    private static final String TAG_ROOT = "PulsarLight";
    private static final String TAG_VERSION = "version";
    private static final String TAG_SECTIONS = "sections";
    private static final String TAG_Y = "y";
    private static final String TAG_BLOCK_STATE = "bs";
    private static final String TAG_BLOCK_DATA = "bd";
    private static final String TAG_SKY_STATE = "ss";
    private static final String TAG_SKY_DATA = "sd";

    @SubscribeEvent
    public void onChunkSave(final ChunkDataEvent.Save event) {
        try {
            this.saveLight(event);
        } catch (final Throwable t) {
            // Failing to attach the tag is not fatal: the chunk will simply be
            // relit on next load.
            Pulsar.LOGGER.warn("Failed to save Pulsar light data for chunk ({}, {})",
                    event.getChunk().x, event.getChunk().z, t);
        }
    }

    @SubscribeEvent
    public void onChunkLoad(final ChunkDataEvent.Load event) {
        try {
            this.loadLight(event);
        } catch (final Throwable t) {
            // Invalid or corrupt data: leave savedLightValid unset so the
            // chunk goes through the normal full-relight path.
            Pulsar.LOGGER.warn("Failed to load Pulsar light data for chunk ({}, {}) - chunk will be relit",
                    event.getChunk().x, event.getChunk().z, t);
        }
    }

    private void saveLight(final ChunkDataEvent.Save event) {
        final Chunk chunk = event.getChunk();
        final PulsarChunk pc = (PulsarChunk) chunk;
        if (!pc.pulsar$isLightReady()) {
            return;
        }

        final SWMRNibbleArray[] blockNibbles = pc.pulsar$getBlockNibbles();
        final SWMRNibbleArray[] skyNibbles = pc.pulsar$getSkyNibbles();
        if (blockNibbles == null || skyNibbles == null) {
            return;
        }

        // Queued initial-light/block-change work means the SWMR values are
        // still in flux (e.g. a lava pocket just turned to stone but its
        // light removal hasn't run). Persisting them as valid would freeze
        // phantom light into the save — skip the tag and let the chunk
        // relight on next load instead.
        final WorldLightManager mgr = ((PulsarWorld) chunk.getWorld()).pulsar$getLightManager();
        if (mgr != null && mgr.hasPendingLightWork(chunk.x, chunk.z)) {
            return;
        }
        final boolean hasSky = chunk.getWorld().provider.hasSkyLight();
        final WorldHeightContext heightContext = WorldUtil.getHeightContext(chunk.getWorld());
        final int minLightSection = heightContext.getMinLightSection();

        final NBTTagList sections = new NBTTagList();
        for (int i = 0, len = blockNibbles.length; i < len; ++i) {
            // Null elements are legal: the sky engine's rewriteNibbleCacheForSkylight
            // replaces NULL-state nibbles with java nulls before the arrays are
            // published to the chunk. Treat them as NULL (nothing to save).
            final SWMRNibbleArray blockNib = blockNibbles[i];
            final SWMRNibbleArray skyNib = hasSky ? skyNibbles[i] : null;
            final SWMRNibbleArray.SaveState blockState = blockNib == null ? null : blockNib.getSaveState();
            final SWMRNibbleArray.SaveState skyState = skyNib == null ? null : skyNib.getSaveState();
            if (blockState == null && skyState == null) {
                continue;
            }
            final NBTTagCompound section = new NBTTagCompound();
            section.setInteger(TAG_Y, i + minLightSection);
            if (blockState != null) {
                section.setByte(TAG_BLOCK_STATE, (byte) blockState.state);
                if (blockState.data != null) {
                    section.setByteArray(TAG_BLOCK_DATA, blockState.data);
                }
            }
            if (skyState != null) {
                section.setByte(TAG_SKY_STATE, (byte) skyState.state);
                if (skyState.data != null) {
                    section.setByteArray(TAG_SKY_DATA, skyState.data);
                }
            }
            sections.appendTag(section);
        }

        final NBTTagCompound root = new NBTTagCompound();
        root.setInteger(TAG_VERSION, LIGHT_VERSION);
        root.setTag(TAG_SECTIONS, sections);
        event.getData().setTag(TAG_ROOT, root);
    }

    private void loadLight(final ChunkDataEvent.Load event) {
        final NBTTagCompound data = event.getData();
        if (!data.hasKey(TAG_ROOT, Constants.NBT.TAG_COMPOUND)) {
            return;
        }
        final NBTTagCompound root = data.getCompoundTag(TAG_ROOT);
        if (root.getInteger(TAG_VERSION) != LIGHT_VERSION) {
            return;
        }

        final WorldHeightContext heightContext = WorldUtil.getHeightContext(event.getChunk().getWorld());
        final int minLightSection = heightContext.getMinLightSection();
        final int totalLightSections = heightContext.getTotalLightSections();

        final SWMRNibbleArray[] blockNibbles = new SWMRNibbleArray[totalLightSections];
        final SWMRNibbleArray[] skyNibbles = new SWMRNibbleArray[totalLightSections];
        for (int i = 0; i < totalLightSections; ++i) {
            blockNibbles[i] = new SWMRNibbleArray(null, true);
            skyNibbles[i] = new SWMRNibbleArray(null, true);
        }

        final NBTTagList sections = root.getTagList(TAG_SECTIONS, Constants.NBT.TAG_COMPOUND);
        for (int i = 0, len = sections.tagCount(); i < len; ++i) {
            final NBTTagCompound section = sections.getCompoundTagAt(i);
            final int sectionY = section.getInteger(TAG_Y);
            final int index = sectionY - minLightSection;
            if (index < 0 || index >= totalLightSections) {
                throw new IllegalStateException("Light section index out of range: " + sectionY);
            }
            if (section.hasKey(TAG_BLOCK_STATE, Constants.NBT.TAG_BYTE)) {
                blockNibbles[index] = restoreNibble(section, TAG_BLOCK_STATE, TAG_BLOCK_DATA);
            }
            if (section.hasKey(TAG_SKY_STATE, Constants.NBT.TAG_BYTE)) {
                skyNibbles[index] = restoreNibble(section, TAG_SKY_STATE, TAG_SKY_DATA);
            }
        }

        final PulsarChunk pc = (PulsarChunk) event.getChunk();
        pc.pulsar$setBlockNibbles(blockNibbles);
        pc.pulsar$setSkyNibbles(skyNibbles);
        pc.pulsar$setSavedLightValid(true);
    }

    private static SWMRNibbleArray restoreNibble(final NBTTagCompound section, final String stateTag, final String dataTag) {
        final int state = section.getByte(stateTag);
        final byte[] raw = section.hasKey(dataTag, Constants.NBT.TAG_BYTE_ARRAY) ? section.getByteArray(dataTag) : null;
        if (raw != null && raw.length != SWMRNibbleArray.ARRAY_SIZE) {
            throw new IllegalStateException("Light nibble of wrong length: " + raw.length);
        }
        // clone: the NBT object owns the parsed array
        return new SWMRNibbleArray(raw == null ? null : raw.clone(), state);
    }
}
