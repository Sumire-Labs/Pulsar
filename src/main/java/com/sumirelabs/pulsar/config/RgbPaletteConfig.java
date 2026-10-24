package com.sumirelabs.pulsar.config;

import com.sumirelabs.pulsar.Pulsar;
import com.sumirelabs.pulsar.api.BlockLightColors;
import com.sumirelabs.pulsar.api.BlockLightFilters;
import com.sumirelabs.pulsar.rgb.RgbLight;
import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** User overrides are applied after mod initialization, before registries freeze. */
public final class RgbPaletteConfig {
    private RgbPaletteConfig() {}

    public static void load(Path directory) {
        try {
            Files.createDirectories(directory);
            load(directory.resolve("pulsar-colors.cfg"), true, (state, r, g, b) -> {
                if ((r | g | b) == 0) BlockLightColors.disable(state);
                else BlockLightColors.register(state, r, g, b);
            });
            load(directory.resolve("pulsar-filters.cfg"), false, BlockLightFilters::register);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Pulsar RGB palettes in " + directory, e);
        }
    }

    private static void load(Path file, boolean colors, ColorConsumer apply) throws IOException {
        if (!Files.exists(file)) {
            String description = colors
                    ? "# Source chromaticity: the maximum is scaled to contextual scalar emission; 0,0,0 disables RGB emission.\n"
                    : "# Minimum absorption: 0 adds no absorption; 15 blocks the component.\n";
            Files.writeString(file, "# Pulsar RGB " + (colors ? "source colors" : "filters") + "\n"
                    + description + "# Restart the server to apply. User overrides take precedence over built-in/mod registrations.\n"
                    + "# Later lines override earlier lines. These files are not reset by the main config version.\n"
                    + "# Format: namespace:block[property=value,...] = red, green, blue (0..15).\n"
                    + "# Omit properties to match all states; namespace:block:meta also matches legacy metadata.\n"
                    + (colors ? "# minecraft:torch = 15, 8, 3\n" : "# minecraft:stained_glass[color=red] = 0, 15, 15\n"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        int lineNumber = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final int number = ++lineNumber;
            parse(line, colors, (state, rgb) -> apply.accept(state,
                            RgbLight.channel(rgb, 0), RgbLight.channel(rgb, 1), RgbLight.channel(rgb, 2)),
                    warning -> Pulsar.LOGGER.warn("{}:{}: {}", file.getFileName(), number, warning));
        }
    }

    @FunctionalInterface private interface ColorConsumer {
        void accept(IBlockState state, int r, int g, int b);
    }

    static void parse(String line, boolean colors, BiConsumer<IBlockState, Integer> apply, Consumer<String> warn) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) return;
        try {
            int equals = line.lastIndexOf('=');
            if (equals < 0) throw new IllegalArgumentException("Expected block selector = r, g, b");
            String selector = line.substring(0, equals).trim();
            String[] values = line.substring(equals + 1).trim().split(",", -1);
            if (values.length != 3) throw new IllegalArgumentException("Expected three integer components");
            int rgb = RgbLight.pack(Integer.parseInt(values[0].trim()), Integer.parseInt(values[1].trim()),
                    Integer.parseInt(values[2].trim()));
            // Resolve everything before applying, so a malformed selector changes no states.
            for (IBlockState state : select(selector)) apply.accept(state, rgb);
        } catch (IllegalArgumentException e) {
            warn.accept("Skipped RGB override: " + e.getMessage());
        }
    }

    static List<IBlockState> select(String selector) {
        Map<String, String> properties = new LinkedHashMap<>();
        int open = selector.indexOf('[');
        if (open >= 0) {
            if (!selector.endsWith("]")) throw new IllegalArgumentException("Unclosed state selector: " + selector);
            String statePart = selector.substring(open + 1, selector.length() - 1);
            for (String part : statePart.split(",", -1)) {
                String[] pair = part.trim().split("=", -1);
                if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()
                        || properties.putIfAbsent(pair[0].trim(), pair[1].trim()) != null)
                    throw new IllegalArgumentException("Invalid or duplicate state property: " + part);
            }
            selector = selector.substring(0, open).trim();
        }
        String[] idParts = selector.split(":", -1);
        if (idParts.length < 2 || idParts.length > 3 || idParts[0].isBlank() || idParts[1].isBlank())
            throw new IllegalArgumentException("Expected namespace:block[:meta]");
        Integer meta = idParts.length == 3 && !idParts[2].equals("*") ? Integer.valueOf(idParts[2]) : null;
        if (meta != null && (meta < 0 || meta > 15)) throw new IllegalArgumentException("Metadata must be 0..15");
        ResourceLocation id = new ResourceLocation(idParts[0], idParts[1]);
        if (!Block.REGISTRY.containsKey(id)) throw new IllegalArgumentException("Unknown block " + id);
        Block block = Block.REGISTRY.getObject(id);
        if (block == net.minecraft.init.Blocks.AIR) throw new IllegalArgumentException("Air cannot emit or filter RGB light");
        for (var pair : properties.entrySet()) {
            IProperty<?> property = block.getBlockState().getProperty(pair.getKey());
            if (property == null || !property.parseValue(pair.getValue()).isPresent())
                throw new IllegalArgumentException("Unknown property/value " + pair + " for " + id);
        }
        List<IBlockState> selected = new ArrayList<>();
        for (IBlockState state : block.getBlockState().getValidStates()) {
            if (meta != null && block.getMetaFromState(state) != meta) continue;
            boolean matches = true;
            for (var pair : properties.entrySet()) {
                IProperty<?> property = block.getBlockState().getProperty(pair.getKey());
                if (!propertyName(state, property).equals(pair.getValue())) { matches = false; break; }
            }
            if (matches) selected.add(state);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("Selector matches no states: " + selector);
        return selected;
    }

    private static <T extends Comparable<T>> String propertyName(IBlockState state, IProperty<T> property) {
        return property.getName(state.getValue(property));
    }
}
