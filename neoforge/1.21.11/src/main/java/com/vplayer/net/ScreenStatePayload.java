package com.vplayer.net;

import com.vplayer.VPlayerMod;
import com.vplayer.client.media.VideoEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 -> 客户端：广播屏幕控制状态（播放/暂停/停止/进度/倍速/声音范围）。
 */
public record ScreenStatePayload(BlockPos pos, String url, boolean loop, byte action, float value)
        implements CustomPacketPayload {

    private static final int MAX_URL_LENGTH = 4096;

    public static final CustomPacketPayload.Type<ScreenStatePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VPlayerMod.MODID, "screen_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenStatePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScreenStatePayload::pos,
            ByteBufCodecs.STRING_UTF8, ScreenStatePayload::url,
            ByteBufCodecs.BOOL, ScreenStatePayload::loop,
            ByteBufCodecs.BYTE, ScreenStatePayload::action,
            ByteBufCodecs.FLOAT, ScreenStatePayload::value,
            ScreenStatePayload::new);

    public ScreenStatePayload {
        if (url == null) {
            url = "";
        }
        if (url.length() > MAX_URL_LENGTH) {
            url = url.substring(0, MAX_URL_LENGTH);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ScreenStatePayload msg, IPayloadContext context) {
        if (FMLEnvironment.getDist().isClient()) {
            VideoEngine.get().apply(msg.pos(), msg.url(), msg.loop(), msg.action(), msg.value());
        }
    }
}
