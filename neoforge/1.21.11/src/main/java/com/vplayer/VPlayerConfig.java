package com.vplayer;

/**
 * 模组运行参数（客户端本地生效）。
 */
public final class VPlayerConfig {

    /** 视频帧渲染的最大宽度，超出会自动缩放。 */
    public static final int MAX_VIDEO_WIDTH = 1280;

    /** 视频帧渲染的最大高度，超出会自动缩放。 */
    public static final int MAX_VIDEO_HEIGHT = 720;

    /** 默认音量（0.0 - 1.0）。 */
    public static final float DEFAULT_VOLUME = 1.0f;

    /** 每块屏幕可设置的声音传播范围上限（方块）。 */
    public static final int MAX_AUDIO_RANGE = 64;

    /** 每块屏幕的声音传播范围默认值（方块）。 */
    public static final int DEFAULT_AUDIO_RANGE = 40;

    /** 声音范围可选的档位（方块，上限为 MAX_AUDIO_RANGE）。 */
    public static final int[] AUDIO_RANGE_STEPS = {8, 16, 24, 32, 40, 48, 56, 64};

    private VPlayerConfig() {}
}
