package com.vplayer.net;

import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;

/**
 * 客户端 -> 服务端：控制屏幕（播放/暂停/停止/进度/倍速/声音范围）。
 */
public record ScreenControlMsg(BlockPos pos, String url, boolean loop, byte action, float value) {

    public static final byte ACTION_PLAY = 0;
    public static final byte ACTION_PAUSE = 1;
    public static final byte ACTION_STOP = 2;
    public static final byte ACTION_SEEK = 3;
    public static final byte ACTION_SPEED = 4;
    public static final byte ACTION_RANGE = 5;

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

    public static ScreenControlMsg decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String url = buf.readUtf(MAX_URL_LENGTH);
        boolean loop = buf.readBoolean();
        byte action = buf.readByte();
        float value = buf.readFloat();
        return new ScreenControlMsg(pos, url, loop, action, value);
    }

    /** 由 VPlayerNetwork 的 consumerMainThread 在主线程调用。 */
    public void handle(CustomPayloadEvent.Context ctx) {
        ServerPlayer player = ctx.getSender();
        if (player == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos p = pos;
        if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof ScreenBlockEntity be)) {
            return;
        }
        ScreenGrid.Group group = ScreenGrid.groupFor(level, p);
        boolean changed = false;
        if (action == ACTION_PLAY) {
            // 整块大屏的每一格都记住地址/循环：之后从任意一块打开界面都能看到地址、直接续播
            for (BlockPos cell : group.cells()) {
                if (level.getBlockEntity(cell) instanceof ScreenBlockEntity cellBe) {
                    cellBe.setUrl(url);
                    cellBe.setLoop(loop);
                    level.sendBlockUpdated(cell, level.getBlockState(cell), level.getBlockState(cell), 3);
                }
            }
            changed = true;
        } else if (action == ACTION_RANGE) {
            for (BlockPos cell : group.cells()) {
                if (level.getBlockEntity(cell) instanceof ScreenBlockEntity cellBe) {
                    cellBe.setAudioRange((int) value);
                    level.sendBlockUpdated(cell, level.getBlockState(cell), level.getBlockState(cell), 3);
                }
            }
            changed = true;
        }
        if (changed) {
            // 立即把方块数据同步给所有能看到这块屏幕的玩家
            level.sendBlockUpdated(p, level.getBlockState(p), level.getBlockState(p), 3);
        }
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos cell : group.cells()) {
            chunks.add(new ChunkPos(cell.getX() >> 4, cell.getZ() >> 4));
        }
        ScreenStateMsg payload = new ScreenStateMsg(p, url, loop, action, value);
        for (ChunkPos chunk : chunks) {
            VPlayerNetwork.CHANNEL.send(payload,
                    PacketDistributor.TRACKING_CHUNK.with(level.getChunk(chunk.x, chunk.z)));
        }
    }
}
