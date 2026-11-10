package com.vplayer.net;

import com.vplayer.client.media.VideoEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * 服务端 -> 客户端：广播屏幕控制状态（播放/暂停/停止/进度/倍速/声音范围）。
 */
public record ScreenStatePayload(BlockPos pos, String url, boolean loop, byte action, float value) {

    private static final int MAX_URL_LENGTH = 4096;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        String u = url == null ? "" : url;
        if (u.length() > MAX_URL_LENGTH) {
            u = u.substring(0, MAX_URL_LENGTH);
        }
        buf.writeUtf(u);
        buf.writeBoolean(loop);
        buf.writeByte(action);
        buf.writeFloat(value);
    }

    public static ScreenStatePayload decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String url = buf.readUtf(MAX_URL_LENGTH);
        boolean loop = buf.readBoolean();
        byte action = buf.readByte();
        float value = buf.readFloat();
        return new ScreenStatePayload(pos, url, loop, action, value);
    }

    /** 由 VPlayerNetwork 的 consumerMainThread 在客户端主线程调用。 */
    public void handle(CustomPayloadEvent.Context ctx) {
        if (ctx.isClientSide()) {
            VideoEngine.get().apply(pos, url, loop, action, value);
        }
    }
}
