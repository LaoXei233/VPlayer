package com.vplayer.init;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(VPlayerMod.MODID);

    public static final DeferredBlock<ScreenBlock> SCREEN = BLOCKS.register("screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of()
                    .strength(1.2f, 6.0f)
                    .noOcclusion()
                    .lightLevel(state -> 1)));

    private ModBlocks() {}
}
