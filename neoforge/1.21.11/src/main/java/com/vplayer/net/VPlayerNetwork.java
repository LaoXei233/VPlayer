package com.vplayer.net;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge Payload 注册。处理器默认运行在主线程。
 */
public final class VPlayerNetwork {

    public static void init0(IEventBus modEventBus) {
        modEventBus.addListener(VPlayerNetwork::register);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                ScreenControlPayload.TYPE,
                ScreenControlPayload.STREAM_CODEC,
                ScreenControlPayload::handle);

        registrar.playToClient(
                ScreenStatePayload.TYPE,
                ScreenStatePayload.STREAM_CODEC,
                ScreenStatePayload::handle);
    }

    private VPlayerNetwork() {}
}
