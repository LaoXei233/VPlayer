package com.vplayer.client.media;

import com.vplayer.VPlayerMod;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * 轻量音频输出：把解码出的 PCM 样本写入系统声卡，支持倍速重采样与音量控制。
 */
public final class AudioPlayer {

    private SourceDataLine line;
    private boolean lineOpened = false;
    private boolean openFailureLogged = false;
    private volatile float volume = 1.0f;
    private volatile boolean closed = false;
    private long writtenBytes = 0;
    private boolean firstPushLogged = false;

    /** 推入交错排列的 16bit PCM 样本（按倍速重采样，任何速度都有声音）。 */
    public synchronized void push(ShortBuffer interleaved, int sampleRate, int channels) {
        push(interleaved, sampleRate, channels, 1.0);
    }

    /** 推入交错排列的 16bit PCM 样本，按 speed 重采样。 */
    public synchronized void push(ShortBuffer interleaved, int sampleRate, int channels, double speed) {
        if (closed || interleaved == null || interleaved.remaining() == 0) {
            return;
        }
        try {
            ensureLine(sampleRate, channels);
            if (!lineOpened) {
                return;
            }
            ShortBuffer src = interleaved;
            int ch = Math.max(1, Math.min(2, channels));
            if (Math.abs(speed - 1.0) > 0.01) {
                int framesIn = src.remaining() / ch;
                if (framesIn > 1) {
                    int framesOut = Math.max(1, (int) (framesIn / speed));
                    short[] data = new short[framesOut * ch];
                    for (int i = 0; i < framesOut; i++) {
                        int srcFrame = (int) (i * speed);
                        if (srcFrame >= framesIn) {
                            srcFrame = framesIn - 1;
                        }
                        for (int c = 0; c < ch; c++) {
                            data[i * ch + c] = src.get(srcFrame * ch + c);
                        }
                    }
                    src = ShortBuffer.wrap(data);
                }
            }
            ByteBuffer bytes = ByteBuffer.allocate(src.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asShortBuffer().put(src);
            line.write(bytes.array(), 0, bytes.capacity());
            writtenBytes += bytes.capacity();
            logIfFirst();
        } catch (Exception e) {
            openFailureLogged = true;
            lineOpened = false;
            line = null;
            VPlayerMod.LOGGER.error("[VPlayer] 音频输出失败", e);
        }
    }

    /** 推入 32bit float 交错样本。 */
    public synchronized void pushFloatInterleaved(FloatBuffer interleaved, int sampleRate, int channels) {
        if (closed || interleaved == null || interleaved.remaining() == 0) {
            return;
        }
        try {
            ensureLine(sampleRate, channels);
            if (!lineOpened) {
                return;
            }
            ByteBuffer bytes = ByteBuffer.allocate(interleaved.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);
            ShortBuffer out = bytes.asShortBuffer();
            while (interleaved.hasRemaining()) {
                out.put((short) (Math.max(-1f, Math.min(1f, interleaved.get())) * 32767f));
            }
            line.write(bytes.array(), 0, bytes.capacity());
            writtenBytes += bytes.capacity();
            logIfFirst();
        } catch (Exception e) {
            openFailureLogged = true;
            lineOpened = false;
            line = null;
            VPlayerMod.LOGGER.error("[VPlayer] 音频输出失败", e);
        }
    }

    /** 推入 32bit float 平面样本（每个声道一个 Buffer）。 */
    public synchronized void pushFloatPlanar(FloatBuffer[] planes, int sampleRate, int channels) {
        if (closed || planes == null || planes.length == 0) {
            return;
        }
        int n = planes[0].remaining();
        if (n == 0) {
            return;
        }
        try {
            ensureLine(sampleRate, channels);
            if (!lineOpened) {
                return;
            }
            int ch = Math.max(1, Math.min(channels, planes.length));
            ByteBuffer bytes = ByteBuffer.allocate(n * ch * 2).order(ByteOrder.LITTLE_ENDIAN);
            ShortBuffer out = bytes.asShortBuffer();
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < ch; c++) {
                    float v = c < planes.length ? planes[c].get(i) : 0f;
                    out.put((short) (Math.max(-1f, Math.min(1f, v)) * 32767f));
                }
            }
            line.write(bytes.array(), 0, bytes.capacity());
            writtenBytes += bytes.capacity();
            logIfFirst();
        } catch (Exception e) {
            openFailureLogged = true;
            lineOpened = false;
            line = null;
            VPlayerMod.LOGGER.error("[VPlayer] 音频输出失败", e);
        }
    }

    private void ensureLine(int sampleRate, int channels) {
        if (lineOpened) {
            return;
        }
        int sr = Math.max(8000, Math.min(192000, sampleRate));
        int ch = Math.max(1, Math.min(2, channels));
        try {
            AudioFormat format = new AudioFormat(sr, 16, ch, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, 16384);
            line.start();
            lineOpened = true;
            applyVolume();
            VPlayerMod.LOGGER.info("[VPlayer] 音频输出已打开: {}Hz {}ch", sr, ch);
        } catch (LineUnavailableException | IllegalArgumentException | IllegalStateException e) {
            if (!openFailureLogged) {
                openFailureLogged = true;
                VPlayerMod.LOGGER.error("[VPlayer] 音频输出打开失败: {}", e.toString());
            }
            lineOpened = false;
            line = null;
        }
    }

    private void logIfFirst() {
        if (!firstPushLogged) {
            firstPushLogged = true;
            VPlayerMod.LOGGER.info("[VPlayer] 音频数据开始写入, 已写入 {} 字节", writtenBytes);
        }
    }

    public long getWrittenBytes() {
        return writtenBytes;
    }

    public boolean isLineOpen() {
        return lineOpened;
    }

    /** 重新开始播放时重置状态。 */
    public synchronized void reset() {
        closed = false;
        openFailureLogged = false;
        firstPushLogged = false;
        writtenBytes = 0;
    }

    public synchronized void pause() {
        if (lineOpened && line != null && line.isRunning()) {
            line.stop();
            // 丢掉暂停前还排在声卡缓冲里的音频，恢复时音画才同步
            line.flush();
        }
    }

    public synchronized void resume() {
        if (lineOpened && line != null && !line.isRunning()) {
            line.start();
        }
    }

    public synchronized void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        applyVolume();
    }

    public float getVolume() {
        return volume;
    }

    private void applyVolume() {
        if (line != null && line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float db = volume <= 0.001f ? control.getMinimum() : (float) (20.0 * Math.log10(volume));
            db = Math.max(control.getMinimum(), Math.min(control.getMaximum(), db));
            control.setValue(db);
        }
    }

    public synchronized void close() {
        closed = true;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
            }
            line = null;
        }
        lineOpened = false;
    }
}
