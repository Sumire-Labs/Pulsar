/* SPDX-License-Identifier: LGPL-3.0-only */
package com.sumirelabs.pulsar.compat;

import io.netty.channel.Channel;

/** Keeps Forge's initial PLAY transition behind already queued LOGIN writes. */
public final class LoginProtocolOrder {
    private LoginProtocolOrder() {}

    public static void advance(Channel channel, Runnable transition) {
        if (!channel.isOpen()) return;
        if (channel.eventLoop().inEventLoop()) {
            transition.run();
        } else {
            channel.eventLoop().execute(() -> {
                if (channel.isOpen()) transition.run();
            });
        }
    }
}
