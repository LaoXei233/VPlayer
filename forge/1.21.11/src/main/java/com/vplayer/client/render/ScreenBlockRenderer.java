package com.vplayer.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlock;
import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import com.vplayer.client.media.VideoEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 屏幕渲染器：完整方块（显示器造型），正面画视频（无光照，夜里也亮）。
 * 边框只画在整块大屏的外缘（按格子在组内的位置判断），保证拼接无缝隙。
 *
 * 1.21.11 渲染管线改为 extractRenderState / submitCustomGeometry，
 * 所有四边形在 submit 里通过自定义几何批次提交。
 */
public final class ScreenBlockRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenRenderState> {

    private static final Identifier WHITE_TEXTURE =
            Identifier.fromNamespaceAndPath(VPlayerMod.MODID, "white");
    private static boolean whiteRegistered = false;

    private static final float Z_FRONT = 0.5f;
    private static final float Z_VIDEO = 0.5006f;

    // 固定颜色（与 1.21.1 版渲染配色一致），0-255 整数
    private static final int COLOR_BACK_R = 11, COLOR_BACK_G = 11, COLOR_BACK_B = 13;
    private static final int COLOR_SIDE_R = 23, COLOR_SIDE_G = 23, COLOR_SIDE_B = 26;
    private static final int COLOR_SCREEN_R = 9, COLOR_SCREEN_G = 13, COLOR_SCREEN_B = 22;

    public ScreenBlockRenderer(BlockEntityRendererProvider.Context context) {}

    /** 纯白纹理（固定颜色的几何用它绘制，不能用视频纹理当底色）。 */
    public static void ensureWhiteTexture() {
        if (whiteRegistered) {
            return;
        }
        try {
            NativeImage img = new NativeImage(2, 2, false);
            img.fillRect(0, 0, 2, 2, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE,
                    new DynamicTexture(() -> "VPlayer White", img));
            whiteRegistered = true;
        } catch (Throwable t) {
            VPlayerMod.LOGGER.error("[VPlayer] 白色纹理注册失败", t);
        }
    }

    @Override
    public ScreenRenderState createRenderState() {
        return new ScreenRenderState();
    }

    @Override
    public void extractRenderState(ScreenBlockEntity be, ScreenRenderState state, float partialTick,
                                   Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTick, cameraPos, crumbling);
        state.valid = false;
        if (be.getLevel() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(ScreenBlock.FACING);
        ScreenGrid.Group group;
        try {
            group = ScreenGrid.groupFor(be.getLevel(), pos);
        } catch (Throwable t) {
            return;
        }
        Identifier videoTex = VideoEngine.get().getTexture(group.master());

        state.valid = true;
        state.facing = facing;
        state.hasVideo = videoTex != null;
        state.videoTexture = videoTex;

        int cu = group.cellU();
        int cv = group.cellV();
        ScreenGrid.VideoRect video = group.video();
        state.cellU = cu;
        state.cellV = cv;
        state.videoOffsetU = video.offsetU();
        state.videoOffsetV = video.offsetV();
        state.videoWidth = Math.max(1, video.width());
        state.videoHeight = Math.max(1, video.height());

        int vu = cu - video.offsetU();
        int vv = cv - video.offsetV();
        state.showsVideo = state.hasVideo
                && vu >= 0 && vv >= 0 && vu < video.width() && vv < video.height();

        // 侧面/边框按"实际有没有相邻屏幕方块"判断：L 形、缺口、内部拐角也要正确封边
        Direction uAxis = ScreenGrid.uAxis(facing);
        Direction vAxis = ScreenGrid.vAxis(facing);
        state.hasUminus = group.cells().contains(pos.relative(uAxis.getOpposite()));
        state.hasUplus = group.cells().contains(pos.relative(uAxis));
        state.hasVminus = group.cells().contains(pos.relative(vAxis.getOpposite()));
        state.hasVplus = group.cells().contains(pos.relative(vAxis));
    }

    @Override
    public void submit(ScreenRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        if (!state.valid) {
            return;
        }
        ensureWhiteTexture();
        if (!whiteRegistered) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(facingRotation(state.facing));

        // 固定颜色部分：纯白纹理 + 顶点色
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(WHITE_TEXTURE),
                (pose, vc) -> drawSolid(pose.pose(), vc, state));

