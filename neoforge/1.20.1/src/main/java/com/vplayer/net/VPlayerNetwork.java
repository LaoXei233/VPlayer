package com.vplayer.net;

import com.vplayer.VPlayerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Forge / NeoForge 1.20.1 的 SimpleChannel 网络注册。
 */
public final class VPlayerNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(VPlayerMod.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();

    private static int id = 0;

    public static void init() {
        CHANNEL.messageBuilder(ScreenControlMsg.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ScreenControlMsg::encode)
                .decoder(ScreenControlMsg::decode)
                .consumerMainThread(ScreenControlMsg::handle)
                .add();

        CHANNEL.messageBuilder(ScreenStateMsg.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ScreenStateMsg::encode)
                .decoder(ScreenStateMsg::decode)
                .consumerMainThread(ScreenStateMsg::handle)
                .add();
    }

    private VPlayerNetwork() {}
}
