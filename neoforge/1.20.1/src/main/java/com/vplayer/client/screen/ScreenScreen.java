package com.vplayer.client.screen;

import com.vplayer.VPlayerConfig;
import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlockEntity;
import com.vplayer.block.ScreenGrid;
import com.vplayer.client.media.VideoEngine;
import com.vplayer.net.ScreenControlMsg;
import com.vplayer.net.VPlayerNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;


import java.util.List;

/**
 * 屏幕控制界面：播放/暂停/停止、进度条拖动、倍速、音量、声音范围。
 */
public class ScreenScreen extends Screen {

    private final BlockPos pos;
    private BlockPos master;
    private int groupWidth = 1;
    private int groupHeight = 1;
    private boolean merged = false;

    private EditBox urlField;
    private boolean loopOn = false;
    private float volume = 1.0f;
    private String notice = "";
    private double speed = 1.0;
    private int audioRange = VPlayerConfig.DEFAULT_AUDIO_RANGE;
    private boolean draggingBar = false;
    private String lastUrl = "";

    private int x0;
    private int barX;
    private int barY;
    private final int barW = 200;
    private final int barH = 10;

    private static final float[] SPEEDS = {0.5f, 1.0f, 1.5f, 2.0f};

    public ScreenScreen(BlockPos pos) {
        super(Component.translatable("screen.vplayer.title"));
        this.pos = pos;
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new ScreenScreen(pos));
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        x0 = this.width / 2 - 145;

        if (mc.level != null) {
            ScreenGrid.Group group = ScreenGrid.groupFor(mc.level, pos);
            master = group.master();
            groupWidth = group.width();
            groupHeight = group.height();
            merged = group.cells().size() > 1;
        } else {
            master = pos;
        }

