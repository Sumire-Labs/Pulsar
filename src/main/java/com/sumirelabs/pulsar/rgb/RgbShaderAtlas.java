/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.util.WorldUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.BiConsumer;

/** Original sparse shader light field. Only immutable publications cross into GPU uploads. */
final class RgbShaderAtlas {
    private static final int WIDTH = RgbShaderLightData.DATA_WIDTH, LIMIT = 4096;
    private static final long UPLOAD_BUDGET_NS = 2_000_000;
    private final IntBuffer cells = BufferUtils.createIntBuffer(4096);
    private final Map<Long, ChunkView> chunks = new HashMap<>();
    private final BiConsumer<Long, RgbClient.View> visitor = this::visit;
    private final int[] free = new int[LIMIT];
    private int freeCount, nextSlot, capacity, dataTexture, indexTexture;
    private int[] index;
    private IntBuffer indexUpload;
    private RgbShaderLightData.Layout layout;
    private World world;
    private int indexHeight, centerX, centerZ, radius;
    private long frame, generation, deadline;
    private boolean indexDirty, uploadOpen, warnedLimit;
    private int oldTexture, oldUnpack, oldAlignment, oldRowLength, oldSkipRows, oldSkipPixels, oldSwapBytes;
    private long uploadedSections;

    private static final class ChunkView {
        final Chunk chunk;
        final int[] slots;
        final long[] generations;
        final RgbSnapshot.ShaderSection[] block, sky;
        final byte[][] fallbackSky;
        long seen;
        ChunkView(Chunk chunk, int count) {
            this.chunk = chunk; slots = new int[count]; Arrays.fill(slots, -1);
            generations = new long[count]; block = new RgbSnapshot.ShaderSection[count]; sky = new RgbSnapshot.ShaderSection[count];
            fallbackSky = new byte[count][];
        }
    }
    int side() { return layout == null ? 1 : layout.side(); }
    int minSection() { return layout == null ? 0 : layout.minSection(); }
    int sectionCount() { return layout == null ? 0 : layout.sectionCount(); }
    boolean ready() { return world != null && layout != null && !chunks.isEmpty(); }
    long uploadedSections() { return uploadedSections; }

