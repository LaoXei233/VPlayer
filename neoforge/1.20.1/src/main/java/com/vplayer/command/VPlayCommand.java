package com.vplayer.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import com.vplayer.net.ScreenControlMsg;
import com.vplayer.net.ScreenStateMsg;
import com.vplayer.net.VPlayerNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;

/**
 * /vplay <url> 或 /vplay stop —— 对附近屏幕执行播放控制。
 */
public final class VPlayCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vplay")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stop").executes(VPlayCommand::stop))
                .then(Commands.argument("url", StringArgumentType.greedyString())
                        .executes(VPlayCommand::play)));
    }

    private static int play(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String url = StringArgumentType.getString(ctx, "url").trim();
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = findNearestScreen(level, BlockPos.containing(source.getPosition()));
        if (pos == null) {
            source.sendFailure(Component.literal("附近 4 格内没有屏幕方块"));
            return 0;
        }
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity be) {
            be.setUrl(url);
            be.setLoop(false);
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            broadcast(level, pos, url, false, ScreenControlMsg.ACTION_PLAY);
        }
        source.sendSuccess(() -> Component.literal("已在屏幕 " + pos.toShortString() + " 播放: " + url), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = findNearestScreen(level, BlockPos.containing(source.getPosition()));
        if (pos == null) {
            source.sendFailure(Component.literal("附近 4 格内没有屏幕方块"));
            return 0;
        }
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity be) {
            broadcast(level, pos, be.getUrl(), be.isLoop(), ScreenControlMsg.ACTION_STOP);
        }
        source.sendSuccess(() -> Component.literal("已停止屏幕播放"), true);
        return 1;
    }

    private static void broadcast(ServerLevel level, BlockPos pos, String url, boolean loop, byte action) {
        ScreenGrid.Group group = ScreenGrid.groupFor(level, pos);
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos cell : group.cells()) {
            chunks.add(new ChunkPos(cell));
        }
        ScreenStateMsg payload = new ScreenStateMsg(pos, url, loop, action, 0f);
        for (ChunkPos chunk : chunks) {
            VPlayerNetwork.CHANNEL.send(
                    PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunk(chunk.x, chunk.z)),
                    payload);
        }
    }

    private static BlockPos findNearestScreen(ServerLevel level, BlockPos origin) {
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -4, -4), origin.offset(4, 4, 4))) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ScreenBlockEntity) {
                double dist = pos.distSqr(origin);
                if (dist < best) {
                    best = dist;
                    nearest = pos.immutable();
                }
            }
        }
        return nearest;
    }

    private VPlayCommand() {}
}