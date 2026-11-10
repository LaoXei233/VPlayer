package com.vplayer.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import com.vplayer.VPlayerConfig;
import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import com.vplayer.client.VPlayerNatives;
import com.vplayer.net.ScreenControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端播放引擎：每个大屏一个解码会话，负责解码、上传纹理与音频输出。
 */
public final class VideoEngine {

    public static final int STATE_STOPPED = 0;
    public static final int STATE_PLAYING = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_LOADING = 3;

    private static final VideoEngine INSTANCE = new VideoEngine();

    private final Map<BlockPos, Session> sessions = new LinkedHashMap<>();
    private int cleanupCounter = 0;

    public static VideoEngine get() {
        return INSTANCE;
    }

    /** 开发自检：绕过方块直接在某坐标启动一次播放。 */
    public void applyDebug(String url) {
        Session session = sessions.computeIfAbsent(BlockPos.ZERO, Session::new);
        session.play(url, false);
    }

    /** 处理服务端广播（pos 为被操作的屏幕方块，自动解析到组合主屏）。 */
    public void apply(BlockPos pos, String url, boolean loop, byte action, float value) {
        BlockPos key = resolveMaster(pos);
        if (key == null) {
            return;
        }
        if (action == ScreenControlPayload.ACTION_PLAY) {
            Session session = sessions.computeIfAbsent(key, Session::new);
            session.play(url, loop);
        } else {
            Session session = sessions.get(key);
            if (session != null) {
                if (action == ScreenControlPayload.ACTION_PAUSE) {
                    session.pause();
                } else if (action == ScreenControlPayload.ACTION_STOP) {
                    session.stop();
                } else if (action == ScreenControlPayload.ACTION_SEEK) {
                    session.seek(value);
                } else if (action == ScreenControlPayload.ACTION_SPEED) {
                    session.setSpeed(value);
                } else if (action == ScreenControlPayload.ACTION_RANGE) {
                    session.setAudioRange((int) value);
                }
            }
        }
    }

    /** 当前会话正在使用的地址（给界面回显用，避免地址框空掉后重贴地址导致重新加载）。 */
    public String getSourceUrl(BlockPos master) {
        Session session = master == null ? null : sessions.get(master);
        return session == null || session.url == null ? "" : session.url;
    }

    /** 区块加载时同步方块实体数据（不改变播放状态）。 */
    public void syncUrl(BlockPos pos, String url, boolean loop) {
        BlockPos key = resolveMaster(pos);
        Session session = key == null ? null : sessions.get(key);
        if (session != null) {
            session.setSource(url, loop);
        }
    }

    private BlockPos resolveMaster(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        return ScreenGrid.masterFor(mc.level, pos);
    }

    public int getState(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? STATE_STOPPED : session.state;
    }

    public float getVolume(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? VPlayerConfig.DEFAULT_VOLUME : session.userVolume;
    }

