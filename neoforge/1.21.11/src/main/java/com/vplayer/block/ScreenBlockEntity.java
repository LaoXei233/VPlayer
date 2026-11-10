package com.vplayer.block;

import com.vplayer.VPlayerConfig;
import com.vplayer.client.media.VideoEngine;
import com.vplayer.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 屏幕方块实体：保存视频源地址、循环开关与声音范围，并同步给客户端。
 */
public class ScreenBlockEntity extends BlockEntity {

    private String url = "";
    private boolean loop = false;
    private int audioRange = VPlayerConfig.DEFAULT_AUDIO_RANGE;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SCREEN.get(), pos, state);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
        setChanged();
    }

    public boolean isLoop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
        setChanged();
    }

    public int getAudioRange() {
        return audioRange <= 0 ? VPlayerConfig.DEFAULT_AUDIO_RANGE : audioRange;
    }

    public void setAudioRange(int audioRange) {
        this.audioRange = Math.max(1, Math.min(VPlayerConfig.MAX_AUDIO_RANGE, audioRange));
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("url", url);
        output.putBoolean("loop", loop);
        output.putInt("audioRange", audioRange);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.url = input.getStringOr("url", "");
        this.loop = input.getBooleanOr("loop", false);
        this.audioRange = input.getIntOr("audioRange", 0);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return this.saveWithoutMetadata(provider);
    }

    @Override
    public void handleUpdateTag(ValueInput input) {
        this.loadWithComponents(input);
        if (this.level != null && this.level.isClientSide()) {
            VideoEngine.get().syncUrl(worldPosition, url, loop);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
