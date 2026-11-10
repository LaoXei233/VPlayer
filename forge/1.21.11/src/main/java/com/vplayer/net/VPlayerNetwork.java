package com.vplayer.net;

import com.vplayer.VPlayerMod;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;

/**
 * Forge 1.21.11 的网络注册。
 * Forge 61 的网络 API 与 1.21.1 Forge 一致：ChannelBuilder 构建 SimpleChannel，
 * 消息用 messageBuilder 注册，handler 上下文是 net.minecraftforge.event.network.CustomPayloadEvent.Context。
 * （官方源码：net.minecraftforge.network.ChannelBuilder / SimpleChannel，61.2.5）
 */
public final class VPlayerNetwork {

    private static final int PROTOCOL = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(VPlayerMod.MODID, "main"))
            .networkProtocolVersion(PROTOCOL)
            .simpleChannel();

    private static int id = 0;

    /** 在模组构造器里调用一次，注册两个消息类型。（通道对象本身在类加载时创建） */
    public static void init0(net.minecraftforge.eventbus.api.bus.BusGroup modBusGroup) {
        CHANNEL.messageBuilder(ScreenControlPayload.class, id++)
                .encoder(ScreenControlPayload::encode)
                .decoder(ScreenControlPayload::decode)
                .consumerMainThread((msg, ctx) -> msg.handle(ctx))
                .add();

        CHANNEL.messageBuilder(ScreenStatePayload.class, id++)
                .encoder(ScreenStatePayload::encode)
                .decoder(ScreenStatePayload::decode)
                .consumerMainThread((msg, ctx) -> msg.handle(ctx))
                .add();

        CHANNEL.build();
    }

    private VPlayerNetwork() {}
}
