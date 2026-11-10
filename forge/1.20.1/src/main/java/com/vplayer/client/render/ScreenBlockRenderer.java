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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 屏幕渲染器：完整方块（显示器造型），正面画视频（无光照，夜里也亮）。
 * 边框只画在整块大屏的外缘（按格子在组内的位置判断），保证拼接无缝隙。
 */
public final class ScreenBlockRenderer implements BlockEntityRenderer<ScreenBlockEntity> {

    private static final ResourceLocation WHITE_TEXTURE =
            new ResourceLocation(VPlayerMod.MODID, "white");
    private static boolean whiteRegistered = false;

    private static final float Z_VIDEO = 0.5006f;

    public ScreenBlockRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ScreenBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        Direction facing = be.getBlockState().getValue(ScreenBlock.FACING);
        ScreenGrid.Group group = ScreenGrid.groupFor(be.getLevel(), pos);
        registerWhiteTexture();

        VideoEngine engine = VideoEngine.get();
        ResourceLocation videoTex = engine.getTexture(group.master());
        boolean hasVideo = videoTex != null;

        int cu = group.cellU();
        int cv = group.cellV();
        ScreenGrid.VideoRect video = group.video();
        // 本格在"画面区域"中的位置；不在区域内的方块（凸出来的零散格）不显示画面
        int vu = cu - video.offsetU();
        int vv = cv - video.offsetV();
        boolean showsVideo = hasVideo
                && vu >= 0 && vv >= 0 && vu < video.width() && vv < video.height();

        // 侧面/边框按"实际有没有相邻屏幕方块"判断：L 形、缺口、内部拐角也要正确封边
        Direction uAxis = ScreenGrid.uAxis(facing);
        Direction vAxis = ScreenGrid.vAxis(facing);
        boolean hasUminus = group.cells().contains(pos.relative(uAxis.getOpposite()));
        boolean hasUplus = group.cells().contains(pos.relative(uAxis));
        boolean hasVminus = group.cells().contains(pos.relative(vAxis.getOpposite()));
        boolean hasVplus = group.cells().contains(pos.relative(vAxis));

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(facingRotation(facing));
        Matrix4f matrix = poseStack.last().pose();
        // 固定颜色的部分用纯白纹理画：不能用视频纹理当底色，否则会出现缩小/变暗的"鬼影画面"
        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityTranslucent(WHITE_TEXTURE));

        // 背板
        xyQuad(vc, matrix, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.045f, 0.045f, 0.05f, 1.0f);
        // 侧面：只画在真正没有相邻屏幕方块的一侧（L 形/缺口的内拐角也能封住，不会透出里面）
        if (!hasUminus) {
            yzQuad(vc, matrix, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.09f, 0.09f, 0.10f, 1.0f);
        }
        if (!hasUplus) {
            yzQuad(vc, matrix, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.09f, 0.09f, 0.10f, 1.0f);
        }
        if (!hasVminus) {
            xzQuad(vc, matrix, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.09f, 0.09f, 0.10f, 1.0f);
        }
        if (!hasVplus) {
            xzQuad(vc, matrix, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.09f, 0.09f, 0.10f, 1.0f);
        }

        if (showsVideo) {
            // 每个方块只画自己那一块画面（UV 按格子在画面区域中的位置切分）。
            // 画面被严格限制在方块正面：缺口处不会悬空出现画面，多出的零散方块也不会拉长画面。
            VertexConsumer vcVideo = bufferSource.getBuffer(RenderType.entityTranslucent(videoTex));
            videoQuad(vcVideo, matrix, -0.5f, -0.5f, 0.5f, 0.5f, Z_VIDEO,
                    (float) vu / video.width(), (float) (vu + 1) / video.width(),
                    (float) vv / video.height(), (float) (vv + 1) / video.height());
        } else {
            // 关屏 / 不在画面区域：深色屏面（和视频面同一 Z，交界处不会出现台阶亮线）
            xyQuad(vc, matrix, -0.5f, -0.5f, 0.5f, 0.5f, Z_VIDEO, 0.035f, 0.05f, 0.085f, 1.0f);
        }
        poseStack.popPose();
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

    private static void registerWhiteTexture() {
        if (whiteRegistered) {
            return;
        }
        NativeImage img = new NativeImage(2, 2, false);
        img.fillRect(0, 0, 2, 2, 0xFFFFFFFF);
        Minecraft.getInstance().getTextureManager().register(WHITE_TEXTURE, new DynamicTexture(img));
        whiteRegistered = true;
    }

    private static void xyQuad(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1, float z,
                               float r, float g, float b, float a) {
        vc.vertex(m, x0, y0, z).color(r, g, b, a).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y0, z).color(r, g, b, a).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y1, z).color(r, g, b, a).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x0, y1, z).color(r, g, b, a).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
    }

    private static void xzQuad(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1, float y,
                               float r, float g, float b, float a) {
        vc.vertex(m, x0, y, z0).color(r, g, b, a).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y, z0).color(r, g, b, a).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y, z1).color(r, g, b, a).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x0, y, z1).color(r, g, b, a).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
    }

    private static void yzQuad(VertexConsumer vc, Matrix4f m, float y0, float z0, float y1, float z1, float x,
                               float r, float g, float b, float a) {
        vc.vertex(m, x, y0, z0).color(r, g, b, a).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x, y1, z0).color(r, g, b, a).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x, y1, z1).color(r, g, b, a).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x, y0, z1).color(r, g, b, a).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
    }

    private static void videoQuad(VertexConsumer vc, Matrix4f m,
                                  float x0, float y0, float x1, float y1, float z,
                                  float u0, float u1, float v0, float v1) {
        // v 轴翻转：图像第一行显示在屏幕顶部（v0 = 视频底部，v1 = 视频顶部）
        // 3 倍亮度系数抵消光照贴图压暗
        vc.vertex(m, x0, y0, z).color(3.0f, 3.0f, 3.0f, 1).uv(u0, 1 - v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y0, z).color(3.0f, 3.0f, 3.0f, 1).uv(u1, 1 - v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x1, y1, z).color(3.0f, 3.0f, 3.0f, 1).uv(u1, 1 - v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
        vc.vertex(m, x0, y1, z).color(3.0f, 3.0f, 3.0f, 1).uv(u0, 1 - v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(0, 0, 1).endVertex();
    }
}