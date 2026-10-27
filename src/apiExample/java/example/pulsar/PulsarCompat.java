package example.pulsar;

import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.BlockLightFilters;
import com.sumirelabs.pulsar.api.LightColors;
import com.sumirelabs.pulsar.api.PackedLightColor;
import com.sumirelabs.pulsar.api.RegisterLightPropertiesEvent;
import net.minecraft.block.Block;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Load only when Pulsar is present; register this instance on MinecraftForge.EVENT_BUS. */
public final class PulsarCompat {
    private final Block lamp;
    private final Block glass;

    public PulsarCompat(Block lamp, Block glass) {
        this.lamp = lamp;
        this.glass = glass;
    }

    @SubscribeEvent
    public void registerLight(RegisterLightPropertiesEvent event) {
        BlockLightColors.registerEmissionAll(lamp, PackedLightColor.pack(15, 8, 3));
        // Transmit red: minimum absorption is the complement of red transmittance.
        BlockLightFilters.registerAll(glass, PackedLightColor.complement(LightColors.RED));
    }
}
