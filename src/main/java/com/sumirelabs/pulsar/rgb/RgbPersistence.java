package com.sumirelabs.pulsar.rgb;

import com.sumirelabs.pulsar.light.SWMRNibbleArray;
import net.minecraft.nbt.NBTTagCompound;
import com.sumirelabs.pulsar.util.WorldHeightContext;

/** RGB v2 is independent of the established scalar cache format. */
public final class RgbPersistence {
    private RgbPersistence() {}
    public static void save(NBTTagCompound root, RgbSnapshot snapshot) { save(root, snapshot, false); }
    public static void save(NBTTagCompound root, RgbSnapshot snapshot, boolean sky) {
        root.setInteger("rgbVersion", 2);
        root.setString("rgbProfile", com.sumirelabs.pulsar.api.BlockLightColors.profile());
        root.setInteger("rgbMin", snapshot.minSection);
        root.setInteger("rgbCount", snapshot.sectionCount());
        NBTTagCompound data = new NBTTagCompound();
        for (int c = 0; c < 3; c++) for (int s = 0; s < snapshot.sectionCount(); s++) {
            byte[] bytes = snapshot.section(c, s);
            if (bytes != null) data.setByteArray(c + ":" + s, bytes);
        }
        root.setTag(sky ? "rgbSky" : "rgb", data);
    }
    public static RgbStorage load(NBTTagCompound root, WorldHeightContext height) { return load(root, height, false); }
    public static RgbStorage load(NBTTagCompound root, WorldHeightContext height, boolean sky) {
        if (root.getInteger("rgbVersion") != 2 || !root.getString("rgbProfile").equals(com.sumirelabs.pulsar.api.BlockLightColors.profile())
                || root.getInteger("rgbMin") != height.getMinLightSection()
                || root.getInteger("rgbCount") != height.getTotalLightSections() || !root.hasKey(sky ? "rgbSky" : "rgb", 10)) return null;
        RgbStorage storage = new RgbStorage(height.getMinLightSection(), height.getTotalLightSections());
        NBTTagCompound data = root.getCompoundTag(sky ? "rgbSky" : "rgb");
        for (int c = 0; c < 3; c++) for (int s = 0; s < height.getTotalLightSections(); s++) {
            String key = c + ":" + s;
            if (data.hasKey(key)) {
                byte[] bytes = data.getByteArray(key);
                if (bytes.length != 2048) throw new IllegalArgumentException("Invalid saved RGB nibble");
                storage.channels[c][s] = new SWMRNibbleArray(bytes.clone());
            } else if (sky) storage.channels[c][s] = new SWMRNibbleArray();
        }
        return storage;
    }
}