        String initialUrl = "";
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity be) {
            initialUrl = be.getUrl();
            loopOn = be.isLoop();
        }
        if (initialUrl.isEmpty() && mc.level != null && mc.level.getBlockEntity(master) instanceof ScreenBlockEntity masterBe) {
            initialUrl = masterBe.getUrl();
            loopOn = masterBe.isLoop();
        }
        if (initialUrl.isEmpty()) {
            // 方块数据可能还没同步到这一格：直接回显正在播放的会话地址，点"播放"就能原地续播
            initialUrl = VideoEngine.get().getSourceUrl(master);
        }
        if (initialUrl.isEmpty()) {
            // 方块数据可能还没同步到这一格：直接回显正在播放的会话地址，点"播放"就能原地续播
            initialUrl = VideoEngine.get().getSourceUrl(master);
        }
        volume = VideoEngine.get().getVolume(master);
        speed = VideoEngine.get().getSpeed(master);
        audioRange = VideoEngine.get().getAudioRange(master);
        if (mc.level != null && mc.level.getBlockEntity(pos) instanceof ScreenBlockEntity be2
                && VideoEngine.get().getState(master) == VideoEngine.STATE_STOPPED) {
            audioRange = be2.getAudioRange();
        }
        if (urlField != null && urlField.getValue() != null && !urlField.getValue().isEmpty()) {
            lastUrl = urlField.getValue();
        }

        urlField = new EditBox(font, x0, 40, 200, 18, Component.translatable("screen.vplayer.url"));
        urlField.setMaxLength(4096);
        urlField.setValue(lastUrl.isEmpty() ? initialUrl : lastUrl);
        addRenderableWidget(urlField);

        addRenderableWidget(Button.builder(Component.translatable("screen.vplayer.play"),
                        b -> sendAction(ScreenControlMsg.ACTION_PLAY, 0))
                .bounds(x0, 66, 62, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.vplayer.pause"),
                        b -> sendAction(ScreenControlMsg.ACTION_PAUSE, 0))
                .bounds(x0 + 66, 66, 62, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.vplayer.stop"),
                        b -> sendAction(ScreenControlMsg.ACTION_STOP, 0))
                .bounds(x0 + 132, 66, 68, 18).build());

        addRenderableWidget(Button.builder(loopLabel(), b -> {
                    loopOn = !loopOn;
                    b.setMessage(loopLabel());
                })
                .bounds(x0, 122, 88, 18).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustVolume(-0.1f))
                .bounds(x0 + 92, 122, 22, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustVolume(0.1f))
                .bounds(x0 + 116, 122, 22, 18).build());
        addRenderableWidget(Button.builder(speedLabel(), b -> {
                    int idx = 0;
                    for (int i = 0; i < SPEEDS.length; i++) {
                        if (Math.abs(SPEEDS[i] - speed) < 0.01) {
                            idx = i;
                        }
                    }
                    speed = SPEEDS[(idx + 1) % SPEEDS.length];
                    b.setMessage(speedLabel());
                    sendAction(ScreenControlMsg.ACTION_SPEED, (float) speed);
                })
                .bounds(x0 + 142, 122, 58, 18).build());

        // 声音范围（每块屏幕独立设置，最大 64 格）
        addRenderableWidget(Button.builder(rangeLabel(), b -> {
                    int[] steps = VPlayerConfig.AUDIO_RANGE_STEPS;
                    int idx = 0;
                    for (int i = 0; i < steps.length; i++) {
                        if (steps[i] == audioRange) {
                            idx = i;
                        }
                    }
                    audioRange = steps[(idx + 1) % steps.length];
                    b.setMessage(rangeLabel());
                    sendAction(ScreenControlMsg.ACTION_RANGE, (float) audioRange);
                })
                .bounds(x0, 146, 110, 18).build());
    }

    private Component loopLabel() {
        return Component.translatable("screen.vplayer.loop",
                Component.translatable(loopOn ? "screen.vplayer.loop.on" : "screen.vplayer.loop.off"));
    }

    private Component speedLabel() {
        return Component.translatable("screen.vplayer.speed", String.format("%.1fx", speed));
    }

    private Component rangeLabel() {
        return Component.translatable("screen.vplayer.range", audioRange);
    }

    private void adjustVolume(float delta) {
        volume = Math.max(0.0f, Math.min(1.0f, volume + delta));
        VideoEngine.get().setVolume(master, volume);
    }

    private void sendAction(byte action, float value) {
        String url = urlField.getValue().replace("\uFEFF", "").trim();
        if (action == ScreenControlMsg.ACTION_PLAY && url.isEmpty()) {
            notice = Component.translatable("screen.vplayer.empty").getString();
            return;
        }
        notice = "";
        try {
            VPlayerNetwork.CHANNEL.sendToServer(new ScreenControlMsg(pos, url, loopOn, action, value));
        } catch (Exception e) {
            notice = "发送失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            VPlayerMod.LOGGER.error("[VPlayer] 发送控制包失败", e);
        }
        try {
            VideoEngine.get().apply(pos, url, loopOn, action, value);
        } catch (Exception e) {
            notice = "本地播放失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            VPlayerMod.LOGGER.error("[VPlayer] 本地应用播放失败", e);
        }
    }

    private void seekToFraction(double fraction) {
        double duration = VideoEngine.get().getDuration(master);
        if (duration <= 0) {
            return;
        }
        double seconds = Math.max(0, Math.min(duration, fraction * duration));
        sendAction(ScreenControlMsg.ACTION_SEEK, (float) seconds);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= barX && mouseX <= barX + barW && mouseY >= barY && mouseY <= barY + barH) {
            draggingBar = true;
            seekToFraction((mouseX - barX) / (double) barW);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingBar) {
            seekToFraction((mouseX - barX) / (double) barW);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private String formatTime(double seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int total = (int) seconds;
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, Component.translatable("screen.vplayer.url"), x0, 28, 0xCCCCCC);

        // 进度条
        double duration = VideoEngine.get().getDuration(master);
        double position = VideoEngine.get().getPosition(master);
        int state = VideoEngine.get().getState(master);
        barX = x0;
        barY = 90;
        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF20202A);
        if (duration > 0) {
            int filled = (int) (barW * Math.max(0, Math.min(1, position / duration)));
            graphics.fill(barX, barY, barX + filled, barY + barH, 0xFF3FA9F5);
            graphics.fill(barX + filled - 1, barY - 1, barX + filled + 1, barY + barH + 1, 0xFFFFFFFF);
        } else if (state != VideoEngine.STATE_STOPPED) {
            graphics.fill(barX, barY, barX + barW / 3, barY + barH, 0xFF3FA9F5);
        }
        String timeText = formatTime(position) + " / " + (duration > 0 ? formatTime(duration) : "--:--");
        graphics.drawString(font, timeText, x0, 102, 0xDDDDDD);
        graphics.drawString(font, Component.translatable("screen.vplayer.volume", Math.round(volume * 100)),
                x0 + 92, 112, 0x999999);

        String stateKey = switch (state) {
            case VideoEngine.STATE_PLAYING -> "screen.vplayer.state.playing";
            case VideoEngine.STATE_PAUSED -> "screen.vplayer.state.paused";
            case VideoEngine.STATE_LOADING -> "screen.vplayer.state.loading";
            default -> "screen.vplayer.state.stopped";
        };
        graphics.drawString(font, Component.translatable("screen.vplayer.state", Component.translatable(stateKey)),
                x0, 172, 0xFFFFFF);
        if (merged) {
            graphics.drawString(font,
                    Component.translatable("screen.vplayer.big", groupWidth, groupHeight),
                    x0, 184, 0xAADDFF);
        }
        int y = 184 + (merged ? 12 : 0);
        if (!notice.isEmpty()) {
            graphics.drawString(font, notice, x0, y, 0xFFAA00);
            y += 12;
        }
        String error = VideoEngine.get().getError(master);
        if (error != null && state == VideoEngine.STATE_STOPPED) {
            List<FormattedCharSequence> lines = font.split(Component.literal(error), 290);
            for (int i = 0; i < lines.size() && i < 2; i++) {
                graphics.drawString(font, lines.get(i), x0, y + i * 11, 0xFF5555);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