        if (state.showsVideo && state.videoTexture != null) {
            // 每个方块只画自己那一块画面（UV 按格子在画面区域中的位置切分）
            Identifier tex = state.videoTexture;
            collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(tex),
                    (pose, vc) -> drawVideo(pose.pose(), vc, state));
        }
        poseStack.popPose();
    }

    /** 背板、侧面、关屏面（固定颜色）。 */
    private static void drawSolid(Matrix4f m, VertexConsumer vc, ScreenRenderState state) {
        // 背板
        xyQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
                COLOR_BACK_R, COLOR_BACK_G, COLOR_BACK_B);
        // 侧面：只画在真正没有相邻屏幕方块的一侧（L 形/缺口的内拐角也能封住，不会透出里面）
        if (!state.hasUminus) {
            yzQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
                    COLOR_SIDE_R, COLOR_SIDE_G, COLOR_SIDE_B);
        }
        if (!state.hasUplus) {
            yzQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
                    COLOR_SIDE_R, COLOR_SIDE_G, COLOR_SIDE_B);
        }
        if (!state.hasVminus) {
            xzQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
                    COLOR_SIDE_R, COLOR_SIDE_G, COLOR_SIDE_B);
        }
        if (!state.hasVplus) {
            xzQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
                    COLOR_SIDE_R, COLOR_SIDE_G, COLOR_SIDE_B);
        }
        if (!state.showsVideo) {
            // 关屏 / 不在画面区域：深色屏面（和视频面同一 Z，交界处不会出现台阶亮线）
            xyQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, Z_VIDEO,
                    COLOR_SCREEN_R, COLOR_SCREEN_G, COLOR_SCREEN_B);
        }
    }

    private static void drawVideo(Matrix4f m, VertexConsumer vc, ScreenRenderState state) {
        int vu = state.cellU - state.videoOffsetU;
        int vv = state.cellV - state.videoOffsetV;
        float u0 = (float) vu / state.videoWidth;
        float u1 = (float) (vu + 1) / state.videoWidth;
        float v0 = (float) vv / state.videoHeight;
        float v1 = (float) (vv + 1) / state.videoHeight;
        videoQuad(vc, m, -0.5f, -0.5f, 0.5f, 0.5f, Z_VIDEO,
                u0, u1, v0, v1,
                255, 255, 255, 255);
    }

    /** 将默认朝向（正面 +Z、上方 +Y）旋转到指定朝向。 */
    private static Quaternionf facingRotation(Direction facing) {
        return switch (facing) {
            case SOUTH -> new Quaternionf();
            case NORTH -> new Quaternionf().rotateY((float) Math.PI);
            case EAST -> new Quaternionf().rotateY((float) (Math.PI / 2));
            case WEST -> new Quaternionf().rotateY((float) (-Math.PI / 2));
            case UP -> new Quaternionf().rotateX((float) (-Math.PI / 2));
            case DOWN -> new Quaternionf().rotateX((float) (Math.PI / 2));
        };
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, float x, float y, float z,
                               float u, float v, int r, int g, int b, int a) {
        vc.addVertex(m, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(0, 0, 1);
    }

    private static void xyQuad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z,
                               int r, int g, int b) {
        vertex(vc, m, x0, y0, z, 0, 0, r, g, b, 255);
        vertex(vc, m, x1, y0, z, 1, 0, r, g, b, 255);
        vertex(vc, m, x1, y1, z, 1, 1, r, g, b, 255);
        vertex(vc, m, x0, y1, z, 0, 1, r, g, b, 255);
    }

    private static void xzQuad(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1, float y,
                               int r, int g, int b) {
        vertex(vc, m, x0, y, z0, 0, 0, r, g, b, 255);
        vertex(vc, m, x1, y, z0, 1, 0, r, g, b, 255);
        vertex(vc, m, x1, y, z1, 1, 1, r, g, b, 255);
        vertex(vc, m, x0, y, z1, 0, 1, r, g, b, 255);
    }

    private static void yzQuad(VertexConsumer vc, Matrix4f m, float y0, float z0, float y1, float z1, float x,
                               int r, int g, int b) {
        vertex(vc, m, x, y0, z0, 0, 0, r, g, b, 255);
        vertex(vc, m, x, y1, z0, 1, 0, r, g, b, 255);
        vertex(vc, m, x, y1, z1, 1, 1, r, g, b, 255);
        vertex(vc, m, x, y0, z1, 0, 1, r, g, b, 255);
    }

    private static void videoQuad(VertexConsumer vc, Matrix4f m,
                                  float x0, float y0, float x1, float y1, float z,
                                  float u0, float u1, float v0, float v1,
                                  int r, int g, int b, int a) {
        // v 轴翻转：图像第一行显示在屏幕顶部（v0 = 视频底部，v1 = 视频顶部）
        vertex(vc, m, x0, y0, z, u0, 1 - v0, r, g, b, a);
        vertex(vc, m, x1, y0, z, u1, 1 - v0, r, g, b, a);
        vertex(vc, m, x1, y1, z, u1, 1 - v1, r, g, b, a);
        vertex(vc, m, x0, y1, z, u0, 1 - v1, r, g, b, a);
    }
}
