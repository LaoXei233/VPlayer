package com.vplayer.net;

import com.vplayer.VPlayerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;

/**
 * Forge 1.21.1 的网络注册。
 * 1.21.1 的 Forge 网络 API 已重写：使用 ChannelBuilder 构建 SimpleChannel，
 * 消息用 messageBuilder 注册，handler 用 net.minecraftforge.event.network.CustomPayloadEvent.Context。
 * （官方源码：net.minecraftforge.network.ChannelBuilder / SimpleChannel）
 */
public final class VPlayerNetwork {

    private static final int PROTOCOL = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(VPlayerMod.MODID, "main"))
            .networkProtocolVersion(PROTOCOL)
            .simpleChannel();

    private static int id = 0;

    public static void init() {
        CHANNEL.messageBuilder(ScreenControlMsg.class, id++)
                .encoder(ScreenControlMsg::encode)
                .decoder(ScreenControlMsg::decode)
                .consumerMainThread((msg, ctx) -> msg.handle(ctx))
                .add();

        CHANNEL.messageBuilder(ScreenStateMsg.class, id++)
                .encoder(ScreenStateMsg::encode)
                .decoder(ScreenStateMsg::decode)
                .consumerMainThread((msg, ctx) -> msg.handle(ctx))
                .add();

        CHANNEL.build();
    }

    private VPlayerNetwork() {}
}
