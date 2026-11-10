package com.vplayer.init;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlock;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(VPlayerMod.MODID);

    // 1.21.11 起方块 Properties 必须带 id：用 registerBlock 让 NeoForge 自动设置 id
    public static final DeferredBlock<ScreenBlock> SCREEN = BLOCKS.registerBlock("screen",
            ScreenBlock::new,
            properties -> properties
                    .strength(1.2f, 6.0f)
                    .noOcclusion()
                    .lightLevel(state -> 1));

    private ModBlocks() {}
}
