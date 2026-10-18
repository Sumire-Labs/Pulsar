package com.sumirelabs.pulsar.light.nativeprobe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.client.model.pipeline.ForgeBlockModelRenderer;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.ByteOrder;
import java.util.*;

/** Bakes the shipped Minecraft models and verifies the lightmap UVs written for their actual faces. */
public final class PartialSurfaceProbe {
    private static final BlockPos ORIGIN = new BlockPos(-31, 64, 47);
    private static final TextureAtlasSprite SPRITE = new TextureAtlasSprite("pulsar:surface_audit") {};
    private static final Map<ResourceLocation, ModelBlock> MODELS = new HashMap<>();
    private static final FaceBakery BAKERY = new FaceBakery();

    public static void run(boolean baseline) throws Exception {
        SPRITE.setIconWidth(16); SPRITE.setIconHeight(16); SPRITE.initSprite(16, 16, 0, 0, false);
        int states = 0, quads = 0, vertices = 0, leaks = 0, insetLeaks = 0;
        for (IBlockState state : states()) {
            int stateLeaks = 0;
            Set<Integer> gradientValues = new HashSet<>();
            for (BakedQuad quad : bake(state)) {
                quads++;
                for (int renderer = 0; renderer < (Boolean.getBoolean("issue20.celeritas") ? 3 : 2); renderer++) {
                    for (int channel : new int[]{15 << 4, 15 << 20}) {
                        SurfaceWorld world = sealed(state, quad, channel);
                        int[] output = render(renderer, world, state, quad);
                        for (int value : output) {
                            vertices++;
                            if (value != 0) {
                                leaks++; stateLeaks++;
                                if (inset(quad)) insetLeaks++;
                                if (!baseline) throw new AssertionError("Actual surface leak renderer=" + renderer
                                        + " state=" + state + " face=" + quad.getFace() + " inset=" + inset(quad)
                                        + " light=" + Integer.toHexString(value));
                            }
                        }
                        // A room illuminated on the exposed side must stay illuminated.
                        world.uniform = channel;
                        for (int value : render(renderer, world, state, quad)) {
                            if (value == 0) throw new AssertionError("Darkened exposed surface: " + state + " " + quad.getFace());
                        }
                        world.uniform = 0;
                        world.surrounding = channel == (15 << 4) ? 14 << 4 : 14 << 20;
                        world.lights.clear();
                        world.lights.put(ORIGIN, channel == (15 << 4) ? 13 << 4 : 13 << 20);
                        world.states.clear();
                        world.states.put(ORIGIN, state);
                        for (int value : render(renderer, world, state, quad)) {
                            int component = channel == (15 << 4) ? value & 0xffff : value >>> 16;
                            if (component < (13 << 4) - 1) throw new AssertionError("Lost illuminated gradient: " + state);
                            gradientValues.add(component);
                        }
                    }
                }
            }
            if (baseline && stateLeaks == 0) throw new AssertionError("Fixture did not reproduce for " + state);
            if (!baseline && gradientValues.size() < 2) throw new AssertionError("Flattened partial-model gradient: " + state);
            states++;
        }
        if (baseline && insetLeaks == 0) throw new AssertionError("No inset-face reproduction");
        System.out.println("PARTIAL_SURFACES_PASS states=" + states + " quads=" + quads + " vertices=" + vertices
                + " leaks=" + leaks + " insetLeaks=" + insetLeaks + " baseline=" + baseline);
    }

