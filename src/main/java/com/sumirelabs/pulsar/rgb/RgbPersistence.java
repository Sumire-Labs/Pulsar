/*
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import com.sumirelabs.pulsar.util.WorldHeightContext;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

/** RGB persistence is independent of the canonical light format. */
public final class RgbPersistence {

    private static final int ARRAY_PLANES = 2;
    private static final int COMPACT_PLANES = 3;

    private RgbPersistence() {}
    public static void save(NBTTagCompound root, RgbSnapshot snapshot) {
        save(root, snapshot, false);
    }
    public static void save(NBTTagCompound root, RgbSnapshot snapshot, boolean sky) {
        root.setInteger("rgbVersion", COMPACT_PLANES);
        root.setString("rgbProfile", com.sumirelabs.pulsar.api.BlockLightColors.profile());
        root.setInteger("rgbMin", snapshot.minSection);
        root.setInteger("rgbCount", snapshot.sectionCount());
        NBTTagCompound data = new NBTTagCompound();
        for (int c = 0; c < 3; c++)
            for (int s = 0; s < snapshot.sectionCount(); s++) {
                byte[] plane = snapshot.ownedSection(c, s);
                if (plane == null)
                    continue;
                String key = c + ":" + s;
                int level = RgbSnapshot.constantLevel(plane);
                if (level >= 0)
                    data.setByte(key, (byte) level);
                else if (c > 0 && plane == snapshot.ownedSection(0, s))
                    data.setByte(key, (byte) -1);
                else if (c > 1 && plane == snapshot.ownedSection(1, s))
                    data.setByte(key, (byte) -2);
                else
                    data.setByteArray(key, plane.clone());
            }
        root.setTag(sky ? "rgbSky" : "rgb", data);
    }
    public static RgbStorage load(NBTTagCompound root, WorldHeightContext height) {
        return load(root, height, false);
    }
    public static RgbStorage load(NBTTagCompound root, WorldHeightContext height, boolean sky) {
        int version = root.getInteger("rgbVersion");
        if ((version != ARRAY_PLANES && version != COMPACT_PLANES)
            || !root.getString("rgbProfile").equals(com.sumirelabs.pulsar.api.BlockLightColors.profile())
            || root.getInteger("rgbMin") != height.getMinLightSection()
            || root.getInteger("rgbCount") != height.getTotalLightSections()
            || !root.hasKey(sky ? "rgbSky" : "rgb", 10))
            return null;
        RgbStorage storage = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections());
        NBTTagCompound data = root.getCompoundTag(sky ? "rgbSky" : "rgb");
        for (int c = 0; c < 3; c++)
            for (int s = 0; s < height.getTotalLightSections(); s++) {
                String key = c + ":" + s;
                if (data.hasKey(key)) {
                    storage.channels[c][s] = SWMRNibbleArray
                        .fromImmutable(readPlane(data, key, storage, c, s, version));
                } else if (sky)
                    storage.channels[c][s] = new SWMRNibbleArray();
            }
        return storage;
    }

    private static byte[] readPlane(NBTTagCompound data, String key, RgbStorage storage, int channel, int section,
        int version) {
        if (version == COMPACT_PLANES && data.hasKey(key, Constants.NBT.TAG_BYTE)) {
            // Nonnegative bytes encode uniform levels. Negative bytes refer to
            // a previously decoded channel in the same section.
            int value = data.getByte(key);
            if (value >= 0 && value <= 15)
                return RgbSnapshot.constantPlane(value);
            int reference = -value - 1;
            if (reference >= 0 && reference < channel) {
                byte[] bytes = storage.channels[reference][section].getVisibleData();
                if (bytes != null)
                    return bytes;
            }
            throw new IllegalArgumentException("Invalid saved RGB plane reference or level");
        }
        byte[] bytes = data.getByteArray(key);
        if (bytes.length != SWMRNibbleArray.ARRAY_SIZE)
            throw new IllegalArgumentException("Invalid saved RGB nibble");
        // NBT arrays are mutable. Retain an immutable copy or an equal plane
        // already loaded from a previous channel.
        byte[] previous = channel == 0 ? null : storage.channels[channel - 1][section].getVisibleData();
        return RgbSnapshot.copyPlane(bytes, previous);
    }

}