    public void setVolume(BlockPos master, float volume) {
        sessions.computeIfAbsent(master, Session::new).userVolume =
                Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
     * 距离衰减系数：范围内全音量，超出开始淡出，到最远距离完全无声。
     * 每个客户端按本地玩家与屏幕的距离独立计算，服务器多人各自正确。
     */
    private static float distanceGain(BlockPos screen, net.minecraft.world.phys.Vec3 eye, int range) {
        if (eye == null) {
            return 1.0f;
        }
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(screen).add(0, 0.5, 0);
        double distance = eye.distanceTo(center);
        double end = Math.max(1.0, Math.min(VPlayerConfig.MAX_AUDIO_RANGE, range));
        double start = end * 0.2;
        if (distance <= start) {
            return 1.0f;
        }
        if (distance >= end) {
            return 0.0f;
        }
        double t = (distance - start) / (end - start);
        // smoothstep：淡入淡出更自然
        t = t * t * (3.0 - 2.0 * t);
        return (float) (1.0 - t);
    }

    public ResourceLocation getTexture(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? null : session.texId;
    }

    public float getAspect(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? 16f / 9f : session.aspect;
    }

    /** 当前播放位置（秒）。 */
    public double getPosition(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? 0 : session.positionSec;
    }

    /** 视频总时长（秒），未知返回 0。 */
    public double getDuration(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? 0 : session.durationSec;
    }

    /** 当前播放倍速。 */
    public double getSpeed(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? 1.0 : session.speed;
    }

    /** 本屏幕的声音传播范围（格）。 */
    public int getAudioRange(BlockPos master) {
        Session session = sessions.get(master);
        return session == null ? VPlayerConfig.DEFAULT_AUDIO_RANGE : session.audioRange;
    }

    public String getError(BlockPos master) {
        Session session = sessions.get(master);
        if (session != null && session.lastError != null) {
            return session.lastError;
        }
        if (session == null || session.state == STATE_STOPPED) {
            return VPlayerNatives.getError();
        }
        return null;
    }

    /** 每帧由渲染线程调用：上传最新帧、按距离调整音量、清理失效会话。 */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.world.phys.Vec3 eye = mc.player == null ? null : mc.player.getEyePosition();
        for (Session session : sessions.values()) {
            session.uploadFrame();
            session.applyDistanceVolume(eye);
        }
        if (mc.level == null) {
            return;
        }
        if (++cleanupCounter >= 20) {
            cleanupCounter = 0;
            // 面板结构变化导致主屏坐标改变时，把会话迁移到新主屏（继续播放，不中断）
            java.util.List<BlockPos> moved = new java.util.ArrayList<>();
            for (BlockPos key : new java.util.ArrayList<>(sessions.keySet())) {
                if (!(mc.level.getBlockEntity(key) instanceof ScreenBlockEntity)) {
                    continue;
                }
                BlockPos master = ScreenGrid.masterFor(mc.level, key);
                if (master != null && !master.equals(key)) {
                    moved.add(key);
                }
            }
            for (BlockPos oldKey : moved) {
                BlockPos newKey = ScreenGrid.masterFor(mc.level, oldKey);
                Session session = sessions.remove(oldKey);
                if (session == null || newKey == null) {
                    continue;
                }
                if (sessions.containsKey(newKey)) {
                    session.stop();
                    VPlayerMod.LOGGER.warn("[VPlayer] 会话合并冲突，已停止: {}", oldKey);
                } else {
                    sessions.put(newKey, session);
                    session.moveTo(newKey);
                    VPlayerMod.LOGGER.info("[VPlayer] 主屏位置变化，会话已迁移: {} -> {}", oldKey, newKey);
                }
            }
            sessions.entrySet().removeIf(entry -> {
                if (!(mc.level.getBlockEntity(entry.getKey()) instanceof ScreenBlockEntity)) {
                    entry.getValue().stop();
                    return true;
                }
                return false;
            });
        }
    }

    /** 退出世界时停止一切播放。 */
    public void stopAll() {
        for (Session session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
    }

    /** 单个大屏的播放会话。 */
    private static final class Session {

        private BlockPos pos;
        void moveTo(BlockPos newPos) {
            this.pos = newPos;
        }
        private final AudioPlayer audio = new AudioPlayer();
        private final Java2DFrameConverter converter = new Java2DFrameConverter();
        private final Object frameLock = new Object();

        private volatile String url = "";
        private volatile boolean loop = false;
        private volatile int state = STATE_STOPPED;
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private volatile boolean clearRequested = false;
        private volatile String lastError = null;
        private volatile boolean eof = false;
        private volatile double speed = 1.0;
        private volatile double seekRequest = -1;
        private volatile double positionSec = 0;
        private volatile double durationSec = 0;
        private volatile float userVolume = 1.0f;
        private volatile int audioRange = VPlayerConfig.DEFAULT_AUDIO_RANGE;
        private volatile boolean reanchor = false;
        private boolean loggedFrame = false;
        private boolean loggedAudioFormat = false;
        private boolean textureUploaded = false;

        private FFmpegFrameGrabber grabber;
        private Thread thread;
        private BufferedImage pending;
        private float aspect = 16f / 9f;
        private ResourceLocation texId;
        private DynamicTexture texture;
        private int texW = -1;
        private int texH = -1;

        private Session(BlockPos pos) {
            this.pos = pos;
        }

        private void setSource(String url, boolean loop) {
            this.url = url == null ? "" : url;
            this.loop = loop;
        }

        private void play(String url, boolean loop) {
            if (url == null || url.isBlank()) {
                return;
            }
            // 从方块实体读取本屏幕的声音范围
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity be) {
                this.audioRange = be.getAudioRange();
            }
            // 正在播放或正在加载同一个地址：只更新循环开关，绝不重启线程
            if ((state == STATE_PLAYING || state == STATE_LOADING) && url.equals(this.url)) {
                this.loop = loop;
                return;
            }
            if (state == STATE_PAUSED && url.equals(this.url)) {
                this.loop = loop;
                resume();
                return;
            }
            VPlayerMod.LOGGER.info("[VPlayer] 重新加载视频: 原状态={} 地址是否变化={}",
                    state, !url.equals(this.url));
            stop();
            this.url = url;
            this.loop = loop;
            start();
        }

        /** 跳转到指定秒数。 */
        private void seek(double seconds) {
            if (seconds < 0) {
                return;
            }
            this.seekRequest = seconds;
        }

        /** 设置播放倍速（0.25 - 4），并重置节奏锚点避免卡顿。 */
        private void setSpeed(double newSpeed) {
            this.speed = Math.max(0.25, Math.min(4.0, newSpeed));
            this.reanchor = true;
        }

        /** 设置本屏幕的声音传播范围（1 - MAX_AUDIO_RANGE 格）。 */
        private void setAudioRange(int range) {
            this.audioRange = Math.max(1, Math.min(VPlayerConfig.MAX_AUDIO_RANGE, range));
        }

        /** 按与本地玩家的距离调整实际输出音量（淡入淡出）。 */
        private void applyDistanceVolume(net.minecraft.world.phys.Vec3 eye) {
            float gain = distanceGain(pos, eye, audioRange);
            audio.setVolume(userVolume * gain);
        }

        private void start() {
            state = STATE_LOADING;
            lastError = null;
            eof = false;
            positionSec = 0;
            durationSec = 0;
            seekRequest = -1;
            loggedFrame = false;
            loggedAudioFormat = false;
            textureUploaded = false;
            audio.reset();
            running = true;
            paused = false;
            thread = new Thread(this::decodeLoop, "VPlayer-" + pos.toShortString());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> VPlayerMod.LOGGER.error("[VPlayer] 解码线程未捕获异常", e));
            thread.start();
        }