    private static List<IBlockState> states() {
        List<IBlockState> result = new ArrayList<>();
        for (BlockSlab.EnumBlockHalf half : BlockSlab.EnumBlockHalf.values()) {
            result.add(Blocks.WOODEN_SLAB.getDefaultState().withProperty(BlockSlab.HALF, half));
        }
        for (EnumFacing facing : EnumFacing.HORIZONTALS) for (BlockStairs.EnumHalf half : BlockStairs.EnumHalf.values()) {
            for (BlockStairs.EnumShape shape : BlockStairs.EnumShape.values()) {
                result.add(Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, facing)
                        .withProperty(BlockStairs.HALF, half).withProperty(BlockStairs.SHAPE, shape));
            }
        }
        return result;
    }

    private static List<BakedQuad> bake(IBlockState state) throws Exception {
        ResourceLocation model;
        ModelRotation rotation = ModelRotation.X0_Y0;
        boolean uvlock = false;
        if (state.getBlock() instanceof BlockSlab) {
            model = new ResourceLocation("block/" + (state.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP ? "upper_slab" : "half_slab"));
        } else {
            String variant = "facing=" + state.getValue(BlockStairs.FACING).getName() + ",half="
                    + state.getValue(BlockStairs.HALF).getName() + ",shape=" + state.getValue(BlockStairs.SHAPE).getName();
            try (var reader = resource("assets/minecraft/blockstates/oak_stairs.json")) {
                JsonObject description = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("variants").getAsJsonObject(variant);
                model = new ResourceLocation("block/" + description.get("model").getAsString());
                rotation = ModelRotation.getModelRotation(description.has("x") ? description.get("x").getAsInt() : 0,
                        description.has("y") ? description.get("y").getAsInt() : 0);
                uvlock = description.has("uvlock") && description.get("uvlock").getAsBoolean();
            }
        }
        ModelBlock block = model(model);
        List<BakedQuad> result = new ArrayList<>();
        for (BlockPart part : block.getElements()) for (var face : part.mapFaces.entrySet()) {
            result.add(BAKERY.makeBakedQuad(part.positionFrom, part.positionTo, face.getValue(), SPRITE,
                    face.getKey(), rotation, part.partRotation, uvlock, part.shade));
        }
        return result;
    }

    private static ModelBlock model(ResourceLocation name) throws Exception {
        if (MODELS.containsKey(name)) return MODELS.get(name);
        ModelBlock block;
        try (var reader = resource("assets/" + name.getNamespace() + "/models/" + name.getPath() + ".json")) {
            block = ModelBlock.deserialize(reader);
        }
        MODELS.put(name, block);
        if (block.getParentLocation() != null) {
            model(block.getParentLocation());
            block.getParentFromMap(MODELS);
        }
        return block;
    }

    private static InputStreamReader resource(String name) {
        var source = PartialSurfaceProbe.class.getClassLoader().getResourceAsStream(name);
        if (source == null) throw new AssertionError("Missing vanilla model " + name);
        return new InputStreamReader(source, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static float coordinate(BakedQuad quad, int vertex, int axis) {
        return Float.intBitsToFloat(quad.getVertexData()[vertex * quad.getFormat().getIntegerSize() + axis]);
    }

    private static boolean inset(BakedQuad quad) {
        int axis = quad.getFace().getAxis() == EnumFacing.Axis.X ? 0 : quad.getFace().getAxis() == EnumFacing.Axis.Y ? 1 : 2;
        float plane = coordinate(quad, 0, axis);
        return plane > .001f && plane < .999f;
    }

    private static SurfaceWorld sealed(IBlockState state, BakedQuad quad, int channel) {
        SurfaceWorld world = new SurfaceWorld(state);
        EnumFacing face = quad.getFace();
        int axis = face.getAxis() == EnumFacing.Axis.X ? 0 : face.getAxis() == EnumFacing.Axis.Y ? 1 : 2;
        float plane = coordinate(quad, 0, axis);
        int layer = MathHelper.floor(plane + (face.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? .0001 : -.0001));
        BlockPos base = ORIGIN.add(axis == 0 ? layer : 0, axis == 1 ? layer : 0, axis == 2 ? layer : 0);
        if (!base.equals(ORIGIN)) world.states.put(base, Blocks.AIR.getDefaultState());
        EnumFacing a = axis == 0 ? EnumFacing.UP : EnumFacing.EAST;
        EnumFacing b = axis == 2 ? EnumFacing.UP : EnumFacing.SOUTH;
        // Four independently lit cells, each separated from the visible side by
        // two solid edge blocks. Stored light in the visible cell remains zero.
        for (EnumFacing u : new EnumFacing[]{a, a.getOpposite()}) for (EnumFacing v : new EnumFacing[]{b, b.getOpposite()}) {
            BlockPos source = base.offset(u).offset(v);
            world.states.put(source, Blocks.AIR.getDefaultState());
            world.lights.put(source, channel);
        }
        return world;
    }

    private static int[] render(int renderer, SurfaceWorld world, IBlockState state, BakedQuad quad) throws Exception {
        if (renderer == 2) return celeritas(world, quad);
        EnumMap<EnumFacing, List<BakedQuad>> faces = new EnumMap<>(EnumFacing.class);
        for (EnumFacing face : EnumFacing.VALUES) faces.put(face, Collections.emptyList());
        IBakedModel model = new SimpleBakedModel(List.of(quad), faces, true, true, SPRITE,
                ItemCameraTransforms.DEFAULT, ItemOverrideList.NONE);
        BufferBuilder buffer = new BufferBuilder(1024);
        buffer.begin(7, DefaultVertexFormats.BLOCK);
        BlockModelRenderer pipeline = renderer == 0 ? new BlockModelRenderer(new BlockColors())
                : new ForgeBlockModelRenderer(new BlockColors());
        pipeline.renderModelSmooth(world, model, state, ORIGIN, buffer, false, 0);
        buffer.finishDrawing();
        if (buffer.getVertexCount() != 4) throw new AssertionError("Quad was not rendered");
        var bytes = buffer.getByteBuffer().order(ByteOrder.nativeOrder());
        int stride = DefaultVertexFormats.BLOCK.getSize(), offset = DefaultVertexFormats.BLOCK.getUvOffsetById(1);
        int[] result = new int[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * stride + offset;
            result[vertex] = Short.toUnsignedInt(bytes.getShort(index)) | (Short.toUnsignedInt(bytes.getShort(index + 2)) << 16);
        }
        return result;
    }

    private static int[] celeritas(SurfaceWorld world, BakedQuad quad) throws Exception {
        Class<?> access = Class.forName("org.embeddedt.embeddium.impl.model.light.data.LightDataAccess");
        Class<?> cacheClass = Class.forName("org.taumc.celeritas.impl.render.terrain.compile.light.LightDataCache");
        Object cache = cacheClass.getConstructor(IBlockAccess.class).newInstance(world);
        access.getMethod("reset", int.class, int.class, int.class).invoke(cache, ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
        Class<?> diffuse = Class.forName("org.embeddedt.embeddium.impl.model.light.DiffuseProvider");
        Object shading = Proxy.newProxyInstance(diffuse.getClassLoader(), new Class[]{diffuse}, (proxy, method, args) -> 1f);
        Class<?> pipeline = Class.forName("org.embeddedt.embeddium.impl.model.light.smooth.SmoothLightPipeline");
        Object lighter = pipeline.getConstructor(access, diffuse, boolean.class).newInstance(cache, shading, false);
        Class<?> data = Class.forName("org.embeddedt.embeddium.impl.model.light.data.QuadLightData");
        Object output = data.getConstructor().newInstance();
        Class<?> view = Class.forName("org.embeddedt.embeddium.impl.model.quad.ModelQuadView");
        Class<?> facing = Class.forName("org.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing");
        String direction = (quad.getFace().getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? "POS_" : "NEG_") + quad.getFace().getAxis().name();
        Object normal = facing.getField(direction).get(null);
        pipeline.getMethod("calculate", view, int.class, int.class, int.class, data, facing, facing, boolean.class, boolean.class)
                .invoke(lighter, quad, ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ(), output, null, normal, false, false);
        return ((int[]) data.getField("lm").get(output)).clone();
    }

    private static final class SurfaceWorld implements IBlockAccess {
        final IBlockState rendered;
        final Map<BlockPos, IBlockState> states = new HashMap<>();
        final Map<BlockPos, Integer> lights = new HashMap<>();
        int uniform, surrounding;
        SurfaceWorld(IBlockState state) { rendered = state; states.put(ORIGIN, state); }
        public IBlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, surrounding != 0 ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState());
        }
        public int getCombinedLight(BlockPos pos, int emission) {
            int value = uniform == 0 ? lights.getOrDefault(pos, surrounding) : uniform;
            return (value & 0xffff0000) | Math.max(value & 0xffff, emission << 4);
        }
        public TileEntity getTileEntity(BlockPos pos) { return null; }
        public boolean isAirBlock(BlockPos pos) { return getBlockState(pos).getBlock() == Blocks.AIR; }
        public Biome getBiome(BlockPos pos) { return null; }
        public int getStrongPower(BlockPos pos, EnumFacing face) { return 0; }
        public WorldType getWorldType() { return WorldType.DEFAULT; }
        public boolean isSideSolid(BlockPos pos, EnumFacing face, boolean fallback) { return getBlockState(pos).isSideSolid(this, pos, face); }
    }
}