    int dataTexture() { fallback(); return dataTexture; }
    int indexTexture() { fallback(); return indexTexture; }
    private void fallback() {
        if (dataTexture != 0) return;
        beginUpload();
        try {
            dataTexture = GL11.glGenTextures(); indexTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTexture); parameters();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, 1, 1, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer)null);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, indexTexture); parameters();
            var missing = BufferUtils.createIntBuffer(4); missing.put(-1).put(0).put(0).put(0).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32I, 1, 1, 0, GL30.GL_RGBA_INTEGER, GL11.GL_INT, missing);
        } finally { endUpload(); }
    }
    private static void parameters() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
    }
    void clear() {
        world = null; layout = null; chunks.clear(); index = null; indexUpload = null;
        nextSlot = freeCount = capacity = 0; warnedLimit = false;
        // Texture names stay stable across worlds/reloads. No sampler keeps a deleted name.
    }
    void tick(World current) {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) throw new IllegalStateException("Shader uploads off render thread");
        if (current == null) { clear(); return; }
        try {
            int distance = Math.max(2, Math.min(32, Minecraft.getMinecraft().gameSettings.renderDistanceChunks));
            int side = 1; while (side < distance * 2 + 3) side <<= 1;
            var height = WorldUtil.getHeightContext(current);
            if (world != current || layout == null || layout.side() != side) {
                clear(); fallback();
                if (GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE) < WIDTH) return;
                world = current; radius = distance + 1;
                layout = new RgbShaderLightData.Layout(side, height.getMinLightSection(), height.getTotalLightSections());
                indexHeight = (layout.entries() + RgbShaderLightData.INDEX_WIDTH - 1) / RgbShaderLightData.INDEX_WIDTH;
                if (indexHeight > GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)) { clear(); return; }
                index = new int[indexHeight * RgbShaderLightData.INDEX_WIDTH * 4];
                for (int i = 0; i < index.length; i += 4) index[i] = -1;
                indexUpload = BufferUtils.createIntBuffer(index.length);
                beginUpload(); grow(64); uploadIndex(true);
            }
            var camera = Minecraft.getMinecraft().getRenderViewEntity();
            if (camera == null) return;
            centerX = (int)Math.floor(camera.posX) >> 4; centerZ = (int)Math.floor(camera.posZ) >> 4;
            ++frame; deadline = System.nanoTime() + UPLOAD_BUDGET_NS;
            for (var it = chunks.values().iterator(); it.hasNext();) {
                var view = it.next(); var chunk = view.chunk;
                if (Math.abs(chunk.x - centerX) > radius || Math.abs(chunk.z - centerZ) > radius
                        || current.getChunkProvider().getLoadedChunk(chunk.x, chunk.z) != chunk) { evict(view); it.remove(); }
            }
            RgbClient.visitShaderViews(visitor);
            for (var it = chunks.values().iterator(); it.hasNext();) {
                var view = it.next(); if (view.seen != frame) { evict(view); it.remove(); }
            }
            if (indexDirty) { beginUpload(); uploadIndex(false); }
        } finally { endUpload(); }
    }
    private void visit(Long key, RgbClient.View source) {
        Chunk chunk = source.chunk();
        if (chunk.getWorld() != world || source.rgb() == null
                || Math.abs(chunk.x - centerX) > radius || Math.abs(chunk.z - centerZ) > radius) return;
        ChunkView view = chunks.get(key);
        if (view != null && view.chunk != chunk) { evict(view); chunks.remove(key); view = null; }
        if (view == null) { view = new ChunkView(chunk, layout.sectionCount()); chunks.put(key, view); }
        view.seen = frame;
        for (int s = 0; s < layout.sectionCount(); s++) {
            if (System.nanoTime() >= deadline) return;
            int y = layout.minSection() + s;
            var block = source.rgb().shaderSection(y, view.block[s]);
            RgbSnapshot.ShaderSection sky;
            if (source.skyRgb() != null) { sky = source.skyRgb().shaderSection(y, view.sky[s]); view.fallbackSky[s] = null; }
            else {
                int at = y - source.rgb().minSection;
                sky = view.sky[s];
                byte[] bytes = at >= 0 && at < source.sky().length ? source.sky()[at] : null;
                if (sky == null || bytes != view.fallbackSky[s]) sky = RgbSnapshot.ShaderSection.white(bytes);
                view.fallbackSky[s] = bytes;
            }
            if (block == view.block[s] && sky == view.sky[s] && view.generations[s] == generation) continue;
            int meta = layout.index(chunk.x, y, chunk.z) * 4;
            int bc = block.constant(), sc = sky.constant();
            if (bc >= 0 && sc >= 0) {
                releaseSlot(view, s);
                index[meta] = RgbShaderLightData.CONSTANT; index[meta + 1] = RgbShaderLightData.fromCells(bc, sc);
            } else {
                int slot = view.slots[s];
                if (slot < 0) {
                    if (freeCount > 0) slot = free[--freeCount];
                    else if (nextSlot < LIMIT) slot = nextSlot++;
                    else {
                        if (!warnedLimit) { warnedLimit = true; com.sumirelabs.pulsar.Pulsar.LOGGER.warn("RGB shader section budget reached; unuploaded sections retain shaderpack lighting"); }
                        continue;
                    }
                    view.slots[s] = slot;
                }
                beginUpload(); if (slot >= capacity) grow(Math.min(LIMIT, capacity * 2));
                cells.clear(); for (int i = 0; i < 4096; i++) cells.put(RgbShaderLightData.fromCells(block.cell(i), sky.cell(i))); cells.flip();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTexture);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, slot, WIDTH, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, cells);
                index[meta] = slot; index[meta + 1] = 0; ++uploadedSections;
            }
            index[meta + 2] = chunk.x; index[meta + 3] = chunk.z; indexDirty = true;
            view.block[s] = block; view.sky[s] = sky; view.generations[s] = generation;
        }
    }
    private void releaseSlot(ChunkView view, int section) {
        if (view.slots[section] >= 0) { free[freeCount++] = view.slots[section]; view.slots[section] = -1; }
    }
    private void evict(ChunkView view) {
        for (int s = 0; s < layout.sectionCount(); s++) {
            int meta = layout.index(view.chunk.x, layout.minSection() + s, view.chunk.z) * 4;
            if (index[meta + 2] == view.chunk.x && index[meta + 3] == view.chunk.z) { index[meta] = -1; indexDirty = true; }
            releaseSlot(view, s);
        }
    }
    private void grow(int size) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, dataTexture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, WIDTH, size, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (IntBuffer)null);
        capacity = size; ++generation;
        // New storage is undefined; old pointers must fall back until reuploaded.
        for (int i = 0; i < index.length; i += 4) if (index[i] >= 0) index[i] = -1;
        indexDirty = true;
    }
    private void uploadIndex(boolean allocate) {
        indexUpload.clear(); indexUpload.put(index).flip(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, indexTexture);
        if (allocate) GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32I, RgbShaderLightData.INDEX_WIDTH, indexHeight, 0, GL30.GL_RGBA_INTEGER, GL11.GL_INT, indexUpload);
        else GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, RgbShaderLightData.INDEX_WIDTH, indexHeight, GL30.GL_RGBA_INTEGER, GL11.GL_INT, indexUpload);
        indexDirty = false;
    }
    private void beginUpload() {
        if (uploadOpen) return; uploadOpen = true;
        oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        oldUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        oldSwapBytes = GL11.glGetInteger(GL11.GL_UNPACK_SWAP_BYTES);
        GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 0);
        oldAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT); oldRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        oldSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS); oldSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4); GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
    }
    private void endUpload() {
        if (!uploadOpen) return;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture); GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, oldUnpack);
        GL11.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, oldSwapBytes);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, oldAlignment); GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, oldRowLength);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, oldSkipRows); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, oldSkipPixels);
        uploadOpen = false;
    }
}
