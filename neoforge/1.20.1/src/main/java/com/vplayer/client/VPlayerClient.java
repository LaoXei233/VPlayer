package com.vplayer.client;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlock;
import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import com.vplayer.client.media.VideoEngine;
import com.vplayer.client.render.ScreenBlockRenderer;
import com.vplayer.init.ModBlockEntities;
import com.vplayer.init.ModBlocks;
import com.vplayer.init.ModItems;
import com.vplayer.net.ScreenControlMsg;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 客户端入口：注册渲染器与每帧更新。
 */
@Mod.EventBusSubscriber(modid = VPlayerMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VPlayerClient {

    public static void init(IEventBus modEventBus) {
        // 通过注解自动订阅事件
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.SCREEN.get(), ScreenBlockRenderer::new);
        VPlayerMod.LOGGER.info("[VPlayer] 屏幕渲染器已注册");
    }

    @Mod.EventBusSubscriber(modid = VPlayerMod.MODID, value = Dist.CLIENT)
    public static final class GameEvents {

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            VideoEngine.get().tick();
            SelfTest.tick(mc);
        }

        @SubscribeEvent
        public static void onLoggingOut(PlayerEvent.PlayerLoggedOutEvent event) {
            VideoEngine.get().stopAll();
        }
    }

    /**
     * 游戏内自动自检（仅当游戏目录存在 vplayer-selftest.txt 时生效，正常游玩无影响）。
     */
    static final class SelfTest {

        // 覆盖 4 个水平朝向 + DOWN：U 轴镜像（NORTH/EAST/WEST）和 V 轴镜像（UP/DOWN）都会被检查到
        private static final Direction[] TEST_FACINGS = {
                Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        /** 最后两项是特殊形状测试：不规则屏幕（多一格+挖一格）、以及 L 形屏幕。 */
        private static final int TEST_STEPS = TEST_FACINGS.length + 2;
        private static final int PANEL_W = 11;
        private static final int PANEL_H = 6;
        /** 相机到屏幕中心的距离（格），保证整块大屏完整入镜。 */
        private static final double VIEW_DIST = 7.0;
        /** 高空测试区：避开地形和上一轮测试留下的屏幕。 */
        private static final double TEST_Y = 200.0;
        private static final double EYE_HEIGHT = 1.62;
        private static final int CLEAR_RADIUS = 22;
        private static int state = 0;
        private static int testIndex = 0;
        private static int waitTicks = 0;
        private static int idleTicks = 0;
        private static long startTime = 0;
        private static String url = "";
        private static BlockPos panelPos = null;
        private static Vec3 cameraEye = null;
        private static double pauseCheckPos = 0;
        private static boolean pauseResumeOk = true;
        private static boolean lootOk = false;

        private static Direction testFacing() {
            return testIndex < TEST_FACINGS.length ? TEST_FACINGS[testIndex] : Direction.SOUTH;
        }

        private static String testName() {
            if (testIndex < TEST_FACINGS.length) {
                return TEST_FACINGS[testIndex].getName();
            }
            return testIndex == TEST_FACINGS.length ? "irregular" : "L";
        }

        static void tick(Minecraft mc) {
            if (state == 0) {
                File file = new File(mc.gameDirectory, "vplayer-selftest.txt");
                if (file.isFile()) {
                    try {
                        url = Files.readString(file.toPath(), StandardCharsets.UTF_8)
                                .replace("\uFEFF", "").trim();
                    } catch (Exception e) {
                        VPlayerMod.LOGGER.error("[VPlayer] 自检读取失败", e);
                    }
                    state = 1;
                    startTime = net.minecraft.Util.getMillis();
                    // 测试实例是后台窗口，可能失去焦点而自动暂停（弹出游戏菜单盖住截图）——自检时关掉
                    mc.options.pauseOnLostFocus = false;
                    VPlayerMod.LOGGER.info("[VPlayer] 自检：等待进入世界...");
                }
                return;
            }
            if (state == 1) {
                if (mc.level == null || mc.player == null) {
                    if (net.minecraft.Util.getMillis() - startTime > 180000L) {
                        finish(mc, false, "180 秒内未进入世界");
                    }
                    return;
                }
                placePanel(mc);
                return;
            }
            // state == 4：未播放状态截图（观察屏幕外观）
            if (state == 4) {
                holdCamera(mc);
                dismissScreen(mc);
                idleTicks++;
                if (idleTicks == 40) {
                    // 提前几帧隐藏 GUI，截图才不会拍到物品栏/聊天框
                    mc.gui.getChat().clearMessages(true);
                    mc.options.hideGui = true;
                }
                if (idleTicks >= 45) {
                    try {
                        File idleShot = new File(mc.gameDirectory, "selftest-idle-" + testName() + ".png");
                        captureScreenshot(mc, idleShot);
                        VPlayerMod.LOGGER.info("[VPlayer] 自检：未播放状态截图已保存 {}", idleShot);
                    } catch (Exception ignored) {
                    }
                    mc.options.hideGui = false;
                    VPlayerMod.LOGGER.info("[VPlayer] 自检：未播放状态截图完成");
                    VideoEngine.get().apply(panelPos, url, false,
                            ScreenControlMsg.ACTION_PLAY, 0f);
                    state = 2;
                    startTime = net.minecraft.Util.getMillis();
                    waitTicks = 0;
                }
                return;
            }
            // state == 5：L 形屏幕的内拐角特写（检查边缘/封边材质）
            if (state == 5) {
                holdCamera(mc);
                dismissScreen(mc);
                waitTicks++;
                if (waitTicks == 1) {
                    mc.gui.getChat().clearMessages(true);
                    mc.options.hideGui = true;
                }
                if (waitTicks >= 20) {
                    try {
                        File shot = new File(mc.gameDirectory, "selftest-shot-L-corner.png");
                        captureScreenshot(mc, shot);
                        VPlayerMod.LOGGER.info("[VPlayer] 自检：L 形内拐角截图已保存 {}", shot);
                    } catch (Exception ignored) {
                    }
                    mc.options.hideGui = false;
                    advance(mc);
                }
                return;
            }
            // state == 6：挖掘掉落测试（服务端真实跑一次方块掉落，检查是否掉回屏幕物品）
            if (state == 6) {
                holdCamera(mc);
                dismissScreen(mc);
                waitTicks++;
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    if (waitTicks > 100) {
                        finish(mc, false, "挖掘掉落测试：拿不到服务端");
                    }
                    return;
                }
                ServerLevel serverLevel = server.overworld();
                BlockPos testPos = BlockPos.containing(cameraEye).offset(0, -4, 0);
                if (waitTicks == 2) {
                    serverLevel.setBlockAndUpdate(testPos, Blocks.AIR.defaultBlockState());
                    serverLevel.setBlockAndUpdate(testPos,
                            ModBlocks.SCREEN.get().defaultBlockState());
                }
                if (waitTicks == 5) {
                    java.util.List<ServerPlayer> players = server.getPlayerList().getPlayers();
                    ServerPlayer sp = players.isEmpty() ? null : players.get(0);
                    Block.dropResources(ModBlocks.SCREEN.get().defaultBlockState(), serverLevel,
                            testPos, null, sp, net.minecraft.world.item.ItemStack.EMPTY);
                }
                if (waitTicks == 12) {
                    java.util.List<ItemEntity> drops = serverLevel.getEntitiesOfClass(ItemEntity.class,
                            new AABB(testPos).inflate(6.0),
                            e -> e.getItem().is(ModItems.SCREEN_ITEM.get()));
                    lootOk = !drops.isEmpty();
                    VPlayerMod.LOGGER.info("[VPlayer] 自检：挖掘掉落 {} 个 掉落测试={}", drops.size(), lootOk);
                    serverLevel.setBlockAndUpdate(testPos, Blocks.AIR.defaultBlockState());
                    // 顺手把屏幕物品放到快捷栏第一格（手里也拿着），拍一张检查 3D 图标
                    mc.player.getInventory().setItem(0, new ItemStack(ModItems.SCREEN_ITEM.get()));
                    mc.player.getInventory().selected = 0;
                    mc.setScreen(null);
                    state = 7;
                    waitTicks = 0;
                }
                return;
            }
            // state == 7：物品 3D 图标截图（快捷栏 + 手持）
            if (state == 7) {
                waitTicks++;
                if (waitTicks == 10) {
                    try {
                        File shot = new File(mc.gameDirectory, "selftest-shot-item.png");
                        captureScreenshot(mc, shot);
                        VPlayerMod.LOGGER.info("[VPlayer] 自检：物品图标截图已保存 {}", shot);
                    } catch (Exception ignored) {
                    }
                    boolean ok = pauseResumeOk && lootOk;
                    finish(mc, ok, ok
                            ? "OK 全部朝向 + 不规则屏幕 + L 形屏幕 + 暂停续播 + 挖掘掉落 + 图标"
                            : "FAIL 暂停续播或挖掘掉落异常");
                }
                return;
            }
            // state == 2：等待画面并截图
            if (mc.level == null || panelPos == null) {
                return;
            }
            holdCamera(mc);
            dismissScreen(mc);
            BlockPos master = ScreenGrid.masterFor(mc.level, panelPos);
            VideoEngine engine = VideoEngine.get();
            if (engine.getTexture(master) == null) {
                String error = engine.getError(master);
                if (error != null && engine.getState(master) == VideoEngine.STATE_STOPPED) {
                    finish(mc, false, error);
                    return;
                }
                if (net.minecraft.Util.getMillis() - startTime > 45000L) {
                    finish(mc, false, "45 秒未出现画面");
                }
                return;
            }
            waitTicks++;
            if (waitTicks == 5) {
                VideoEngine.get().apply(panelPos, url, false,
                        ScreenControlMsg.ACTION_SPEED, 2.0f);
                VPlayerMod.LOGGER.info("[VPlayer] 自检：切换 2x，当前位置={}", engine.getPosition(master));
            }
            if (waitTicks == 25) {
                VPlayerMod.LOGGER.info("[VPlayer] 自检：2x 播放约 1 秒后位置={}", engine.getPosition(master));
            }
            if (waitTicks == 30) {
                VideoEngine.get().apply(panelPos, url, false,
                        ScreenControlMsg.ACTION_SEEK, 10.0f);
                VPlayerMod.LOGGER.info("[VPlayer] 自检：跳转到 10 秒");
            }
            if (waitTicks == 45) {
                VPlayerMod.LOGGER.info("[VPlayer] 自检：跳转后位置={} 总时长={}",
                        engine.getPosition(master), engine.getDuration(master));
            }
            // 暂停 -> 继续播放测试（只测第一项）：应原地续播，不重新加载、不快进跳变
            if (testIndex == 0 && waitTicks == 50) {
                VideoEngine.get().apply(panelPos, url, false,
                        ScreenControlMsg.ACTION_PAUSE, 0f);
                pauseCheckPos = engine.getPosition(master);
                VPlayerMod.LOGGER.info("[VPlayer] 自检：已暂停 状态={} 位置={}",
                        engine.getState(master), pauseCheckPos);
            }
            if (testIndex == 0 && waitTicks == 90) {
                boolean frozen = Math.abs(engine.getPosition(master) - pauseCheckPos) < 0.25;
                boolean pausedOk = engine.getState(master) == VideoEngine.STATE_PAUSED;
                pauseResumeOk = frozen && pausedOk;
                VPlayerMod.LOGGER.info("[VPlayer] 自检：暂停 2 秒后 状态={} 位置={} 冻结={}",
                        engine.getState(master), engine.getPosition(master), frozen);
                VideoEngine.get().apply(panelPos, url, false,
                        ScreenControlMsg.ACTION_PLAY, 0f);
                VPlayerMod.LOGGER.info("[VPlayer] 自检：点击播放（应原地续播、不重新加载）");
            }
            if (testIndex == 0 && waitTicks == 96) {
                double delta = engine.getPosition(master) - pauseCheckPos;
                boolean noJump = delta >= -0.1 && delta < 1.5;
                pauseResumeOk = pauseResumeOk && noJump;
                VPlayerMod.LOGGER.info("[VPlayer] 自检：恢复瞬间 位置增量={} 无快进跳变={}", delta, noJump);
            }
            if (testIndex == 0 && waitTicks == 130) {
                double p = engine.getPosition(master);
                boolean resumed = engine.getState(master) == VideoEngine.STATE_PLAYING
                        && p > pauseCheckPos && p < pauseCheckPos + 10.0;
                pauseResumeOk = pauseResumeOk && resumed;
                VPlayerMod.LOGGER.info("[VPlayer] 自检：恢复后 状态={} 位置={} 原地续播={}",
                        engine.getState(master), p, resumed);
            }
            if (waitTicks == 1) {
                mc.gui.getChat().clearMessages(true);
                mc.options.hideGui = true;
                return;
            }
            if (waitTicks < (testIndex == 0 ? 140 : 60)) {
                return;
            }
            mc.options.hideGui = false;
            try {
                File shot = new File(mc.gameDirectory, "selftest-shot-" + testName() + ".png");
                captureScreenshot(mc, shot);
                VPlayerMod.LOGGER.info("[VPlayer] 自检截图已保存: {} (测试={})", shot, testName());
                if ("L".equals(testName())) {
                    // L 形额外拍一张内拐角特写，检查边缘/封边
                    setCornerView(mc);
                    state = 5;
                    waitTicks = 0;
                    return;
                }
                advance(mc);
            } catch (Exception e) {
                finish(mc, false, "截图失败: " + e);
            }
        }

        /** 进入下一项测试（最后一项完成后进入挖掘掉落测试）。 */
        private static void advance(Minecraft mc) {
            testIndex++;
            waitTicks = 0;
            if (testIndex < TEST_STEPS) {
                state = 1;
                placePanel(mc);
            } else {
                state = 6;
            }
        }

        private static void placePanel(Minecraft mc) {
            try {
                Direction facing = testFacing();
                boolean irregular = testIndex == TEST_FACINGS.length;
                boolean lShape = testIndex == TEST_FACINGS.length + 1;
                Direction u = ScreenGrid.uAxis(facing);
                Direction v = ScreenGrid.vAxis(facing);

                // 相机固定在 y=200 的高空测试区，正对屏幕中心观察（便于人工核对画面是否错位/重影）
                if (cameraEye == null) {
                    Vec3 eye = mc.player.getEyePosition();
                    cameraEye = new Vec3(eye.x, TEST_Y, eye.z);
                    mc.player.setNoGravity(true);
                    mc.player.setDeltaMovement(Vec3.ZERO);
                    mc.player.setPos(cameraEye.x, cameraEye.y - EYE_HEIGHT, cameraEye.z);
                }
                // 清掉范围内上一轮留下的屏幕方块，避免遮挡或合并串味
                clearScreens(mc, BlockPos.containing(cameraEye), CLEAR_RADIUS);
                // 停掉上一项测试残留的播放会话，保证本项的"未播放"截图是干净的
                VideoEngine.get().stopAll();

                Vec3 f = Vec3.atLowerCornerOf(facing.getNormal());
                Vec3 uu = Vec3.atLowerCornerOf(u.getNormal());
                Vec3 vv = Vec3.atLowerCornerOf(v.getNormal());
                Vec3 center = cameraEye.subtract(f.scale(VIEW_DIST));
                Vec3 baseCenter = center
                        .subtract(uu.scale((PANEL_W - 1) / 2.0))
                        .subtract(vv.scale((PANEL_H - 1) / 2.0));
                BlockPos base = BlockPos.containing(baseCenter);

                // 清空屏幕正面的视线通道，保证截图不被方块遮挡
                for (int t = 1; t <= 5; t++) {
                    for (int a = -1; a <= PANEL_W; a++) {
                        for (int b = -1; b <= PANEL_H; b++) {
                            BlockPos p = base.relative(u, a).relative(v, b).relative(facing, t);
                            if (!mc.level.getBlockState(p).isAir()) {
                                mc.level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                            }
                        }
                    }
                }
                for (int a = 0; a < PANEL_W; a++) {
                    for (int b = 0; b < PANEL_H; b++) {
                        BlockPos p = base.relative(u, a).relative(v, b);
                        mc.level.setBlockAndUpdate(p, ModBlocks.SCREEN.get().defaultBlockState()
                                .setValue(ScreenBlock.FACING, facing));
                    }
                }
                if (irregular) {
                    // 多放一格（右侧凸出一块）+ 挖掉中间一格：验证画面不悬空、也不被多出的格子拉长
                    mc.level.setBlockAndUpdate(base.relative(u, PANEL_W).relative(v, 0),
                            ModBlocks.SCREEN.get().defaultBlockState().setValue(ScreenBlock.FACING, facing));
                    mc.level.setBlockAndUpdate(base.relative(u, 5).relative(v, 2),
                            Blocks.AIR.defaultBlockState());
                } else if (lShape) {
                    // L 形：挖掉右上角 5×3，验证画面照样铺满、内拐角边缘能正确封边
                    for (int a = 6; a < PANEL_W; a++) {
                        for (int b = 3; b < PANEL_H; b++) {
                            mc.level.setBlockAndUpdate(base.relative(u, a).relative(v, b),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                panelPos = base;
                Vec3 target = Vec3.atCenterOf(base)
                        .add(uu.scale((PANEL_W - 1) / 2.0))
                        .add(vv.scale((PANEL_H - 1) / 2.0));
                setLook(mc, cameraEye, target);

                // 记录合并信息：基准格应是 (0,0)，远角格应是 (W-1,H-1)，否则合并/坐标系有错
                ScreenGrid.Group group = ScreenGrid.groupFor(mc.level, base);
                BlockPos farCell = base.relative(u, PANEL_W - 1).relative(v, PANEL_H - 1);
                ScreenGrid.Group farGroup = ScreenGrid.groupFor(mc.level, farCell);
                VPlayerMod.LOGGER.info("[VPlayer] 自检[{}]: 眼位={} 基准={} 组={}x{} master={} 基准格=({},{}) 远角格=({},{}) 画面区域={}x{}@({},{})",
                        testName(), cameraEye, base, group.width(), group.height(), group.master(),
                        group.cellU(), group.cellV(), farGroup.cellU(), farGroup.cellV(),
                        group.video().width(), group.video().height(),
                        group.video().offsetU(), group.video().offsetV());
                idleTicks = 0;
                state = 4;
            } catch (Exception e) {
                VPlayerMod.LOGGER.error("[VPlayer] 自检摆屏失败", e);
                finish(mc, false, "摆屏失败: " + e);
            }
        }

        private static void setLook(Minecraft mc, Vec3 eye, Vec3 target) {
            double dx = target.x - eye.x;
            double dy = target.y - eye.y;
            double dz = target.z - eye.z;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.setYHeadRot(yaw);
        }

        /** L 形测试：把相机移到内拐角斜前方，检查边缘/封边是否正常。 */
        private static void setCornerView(Minecraft mc) {
            Direction facing = testFacing();
            Vec3 f = Vec3.atLowerCornerOf(facing.getNormal());
            Vec3 uu = Vec3.atLowerCornerOf(ScreenGrid.uAxis(facing).getNormal());
            Vec3 vv = Vec3.atLowerCornerOf(ScreenGrid.vAxis(facing).getNormal());
            Vec3 notch = Vec3.atCenterOf(panelPos).add(uu.scale(8.0)).add(vv.scale(4.0));
            Vec3 eye = notch.add(f.scale(4.5)).add(uu.scale(3.5)).add(vv.scale(3.0));
            cameraEye = eye;
            mc.player.setNoGravity(true);
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setPos(eye.x, eye.y - EYE_HEIGHT, eye.z);
            setLook(mc, eye, notch);
        }

        /** 关掉可能弹出的菜单界面（失焦暂停等），保证截图只拍到游戏画面。 */
        private static void dismissScreen(Minecraft mc) {
            if (mc.screen != null) {
                mc.setScreen(null);
            }
        }

        /** 每 tick 保持相机悬停不动（防止重力/位置校正把镜头带偏）。 */
        private static void holdCamera(Minecraft mc) {
            if (cameraEye == null || mc.player == null) {
                return;
            }
            mc.player.setNoGravity(true);
            mc.player.setDeltaMovement(Vec3.ZERO);
            if (mc.player.getEyePosition().distanceToSqr(cameraEye) > 0.0004D) {
                mc.player.setPos(cameraEye.x, cameraEye.y - EYE_HEIGHT, cameraEye.z);
            }
        }

        private static void clearScreens(Minecraft mc, BlockPos center, int radius) {
            if (mc.level == null) {
                return;
            }
            for (BlockPos p : BlockPos.betweenClosed(
                    center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius))) {
                if (mc.level.getBlockState(p).getBlock() instanceof ScreenBlock) {
                    mc.level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                }
            }
        }

        /** 1.20.1 没有 Screenshot.takeScreenshot(RenderTarget)，手动从渲染目标读像素并写 PNG。 */
        private static void captureScreenshot(Minecraft mc, File file) throws Exception {
            var target = mc.getMainRenderTarget();
            int w = target.width;
            int h = target.height;
            NativeImage raw = new NativeImage(w, h, false);
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(target.getColorTextureId());
            raw.downloadTexture(0, false);
            NativeImage out = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setPixelRGBA(x, h - 1 - y, raw.getPixelRGBA(x, y));
                }
            }
            out.writeToFile(file);
            out.close();
            raw.close();
        }

        private static void finish(Minecraft mc, boolean ok, String message) {
            state = 3;
            VPlayerMod.LOGGER.info("[VPlayer] 自检结果: {} - {}", ok ? "成功" : "失败", message);
            try {
                Files.writeString(new File(mc.gameDirectory, "vplayer-selftest-result.txt").toPath(),
                        (ok ? "OK " : "FAIL ") + message, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            mc.stop();
        }

        private SelfTest() {}
    }

    private VPlayerClient() {}
}