        private void pause() {
            if (state == STATE_PLAYING || state == STATE_LOADING) {
                paused = true;
                state = STATE_PAUSED;
                audio.pause();
                VPlayerMod.LOGGER.info("[VPlayer] 已暂停（保持会话）位置={}", positionSec);
            }
        }

        private void resume() {
            if (state == STATE_PAUSED) {
                paused = false;
                state = STATE_PLAYING;
                // 重新锚定帧节奏：否则解码线程会把"暂停的那段时间"当成落后，
                // 恢复瞬间疯狂解码快进追时间，看起来就像视频被重新加载/跳变。
                reanchor = true;
                audio.resume();
                VPlayerMod.LOGGER.info("[VPlayer] 继续播放（原地续播，不重新加载）位置={}", positionSec);
            }
        }

        /** 停止播放（可从任意线程调用）。 */
        private void stop() {
            if (running) {
                StackTraceElement[] st = new Exception("stop-call").getStackTrace();
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < Math.min(st.length, 7); i++) {
                    sb.append(st[i].getClassName()).append(".").append(st[i].getMethodName())
                            .append(":").append(st[i].getLineNumber()).append(" <- ");
                }
                VPlayerMod.LOGGER.warn("[VPlayer] stop() 调用来源: {}", sb);
            }
            if (!running && state == STATE_STOPPED) {
                audio.close();
                return;
            }
            running = false;
            paused = false;
            Thread t = thread;
            if (t != null && t.isAlive()) {
                t.interrupt();
                try {
                    t.join(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (t.isAlive()) {
                    FFmpegFrameGrabber g = grabber;
                    if (g != null) {
                        try {
                            g.release();
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        t.join(2500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            thread = null;
            audio.close();
            state = STATE_STOPPED;
            clearRequested = true;
            synchronized (frameLock) {
                pending = null;
            }
        }

        private void decodeLoop() {
            try {
                VPlayerMod.LOGGER.info("[VPlayer] 解码线程启动: url={}", url);
                VPlayerNatives.ensureLoaded();
                if (!VPlayerNatives.isLoaded()) {
                    lastError = "FFmpeg 初始化失败: " + VPlayerNatives.getError();
                    return;
                }
                FFmpegFrameGrabber g;
                try {
                    g = new FFmpegFrameGrabber(url);
                } catch (Throwable t) {
                    VPlayerMod.LOGGER.error("[VPlayer] 创建 FFmpeg 解码器失败", t);
                    lastError = "创建解码器失败: " + t;
                    return;
                }
                String lower = url.toLowerCase();
                if (lower.contains("bilibili") || lower.contains("akamaized") || lower.contains("upos")) {
                    // B 站 CDN 直链需要浏览器 UA 与 Referer
                    g.setOption("referer", "https://www.bilibili.com");
                    g.setOption("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                } else {
                    g.setOption("user_agent", "VPlayer/1.0");
                }
                g.setOption("stimeout", "10000000");
                g.setOption("rtsp_transport", "tcp");
                g.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
                grabber = g;
                VPlayerMod.LOGGER.info("[VPlayer] 开始打开视频流: url={}", url);
                g.start();
                double len = g.getLengthInTime() / 1000000.0;
                durationSec = len > 0 && len < 86400 ? len : 0;
                VPlayerMod.LOGGER.info("[VPlayer] 视频流已打开: {}x{} fps={} 音频={}ch/{}Hz 时长={}s",
                        g.getImageWidth(), g.getImageHeight(), g.getFrameRate(),
                        g.getAudioChannels(), g.getSampleRate(), durationSec);
                if (!paused) {
                    state = STATE_PLAYING;
                }
                double videoAnchor = -1;
                long wallAnchorNs = 0;
                while (running) {
                    if (paused) {
                        Thread.sleep(50);
                        continue;
                    }
                    if (reanchor) {
                        reanchor = false;
                        videoAnchor = -1;
                    }
                    // 处理进度跳转
                    double seekTo = seekRequest;
                    if (seekTo >= 0) {
                        seekRequest = -1;
                        try {
                            g.setTimestamp((long) (seekTo * 1000000L));
                        } catch (Exception ignored) {
                        }
                        videoAnchor = -1;
                    }
                    Frame frame;
                    try {
                        frame = g.grabFrame();
                    } catch (Exception e) {
                        frame = null;
                    }
                    if (frame == null) {
                        if (loop) {
                            try {
                                g.restart();
                            } catch (Exception e) {
                                break;
                            }
                            videoAnchor = -1;
                            if (!paused) {
                                state = STATE_PLAYING;
                            }
                            continue;
                        }
                        eof = true;
                        break;
                    }
                    if (frame.image != null) {
                        BufferedImage img = converter.getBufferedImage(frame);
                        if (img != null) {
                            img = scaleDown(img);
                            if (!loggedFrame) {
                                loggedFrame = true;
                                VPlayerMod.LOGGER.info("[VPlayer] 首帧已解码: {}x{}", img.getWidth(), img.getHeight());
                            }
                            synchronized (frameLock) {
                                pending = img;
                            }
                        }
                        // 按视频时间戳做帧节奏控制（支持倍速）
                        double fps = Math.max(1.0, g.getFrameRate());
                        double tsSec = frame.timestamp > 0 ? frame.timestamp / 1000000.0 : positionSec + 1.0 / fps;
                        if (videoAnchor < 0) {
                            videoAnchor = tsSec;
                            wallAnchorNs = System.nanoTime();
                        }
                        positionSec = tsSec;
                        double target = (tsSec - videoAnchor) / Math.max(0.25, speed);
                        double elapsed = (System.nanoTime() - wallAnchorNs) / 1e9;
                        double wait = target - elapsed;
                        if (wait > 0) {
                            // 单次最多休眠 100ms，避免跳变导致长时间冻结
                            Thread.sleep(Math.min((long) (wait * 1000.0) + 1, 100L));
                        }
                    }
                    if (frame.samples != null && frame.samples.length > 0) {
                        int sr = frame.sampleRate > 0 ? frame.sampleRate : 44100;
                        int ch = frame.audioChannels > 0 ? frame.audioChannels : 2;
                        java.nio.Buffer first = frame.samples[0];
                        if (!loggedAudioFormat) {
                            loggedAudioFormat = true;
                            VPlayerMod.LOGGER.info("[VPlayer] 音频样本格式: {} 声道={} 采样率={} 平面数={} 可读={}",
                                    first.getClass().getSimpleName(), ch, sr, frame.samples.length, first.remaining());
                        }
                        if (first instanceof ShortBuffer rawSb) {
                            ShortBuffer sb = rawSb.duplicate();
                            if (sb.position() > 0) {
                                sb.flip();
                            } else {
                                sb.rewind();
                            }
                            if (sb.remaining() > 0) {
                                audio.push(sb, sr, ch, speed);
                            }
                        } else if (first instanceof FloatBuffer rawFb) {
                            if (frame.samples.length > 1) {
                                FloatBuffer[] planes = new FloatBuffer[frame.samples.length];
                                for (int c = 0; c < planes.length; c++) {
                                    FloatBuffer fb = frame.samples[c] instanceof FloatBuffer pf ? pf.duplicate() : null;
                                    if (fb != null) {
                                        if (fb.position() > 0) fb.flip(); else fb.rewind();
                                    }
                                    planes[c] = fb != null ? fb : FloatBuffer.allocate(0);
                                }
                                audio.pushFloatPlanar(planes, sr, ch);
                            } else {
                                FloatBuffer fb = rawFb.duplicate();
                                if (fb.position() > 0) fb.flip(); else fb.rewind();
                                if (fb.remaining() > 0) {
                                    audio.pushFloatInterleaved(fb, sr, ch);
                                }
                            }
                        }
                    }
                    Thread.sleep(5);
                }
            } catch (Throwable t) {
                VPlayerMod.LOGGER.error("[VPlayer] 视频播放出错: {}", url, t);
                lastError = friendlyError(t);
            } finally {
                closeGrabber();
                audio.close();
                VPlayerMod.LOGGER.info("[VPlayer] 解码线程结束: url={} eof={} error={}", url, eof, lastError);
                if (running) {
                    running = false;
                    state = STATE_STOPPED;
                }
            }
        }

        private static String friendlyError(Throwable e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 300) {
                msg = msg.substring(0, 300);
            }
            return e.getClass().getSimpleName() + ": " + (msg == null ? "" : msg);
        }

        private void closeGrabber() {
            if (grabber != null) {
                try {
                    grabber.close();
                } catch (Exception ignored) {
                }
                grabber = null;
            }
        }

        private static BufferedImage downscaleTo(BufferedImage src, int w, int h) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = out.createGraphics();
            g2d.drawImage(src, 0, 0, w, h, null);
            g2d.dispose();
            return out;
        }

        private static BufferedImage scaleDown(BufferedImage src) {
            int w = src.getWidth();
            int h = src.getHeight();
            int maxW = VPlayerConfig.MAX_VIDEO_WIDTH;
            int maxH = VPlayerConfig.MAX_VIDEO_HEIGHT;
            if (w <= maxW && h <= maxH) {
                return src;
            }
            double scale = Math.min((double) maxW / w, (double) maxH / h);
            int nw = Math.max(1, (int) (w * scale));
            int nh = Math.max(1, (int) (h * scale));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = out.createGraphics();
            g2d.drawImage(src, 0, 0, nw, nh, null);
            g2d.dispose();
            return out;
        }

        /** 渲染线程调用：把最新帧上传到动态纹理。 */
        private void uploadFrame() {
            if (clearRequested && state == STATE_STOPPED) {
                clearRequested = false;
                releaseTexture();
                return;
            }
            BufferedImage img;
            synchronized (frameLock) {
                img = pending;
                pending = null;
            }
            if (img == null) {
                return;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            Minecraft mc = Minecraft.getInstance();
            if (texture == null || texW != w || texH != h) {
                releaseTexture();
                texId = ResourceLocation.fromNamespaceAndPath(VPlayerMod.MODID,
                        "screen/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
                // 构造时直接带首帧数据，确保首帧立即可见
                NativeImage firstImage = buildNativeImage(img);
                texture = new DynamicTexture(firstImage);
                mc.getTextureManager().register(texId, texture);
                texW = w;
                texH = h;
                aspect = (float) w / (float) h;
                // 关闭 mipmap 采样，避免采样到未生成的暗色 mip 层级导致画面变暗
                com.mojang.blaze3d.platform.GlStateManager._bindTexture(texture.getId());
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_NEAREST);
                if (!textureUploaded) {
                    textureUploaded = true;
                    VPlayerMod.LOGGER.info("[VPlayer] 首帧纹理已创建: {} ({}x{}) texId={}",
                            texId, w, h, texture.getId());
                }
            }
            NativeImage nativeImage = texture.getPixels();
            if (nativeImage == null) {
                return;
            }
            for (int y = 0; y < h && y < nativeImage.getHeight(); y++) {
                for (int x = 0; x < w && x < nativeImage.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            texture.upload();
        }

        private static NativeImage buildNativeImage(BufferedImage img) {
            int w = img.getWidth();
            int h = img.getHeight();
            NativeImage ni = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    ni.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return ni;
        }

        private void releaseTexture() {
            if (texId != null) {
                Minecraft.getInstance().getTextureManager().release(texId);
                texId = null;
            }
            texture = null;
            texW = -1;
            texH = -1;
        }
    }

    private VideoEngine() {}
}
