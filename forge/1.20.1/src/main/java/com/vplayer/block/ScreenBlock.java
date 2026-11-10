package com.vplayer.block;

import com.vplayer.client.screen.ScreenScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 屏幕方块：完整方块造型的显示器，正面显示视频。
 * 多个相同朝向的方块相邻时自动合并为一块大屏。
 */
public class ScreenBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public ScreenBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 若旁边已有屏幕方块，继承其朝向，保证手放的整块大屏朝向统一（合并为一块）
        for (Direction d : Direction.values()) {
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(d));
            if (neighbor.getBlock() instanceof ScreenBlock) {
                return this.defaultBlockState().setValue(FACING, neighbor.getValue(FACING));
            }
        }
        return this.defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            ScreenScreen.open(pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity be && be.getUrl().isEmpty()) {
            Direction facing = state.getValue(FACING);
            Direction u = ScreenGrid.uAxis(facing);
            Direction v = ScreenGrid.vAxis(facing);
            for (Direction d : new Direction[]{u, u.getOpposite(), v, v.getOpposite()}) {
                BlockPos next = pos.relative(d);
                BlockState ns = level.getBlockState(next);
                if (ns.getBlock() instanceof ScreenBlock && ns.getValue(FACING) == facing) {
                    if (level.getBlockEntity(next) instanceof ScreenBlockEntity other && !other.getUrl().isEmpty()) {
                        be.setUrl(other.getUrl());
                        be.setLoop(other.isLoop());
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (level.isClientSide) {
            return;
        }
        // 结构变化时通知客户端重建合并信息
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }
}