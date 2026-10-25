package com.sumirelabs.pulsar.api;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Register static RGB properties using {@link BlockLightColors} and
 * {@link BlockLightFilters} from a Forge {@code @SubscribeEvent} listener.
 * Posted once per physical process on {@code MinecraftForge.EVENT_BUS}, during
 * Pulsar's load-complete handler, in both RGB and SCALAR mode.
 * Register listeners by the end of post-init; no ordering dependency on Pulsar
 * is required. This event is not cancelable and has no result.
 *
 * <p>Built-in defaults have already been installed. After listeners return,
 * state-interface defaults fill missing entries, user palette files override
 * registrations in RGB mode, and the registries are frozen in both modes.
 * Among explicit registrations for the same state, the last write wins.
 * SCALAR does not use RGB properties for propagation.
 *
 * <p>Register the same properties on client and server. Do not access a world,
 * retain this event for later registration, or call registry lifecycle methods
 * such as {@code freeze()}, {@code defaults()} or {@code interfaceDefaults()}.
 */
public final class RegisterLightPropertiesEvent extends Event {}
