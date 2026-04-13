package com.sumirelabs.pulsar.light;

import net.minecraft.world.chunk.NibbleArray;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Single Writer Multi Reader nibble array. Port of Starlight's design,
 * inherited from SuperNova (1.7.10) and adapted to 1.12.2 vanilla
 * {@link NibbleArray} (which uses {@link NibbleArray#getData()} instead of a
 * public {@code data} field).
 *
 * <p>State diagram:
 * <ul>
 *     <li>{@code NULL}: section does not exist, reads as 0, never written.</li>
 *     <li>{@code UNINIT}: section exists conceptually, all-zero, backing array
 *         not allocated.</li>
 *     <li>{@code INIT}: section has light data.</li>
 *     <li>{@code HIDDEN}: initialised but {@link #toVanillaNibble()} should
 *         appear null (used to keep persisted RGB-style data hidden from
 *         vanilla code paths). Pulsar's scalar release does not currently use
 *         this state but it is preserved for forward compatibility with future
 *         RGB modes.</li>
 * </ul>
 */
public final class SWMRNibbleArray {

    public static final int INIT_STATE_NULL = 0;
    public static final int INIT_STATE_UNINIT = 1;
    public static final int INIT_STATE_INIT = 2;
    public static final int INIT_STATE_HIDDEN = 3;

    public static final int ARRAY_SIZE = 16 * 16 * 16 / 2; // 2048 bytes

    static final ThreadLocal<ArrayDeque<byte[]>> WORKING_BYTES_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static byte[] allocateBytes() {
        final byte[] inPool = WORKING_BYTES_POOL.get().pollFirst();
        if (inPool != null) {
            return inPool;
        }
        return new byte[ARRAY_SIZE];
    }

    private static void freeBytes(final byte[] bytes) {
        WORKING_BYTES_POOL.get().addFirst(bytes);
    }

    public static SWMRNibbleArray fromVanilla(final NibbleArray nibble) {
        if (nibble == null) {
            return new SWMRNibbleArray(null, true);
        }
        final byte[] data = nibble.getData();
        if (data == null) {
            return new SWMRNibbleArray();
        }
        return new SWMRNibbleArray(data.clone());
    }

    private int stateUpdating;
    private volatile int stateVisible;

    private byte[] storageUpdating;
    private boolean updatingDirty;
    private volatile byte[] storageVisible;

    // NOTE: dirty byte range tracking removed in dev.7 — the fields were
    // never consumed outside SWMRNibbleArray (ChunkLightHelper.sync* always
    // does a full System.arraycopy). Saves 8 bytes/object × 36 nibbles/chunk.
    // Restore if a partial-sync optimisation is implemented later.

    // Fast section state flags
    private boolean fullFlag;
    private boolean zeroFlag;

    // Visible-side copies -- published by updateVisible(), read by render thread
    private volatile boolean fullFlagVisible;
    private volatile boolean zeroFlagVisible;

    public SWMRNibbleArray() {
        this(null, false);
    }

    public SWMRNibbleArray(final byte[] bytes) {
        this(bytes, false);
    }

    public SWMRNibbleArray(final byte[] bytes, final boolean isNullNibble) {
        if (bytes != null && bytes.length != ARRAY_SIZE) {
            throw new IllegalArgumentException("Data of wrong length: " + bytes.length);
        }
        this.stateVisible = this.stateUpdating = bytes == null
                ? (isNullNibble ? INIT_STATE_NULL : INIT_STATE_UNINIT)
                : INIT_STATE_INIT;
        this.storageUpdating = this.storageVisible = bytes;
        this.zeroFlag = bytes == null && !isNullNibble;
        this.fullFlagVisible = this.fullFlag;
        this.zeroFlagVisible = this.zeroFlag;
    }

    public SWMRNibbleArray(final byte[] bytes, final int state) {
        if (bytes != null && bytes.length != ARRAY_SIZE) {
            throw new IllegalArgumentException("Data of wrong length: " + bytes.length);
        }
        if (bytes == null && (state == INIT_STATE_INIT || state == INIT_STATE_HIDDEN)) {
            throw new IllegalArgumentException("Data cannot be null and have state be initialised");
        }
        this.stateUpdating = this.stateVisible = state;
        this.storageUpdating = this.storageVisible = bytes;
        this.zeroFlag = bytes == null && state == INIT_STATE_UNINIT;
        this.fullFlagVisible = this.fullFlag;
        this.zeroFlagVisible = this.zeroFlag;
    }

    public SaveState getSaveState() {
        synchronized (this) {
            final int state = this.stateVisible;
            final byte[] data = this.storageVisible;
            if (state == INIT_STATE_NULL) {
                return null;
            }
            if (state == INIT_STATE_UNINIT) {
                return new SaveState(null, state);
            }
            final boolean zero = isAllZero(data);
            if (zero) {
                return state == INIT_STATE_INIT ? new SaveState(null, INIT_STATE_UNINIT) : null;
            } else {
                return new SaveState(data.clone(), state);
            }
        }
    }

    private static boolean isAllZero(final byte[] data) {
        for (int i = 0; i < (ARRAY_SIZE >>> 4); ++i) {
            byte whole = data[i << 4];
            for (int k = 1; k < (1 << 4); ++k) {
                whole |= data[(i << 4) | k];
            }
            if (whole != 0) {
                return false;
            }
        }
        return true;
    }

    public void extrudeLower(final SWMRNibbleArray other) {
        if (other.stateUpdating == INIT_STATE_NULL) {
            throw new IllegalArgumentException();
        }
        if (other.storageUpdating == null) {
            this.setUninitialised();
            return;
        }

        final byte[] src = other.storageUpdating;
        final byte[] into;
        if (!this.updatingDirty) {
            if (this.storageUpdating != null) {
                into = this.storageUpdating = allocateBytes();
            } else {
                this.storageUpdating = into = allocateBytes();
                this.stateUpdating = INIT_STATE_INIT;
            }
            this.updatingDirty = true;
        } else {
            into = this.storageUpdating;
        }

        final int start = 0;
        final int end = (15 | (15 << 4)) >>> 1;

        /* x | (z << 4) | (y << 8) */
        for (int y = 0; y <= 15; ++y) {
            System.arraycopy(src, start, into, y << (8 - 1), end - start + 1);
        }
        this.fullFlag = other.fullFlag;
        this.zeroFlag = other.zeroFlag;
    }

    public void setFull() {
        if (this.stateUpdating != INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
        }
        Arrays.fill(this.storageUpdating == null || !this.updatingDirty
                ? this.storageUpdating = allocateBytes()
                : this.storageUpdating, (byte) -1);
        this.updatingDirty = true;
        this.fullFlag = true;
        this.zeroFlag = false;
    }

    public void setZero() {
        if (this.stateUpdating != INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
        }
        Arrays.fill(this.storageUpdating == null || !this.updatingDirty
                ? this.storageUpdating = allocateBytes()
                : this.storageUpdating, (byte) 0);
        this.updatingDirty = true;
        this.fullFlag = false;
        this.zeroFlag = true;
    }

    public void setNonNull() {
        if (this.stateUpdating == INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
            return;
        }
        if (this.stateUpdating != INIT_STATE_NULL) {
            return;
        }
        this.stateUpdating = INIT_STATE_UNINIT;
    }

    public void setNull() {
        this.stateUpdating = INIT_STATE_NULL;
        if (this.updatingDirty && this.storageUpdating != null) {
            freeBytes(this.storageUpdating);
        }
        this.storageUpdating = null;
        this.updatingDirty = false;
        this.fullFlag = false;
        this.zeroFlag = false;
    }

    public void setUninitialised() {
        this.stateUpdating = INIT_STATE_UNINIT;
        if (this.storageUpdating != null && this.updatingDirty) {
            freeBytes(this.storageUpdating);
        }
        this.storageUpdating = null;
        this.updatingDirty = false;
        this.fullFlag = false;
        this.zeroFlag = true;
    }

    public void setHidden() {
        if (this.stateUpdating == INIT_STATE_HIDDEN) {
            return;
        }
        if (this.stateUpdating != INIT_STATE_INIT) {
            this.setNull();
        } else {
            this.stateUpdating = INIT_STATE_HIDDEN;
        }
    }

    public boolean isDirty() {
        return this.stateUpdating != this.stateVisible || this.updatingDirty;
    }

    public boolean isNullNibbleUpdating() {
        return this.stateUpdating == INIT_STATE_NULL;
    }

    public boolean isNullNibbleVisible() {
        return this.stateVisible == INIT_STATE_NULL;
    }

    public boolean isUninitialisedUpdating() {
        return this.stateUpdating == INIT_STATE_UNINIT;
    }

    public boolean isUninitialisedVisible() {
        return this.stateVisible == INIT_STATE_UNINIT;
    }

    public boolean isInitialisedUpdating() {
        return this.stateUpdating == INIT_STATE_INIT;
    }

    public boolean isInitialisedVisible() {
        return this.stateVisible == INIT_STATE_INIT;
    }

    public boolean isHiddenUpdating() {
        return this.stateUpdating == INIT_STATE_HIDDEN;
    }

    public boolean isHiddenVisible() {
        return this.stateVisible == INIT_STATE_HIDDEN;
    }

    public boolean isFullUpdating() {
        return this.fullFlag;
    }

    public boolean isZeroUpdating() {
        return this.zeroFlag;
    }

    public boolean isFullVisible() {
        return this.fullFlagVisible;
    }

    public boolean isZeroVisible() {
        return this.zeroFlagVisible;
    }

    private void swapUpdatingAndMarkDirty() {
        if (this.updatingDirty) {
            return;
        }
        if (this.storageUpdating == null) {
            this.storageUpdating = allocateBytes();
            Arrays.fill(this.storageUpdating, (byte) 0);
        } else {
            System.arraycopy(this.storageUpdating, 0,
                    this.storageUpdating = allocateBytes(), 0, ARRAY_SIZE);
        }
        if (this.stateUpdating != INIT_STATE_HIDDEN) {
            this.stateUpdating = INIT_STATE_INIT;
        }
        this.updatingDirty = true;
        this.fullFlag = false;
        this.zeroFlag = false;
    }

    public boolean updateVisible() {
        if (!this.isDirty()) {
            return false;
        }
        synchronized (this) {
            if (this.stateUpdating == INIT_STATE_NULL || this.stateUpdating == INIT_STATE_UNINIT) {
                this.storageVisible = null;
            } else {
                if (this.storageVisible == null) {
                    this.storageVisible = this.storageUpdating.clone();
                } else {
                    if (this.storageUpdating != this.storageVisible) {
                        System.arraycopy(this.storageUpdating, 0, this.storageVisible, 0, ARRAY_SIZE);
                    }
                }
                if (this.storageUpdating != this.storageVisible) {
                    freeBytes(this.storageUpdating);
                }
                this.storageUpdating = this.storageVisible;
            }
            this.updatingDirty = false;
            this.stateVisible = this.stateUpdating;
            this.fullFlagVisible = this.fullFlag;
            this.zeroFlagVisible = this.zeroFlag;
        }
        return true;
    }

    /**
     * Convert the visible state into a fresh vanilla {@link NibbleArray}.
     * Used for tooling / debug paths only — Pulsar's normal sync path writes
     * directly into the existing {@code ExtendedBlockStorage} nibbles.
     */
    public NibbleArray toVanillaNibble() {
        synchronized (this) {
            switch (this.stateVisible) {
                case INIT_STATE_HIDDEN:
                case INIT_STATE_NULL:
                    return null;
                case INIT_STATE_UNINIT:
                    return new NibbleArray();
                case INIT_STATE_INIT:
                    return new NibbleArray(this.storageVisible.clone());
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * @return the raw backing byte array for the visible state, or {@code null}
     *         if the state is null or uninitialised. The caller must not
     *         mutate the returned array.
     */
    public byte[] getVisibleData() {
        return this.storageVisible;
    }

    public int getUpdating(final int x, final int y, final int z) {
        return this.getUpdating((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    public int getUpdating(final int index) {
        final byte[] bytes = this.storageUpdating;
        if (bytes == null) {
            return 0;
        }
        final byte value = bytes[index >>> 1];
        return ((value >>> ((index & 1) << 2)) & 0xF);
    }

    public int getVisible(final int x, final int y, final int z) {
        return this.getVisible((x & 15) | ((z & 15) << 4) | ((y & 15) << 8));
    }

    public int getVisible(final int index) {
        final byte[] visibleBytes = this.storageVisible;
        if (visibleBytes == null) {
            return 0;
        }
        final byte value = visibleBytes[index >>> 1];
        return ((value >>> ((index & 1) << 2)) & 0xF);
    }

    public void set(final int x, final int y, final int z, final int value) {
        this.set((x & 15) | ((z & 15) << 4) | ((y & 15) << 8), value);
    }

    public byte[] getUpdatingStorage() {
        return this.storageUpdating;
    }

    public void set(final int index, final int value) {
        if (this.fullFlag | this.zeroFlag) {
            this.fullFlag = false;
            this.zeroFlag = false;
        }
        if (!this.updatingDirty) {
            this.swapUpdatingAndMarkDirty();
        }
        final int shift = (index & 1) << 2;
        final int i = index >>> 1;
        this.storageUpdating[i] = (byte) ((this.storageUpdating[i] & (0xF0 >>> shift)) | (value << shift));
    }

    /**
     * Replace the entire backing array in storageUpdating with data from src.
     * Marks dirty, clears full/zero flags. Caller is responsible for correct
     * nibble packing.
     */
    public void bulkWriteAll(final byte[] src) {
        if (!this.updatingDirty) {
            this.swapUpdatingAndMarkDirty();
        }
        System.arraycopy(src, 0, this.storageUpdating, 0, ARRAY_SIZE);
        this.fullFlag = false;
        this.zeroFlag = false;
    }

    /**
     * Prepare for a full bulk write by returning a writable {@code byte[]}
     * without copying existing data. The caller will overwrite every byte.
     * Avoids the allocation + copy overhead of {@link #bulkWriteAll}.
     */
    public byte[] prepareForBulkWrite() {
        if (!this.updatingDirty) {
            this.storageUpdating = allocateBytes();
            if (this.stateUpdating != INIT_STATE_HIDDEN) {
                this.stateUpdating = INIT_STATE_INIT;
            }
            this.updatingDirty = true;
        }
        this.fullFlag = false;
        this.zeroFlag = false;
        return this.storageUpdating;
    }

    /**
     * Mark the entire section dirty without writing data. Used after external
     * code has written directly to the array returned by
     * {@link #getUpdatingStorage()}.
     */
    public void markDirtyAll() {
        if (!this.updatingDirty) {
            this.swapUpdatingAndMarkDirty();
        }
        this.fullFlag = false;
        this.zeroFlag = false;
    }

    public static final class SaveState {

        public final byte[] data;
        public final int state;

        public SaveState(final byte[] data, final int state) {
            this.data = data;
            this.state = state;
        }
    }
}
