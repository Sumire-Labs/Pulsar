package com.sumirelabs.pulsar.compat;

import codechicken.microblock.BlockMicroMaterial;
import codechicken.microblock.MicroMaterialRegistry;
import codechicken.microblock.Microblock;
import codechicken.microblock.MicroblockFactory;
import codechicken.multipart.TMultiPart;
import codechicken.multipart.TileMultipart;
import codechicken.lib.vec.Cuboid6;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

/** Real CBMultipart getters/collections; only geometry irrelevant to lighting is stubbed. */
public final class MultipartFixtures {
    private MultipartFixtures() {}
    public static class Part extends Microblock {
        public Part(int id) { super(id); }
        public MicroblockFactory microFactory() { return null; }
        public int itemFactoryID() { return 0; }
        public Cuboid6 getBounds() { return Cuboid6.full; }
        @Override public ResourceLocation getType() { return new ResourceLocation("pulsar", "test_part"); }
    }
    public static class UnknownPart extends TMultiPart {
        public int level;
        public UnknownPart(int level) { this.level = level; }
        public ResourceLocation getType() { return new ResourceLocation("pulsar", "unknown_part"); }
        public int getLightValue() { return level; }
    }
    public static void material(String name, IBlockState state) {
        MicroMaterialRegistry.registerMaterial(new BlockMicroMaterial(state, name), name);
    }
    public static void finishMaterials() throws Exception {
        var module = Class.forName("codechicken.microblock.MicroMaterialRegistry$");
        module.getMethod("setupIDMap").invoke(module.getField("MODULE$").get(null));
    }
    public static Part part(String name) { return new Part(MicroMaterialRegistry.materialID(name)); }
    public static void parts(TileMultipart tile, TMultiPart... parts) {
        tile.partList_$eq(scala.collection.JavaConversions.asScalaBuffer(java.util.Arrays.asList(parts)).toSeq());
        for (TMultiPart part : parts) part.bind(tile);
    }
}
