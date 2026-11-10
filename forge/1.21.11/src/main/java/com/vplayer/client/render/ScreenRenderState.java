package com.vplayer.client.render;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * 屏幕方块实体的渲染状态（1.21.11 起渲染拆分为 extract / submit 两阶段）。
 */
public class ScreenRenderState extends BlockEntityRenderState {

    public boolean valid = false;
    public Direction facing = Direction.SOUTH;

    public boolean hasVideo = false;
    public Identifier videoTexture = null;

    public int cellU = 0;
    public int cellV = 0;
    public int videoOffsetU = 0;
    public int videoOffsetV = 0;
    public int videoWidth = 1;
    public int videoHeight = 1;

    public boolean hasUminus = false;
    public boolean hasUplus = false;
    public boolean hasVminus = false;
    public boolean hasVplus = false;

    /** 本格是否显示画面（在画面区域内且有视频）。 */
    public boolean showsVideo = false;
}
