package com.sumirelabs.pulsar.api;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.core.RgbMixinSelection;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs real Forge dispatch and Pulsar's load-complete handler in an isolated JVM. */
public final class PublicApiRegistrationProbe {
    private static int calls;

    public static final class Integration {
        @SubscribeEvent
        public void register(RegisterLightPropertiesEvent event) {
            check(!event.isCancelable() && !event.hasResult(), "Event is informational");
            calls++;
            BlockLightColors.registerEmission(Blocks.TORCH.getDefaultState(), 0x842);
            BlockLightFilters.register(Blocks.GLASS.getDefaultState(), 0, 15, 15);
            BlockLightColors.registerEmission(Blocks.WOOL.getDefaultState(), 0x111);
            BlockLightColors.registerEmissionAll(Blocks.WOOL, 0x842);
            BlockLightColors.register(Blocks.WOOL.getDefaultState(), LightColors.WHITE);
            BlockLightColors.registerEmissionAll(Blocks.STAINED_HARDENED_CLAY, 0x111);
            BlockLightColors.registerAll(Blocks.STAINED_HARDENED_CLAY, 0x842);
            BlockLightColors.registerEmissionAll(Blocks.CONCRETE, LightColors.RED);
            BlockLightColors.disableAll(Blocks.CONCRETE);
            BlockLightFilters.registerAll(Blocks.STAINED_GLASS, PackedLightColor.complement(LightColors.RED));
            BlockLightFilters.register(Blocks.STAINED_GLASS.getDefaultState(), 0x123);
            // Rejected requests must leave every state's previous value intact.
            expectInvalid(() -> BlockLightColors.registerAll(Blocks.WOOL, 0));
            expectInvalid(() -> BlockLightColors.registerAll(Blocks.WOOL, 0x1000));
            expectInvalid(() -> BlockLightColors.registerEmissionAll(Blocks.WOOL, -1));
            expectInvalid(() -> BlockLightFilters.registerAll(Blocks.STAINED_GLASS, 0x1000));
            expectInvalid(() -> BlockLightColors.registerAll(Blocks.AIR, LightColors.WHITE));
            expectInvalid(() -> BlockLightColors.registerEmissionAll(Blocks.AIR, 0));
            expectInvalid(() -> BlockLightColors.disableAll(Blocks.AIR));
            expectInvalid(() -> BlockLightFilters.registerAll(Blocks.AIR, 0));
        }
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        Files.createDirectories(home.resolve("config"));
        net.minecraft.launchwrapper.Launch.minecraftHome = home.toFile();
        var injectionHome = net.minecraftforge.fml.relauncher.FMLInjectionData.class.getDeclaredField("minecraftHome");
        injectionHome.setAccessible(true); injectionHome.set(null, home.toFile());
        Files.writeString(home.resolve("config/pulsar.cfg"),
                "general {\nI:configVersion=2\nS:lightingMode=" + args[1] + "\n}\n");
        new RgbMixinSelection().onLoad("com.sumirelabs.pulsar.mixin");
        Bootstrap.register();
        BlockLightColors.defaults();
        BlockLightFilters.defaults();
        // The same user override must win in RGB and remain unused in SCALAR.
        Files.writeString(home.resolve("config/pulsar-colors.cfg"), "minecraft:torch = 0,0,15\n");
        Files.writeString(home.resolve("config/pulsar-filters.cfg"), "minecraft:glass = 15,0,0\n");
        MinecraftForge.EVENT_BUS.register(new Integration());
        new Pulsar().loadComplete(null);
        check(calls == 1, "Registration listener must run once in either mode");
        int expectedColor = args[1].equals("RGB") ? 0x00F : 0x842;
        int expectedFilter = args[1].equals("RGB") ? 0xF00 : 0x0FF;
        check(RgbStateProperties.color(RgbStateProperties.of(Blocks.TORCH.getDefaultState())) == expectedColor,
                "Color event/config precedence");
        check(RgbStateProperties.filter(RgbStateProperties.of(Blocks.GLASS.getDefaultState())) == expectedFilter,
                "Filter event/config precedence");
        for (var state : Blocks.WOOL.getBlockState().getValidStates()) {
            int properties = RgbStateProperties.of(state);
            boolean exception = state == Blocks.WOOL.getDefaultState();
            check(RgbStateProperties.color(properties) == (exception ? 0xFFF : 0x842), "All-state emission/per-state override");
            check(((properties & RgbStateProperties.SOURCE_ABSOLUTE) != 0) != exception, "Hue must clear absolute emission flag");
        }
        for (var state : Blocks.STAINED_HARDENED_CLAY.getBlockState().getValidStates()) {
            int properties = RgbStateProperties.of(state);
            check(RgbStateProperties.color(properties) == 0xF84, "All-state hue normalization");
            check((properties & RgbStateProperties.SOURCE_ABSOLUTE) == 0, "All-state hue clears absolute emission flags");
        }
        for (var state : Blocks.CONCRETE.getBlockState().getValidStates()) {
            int properties = RgbStateProperties.of(state);
            check(RgbStateProperties.color(properties) == 0, "All-state disable");
            check((properties & RgbStateProperties.SOURCE_ABSOLUTE) == 0, "Disable clears absolute emission flags");
        }
        for (var state : Blocks.STAINED_GLASS.getBlockState().getValidStates()) {
            int filter = RgbStateProperties.filter(RgbStateProperties.of(state));
            check(filter == (state == Blocks.STAINED_GLASS.getDefaultState() ? 0x123 : 0x0FF), "All-state filter/per-state override");
        }
        expectFrozen(() -> BlockLightColors.register(Blocks.TORCH.getDefaultState(), 15, 15, 15));
        expectFrozen(() -> BlockLightColors.registerEmission(Blocks.TORCH.getDefaultState(), 1, 2, 3));
        expectFrozen(() -> BlockLightColors.disable(Blocks.TORCH.getDefaultState()));
        expectFrozen(() -> BlockLightFilters.register(Blocks.GLASS.getDefaultState(), 0, 0, 0));
        expectFrozen(() -> BlockLightColors.registerAll(Blocks.WOOL, LightColors.WHITE));
        expectFrozen(() -> BlockLightColors.registerEmissionAll(Blocks.WOOL, LightColors.BLUE));
        expectFrozen(() -> BlockLightColors.disableAll(Blocks.WOOL));
        expectFrozen(() -> BlockLightFilters.registerAll(Blocks.STAINED_GLASS, 0));
        System.out.println("Public API lifecycle passed: " + args[1]);
    }

    private static void expectFrozen(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Registration after freeze must fail");
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid registration must fail");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
