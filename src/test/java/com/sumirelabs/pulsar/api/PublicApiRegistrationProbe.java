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
            BlockLightColors.registerEmission(Blocks.TORCH.getDefaultState(), 8, 4, 2);
            BlockLightFilters.register(Blocks.GLASS.getDefaultState(), 0, 15, 15);
        }
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        Files.createDirectories(home.resolve("config"));
        net.minecraft.launchwrapper.Launch.minecraftHome = home.toFile();
        Files.writeString(home.resolve("config/pulsar.cfg"),
                "I:configVersion=2\nS:lightingMode=" + args[1] + "\n");
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
        expectFrozen(() -> BlockLightColors.register(Blocks.TORCH.getDefaultState(), 15, 15, 15));
        expectFrozen(() -> BlockLightColors.registerEmission(Blocks.TORCH.getDefaultState(), 1, 2, 3));
        expectFrozen(() -> BlockLightColors.disable(Blocks.TORCH.getDefaultState()));
        expectFrozen(() -> BlockLightFilters.register(Blocks.GLASS.getDefaultState(), 0, 0, 0));
        System.out.println("Public API lifecycle passed: " + args[1]);
    }

    private static void expectFrozen(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Registration after freeze must fail");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
