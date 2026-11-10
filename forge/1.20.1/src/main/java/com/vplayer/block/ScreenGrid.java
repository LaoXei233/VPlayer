package com.vplayer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多屏合并：同朝向相邻方块合并为一个大屏，视频画面跨方块连续显示。
 * 支持任意连通形状（2x2、L 形、带镂空的口字形等），按外接矩形统一分块。
 */
public final class ScreenGrid {

    private static final int MAX_CELLS = 1024;

    /** 面板平面上的 U 轴（渲染器局部 +X 方向在世界中的朝向）。 */
    public static Direction uAxis(Direction facing) {
        return switch (facing) {
            case SOUTH -> Direction.EAST;
            case NORTH -> Direction.WEST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            case UP -> Direction.EAST;
            case DOWN -> Direction.EAST;
        };
    }

    /** 面板平面上的 V 轴（渲染器局部 +Y 方向在世界中的朝向）。 */
    public static Direction vAxis(Direction facing) {
        return switch (facing) {
            case UP -> Direction.NORTH;
            case DOWN -> Direction.SOUTH;
            default -> Direction.UP;
        };
    }

    public record Group(BlockPos master, int width, int height, int cellU, int cellV,
                        VideoRect video, boolean rectangle, Set<BlockPos> cells) {}

    /**
     * 画面实际显示的区域（相对整组外接矩形的格子坐标）。
     * 外接矩形边缘那些"填充不足"的零散方块（比如多放的一格）不算进画面区域，
     * 这样多出一格不会把整幅画面拉长；画面区域内部的缺口则是正常挖空（不显示画面）。
     */
    public record VideoRect(int offsetU, int offsetV, int width, int height) {}

    public static Group groupFor(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScreenBlock)) {
            return single(pos);
        }
        Direction facing = state.getValue(ScreenBlock.FACING);
        Set<BlockPos> cells = collect(level, pos, facing);
        return compute(pos, facing, cells);
    }

    public static BlockPos masterFor(BlockGetter level, BlockPos pos) {
        return groupFor(level, pos).master();
    }

    private static Set<BlockPos> collect(BlockGetter level, BlockPos pos, Direction facing) {
        Set<BlockPos> cells = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        cells.add(pos);
        queue.add(pos);
        Direction u = uAxis(facing);
        Direction v = vAxis(facing);
        Direction[] neighbors = {u, u.getOpposite(), v, v.getOpposite()};
        while (!queue.isEmpty() && cells.size() < MAX_CELLS) {
            BlockPos cur = queue.poll();
            for (Direction d : neighbors) {
                BlockPos next = cur.relative(d);
                if (cells.contains(next)) {
                    continue;
                }
                BlockState ns = level.getBlockState(next);
                if (ns.getBlock() instanceof ScreenBlock && ns.getValue(ScreenBlock.FACING) == facing) {
                    cells.add(next);
                    queue.add(next);
                }
            }
        }
        return cells;
    }

    /** 纯算法部分：给定朝向与同组方块集合，计算组合信息（不依赖世界，便于单元测试）。 */
    public static Group compute(BlockPos pos, Direction facing, Set<BlockPos> cells) {
        if (!cells.contains(pos)) {
            return single(pos);
        }
        Direction u = uAxis(facing);
        Direction v = vAxis(facing);
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (BlockPos cell : cells) {
            int pu = project(cell, u);
            int pv = project(cell, v);
            minU = Math.min(minU, pu);
            maxU = Math.max(maxU, pu);
            minV = Math.min(minV, pv);
            maxV = Math.max(maxV, pv);
        }
        int width = maxU - minU + 1;
        int height = maxV - minV + 1;
        boolean rectangle = width * height == cells.size();
        BlockPos master = null;
        for (BlockPos cell : cells) {
            if (project(cell, u) == minU && project(cell, v) == minV) {
                master = cell;
                break;
            }
        }
        if (master == null) {
            master = cells.iterator().next();
        }
        return new Group(master, width, height,
                project(pos, u) - minU, project(pos, v) - minV,
                effectiveVideoRect(cells, u, v, minU, minV, maxU, maxV),
                rectangle, java.util.Collections.unmodifiableSet(cells));
    }

    /**
     * 计算画面区域：从外接矩形开始，只剔除边界上"孤零零一格"的方块
     * （基本是手滑多放的一格，它不该把整幅画面拉长）。
     * 整行/整列的延伸（例如 L 形大屏的横臂/竖臂）不会被剔除，保证 L 形上画面照样铺满；
     * 画面区域内部的缺口也不会被剔除——那种格子只是不显示画面（挖空），不会改变画面比例。
     */
    private static VideoRect effectiveVideoRect(Set<BlockPos> cells, Direction u, Direction v,
                                                int minU, int minV, int maxU, int maxV) {
        int loU = minU;
        int hiU = maxU;
        int loV = minV;
        int hiV = maxV;
        while (true) {
            int w = hiU - loU + 1;
            int h = hiV - loV + 1;
            if (w < 2 || h < 2) {
                break;
            }
            int bottomCount = 0;
            int topCount = 0;
            int leftCount = 0;
            int rightCount = 0;
            for (BlockPos cell : cells) {
                int pu = project(cell, u);
                int pv = project(cell, v);
                if (pu < loU || pu > hiU || pv < loV || pv > hiV) {
                    continue;
                }
                if (pv == loV) {
                    bottomCount++;
                }
                if (pv == hiV) {
                    topCount++;
                }
                if (pu == loU) {
                    leftCount++;
                }
                if (pu == hiU) {
                    rightCount++;
                }
            }
            boolean trimmed = false;
            // 只有"边界这一行/这一列上就孤零零一个方块"才剔除（多放一格）；成排的形状不裁剪
            if (bottomCount == 1) {
                loV++;
                trimmed = true;
            } else if (topCount == 1) {
                hiV--;
                trimmed = true;
            }
            if (leftCount == 1) {
                loU++;
                trimmed = true;
            } else if (rightCount == 1) {
                hiU--;
                trimmed = true;
            }
            if (!trimmed) {
                break;
            }
        }
        if (hiU < loU || hiV < loV) {
            return new VideoRect(0, 0, 1, 1);
        }
        return new VideoRect(loU - minU, loV - minV, hiU - loU + 1, hiV - loV + 1);
    }

    private static int project(BlockPos pos, Direction axis) {
        // 必须严格按 axis 的正方向投影（uAxis/vAxis 就是渲染器局部 +X/+Y 的世界朝向）。
        // 不能为了“让主屏在最小坐标处”再取反：一旦取反，NORTH/EAST/WEST 的 U 和 UP/DOWN 的 V
        // 会与渲染器局部坐标镜像，表现为大屏上视频被分成错位的竖条/横条（左右接不上、重影）。
        return axis.getStepX() * pos.getX() + axis.getStepY() * pos.getY() + axis.getStepZ() * pos.getZ();
    }

    private static Group single(BlockPos pos) {
        return new Group(pos, 1, 1, 0, 0, new VideoRect(0, 0, 1, 1), false, Set.of(pos));
    }

    private ScreenGrid() {}
}
