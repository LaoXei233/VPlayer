package com.vplayer.net;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashSet;
import java.util.Set;

/**
 * 客户端 -> 服务端：控制屏幕（播放/暂停/停止/进度/倍速/声音范围）。
 */
public record ScreenControlPayload(BlockPos pos, String url, boolean loop, byte action, float value)
        implements CustomPacketPayload {

    public static final byte ACTION_PLAY = 0;
    public static final byte ACTION_PAUSE = 1;
    public static final byte ACTION_STOP = 2;
    public static final byte ACTION_SEEK = 3;
    public static final byte ACTION_SPEED = 4;
    public static final byte ACTION_RANGE = 5;

    private static final int MAX_URL_LENGTH = 4096;

    public static final CustomPacketPayload.Type<ScreenControlPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VPlayerMod.MODID, "screen_control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenControlPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ScreenControlPayload::pos,
            ByteBufCodecs.STRING_UTF8, ScreenControlPayload::url,
            ByteBufCodecs.BOOL, ScreenControlPayload::loop,
            ByteBufCodecs.BYTE, ScreenControlPayload::action,
            ByteBufCodecs.FLOAT, ScreenControlPayload::value,
            ScreenControlPayload::new);

    public ScreenControlPayload {
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

    public static void handle(ScreenControlPayload msg, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.level();
        BlockPos pos = msg.pos();
        if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof ScreenBlockEntity) {
            ScreenGrid.Group group = ScreenGrid.groupFor(level, pos);
            boolean changed = false;
            if (msg.action() == ACTION_PLAY) {
                // 整块大屏的每一格都记住地址/循环：之后从任意一块打开界面都能看到地址、直接续播
                for (BlockPos cell : group.cells()) {
                    if (level.getBlockEntity(cell) instanceof ScreenBlockEntity cellBe) {
                        cellBe.setUrl(msg.url());
                        cellBe.setLoop(msg.loop());
                        level.sendBlockUpdated(cell, level.getBlockState(cell), level.getBlockState(cell), 3);
                    }
                }
                changed = true;
            } else if (msg.action() == ACTION_RANGE) {
                for (BlockPos cell : group.cells()) {
                    if (level.getBlockEntity(cell) instanceof ScreenBlockEntity cellBe) {
                        cellBe.setAudioRange((int) msg.value());
                        level.sendBlockUpdated(cell, level.getBlockState(cell), level.getBlockState(cell), 3);
                    }
                }
                changed = true;
            }
            if (changed) {
                // 立即把方块数据同步给所有能看到这块屏幕的玩家
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            }
            Set<ChunkPos> chunks = new HashSet<>();
            for (BlockPos cell : group.cells()) {
                chunks.add(new ChunkPos(cell));
            }
            ScreenStatePayload payload = new ScreenStatePayload(pos, msg.url(), msg.loop(), msg.action(), msg.value());
            for (ChunkPos chunk : chunks) {
                PacketDistributor.sendToPlayersTrackingChunk(level, chunk, payload);
            }
        }
    }
}
