package com.vplayer.init;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, VPlayerMod.MODID);

    public static final RegistryObject<ScreenBlock> SCREEN = BLOCKS.register("screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of()
                    .strength(1.2f, 6.0f)
                    .noOcclusion()
                    .lightLevel(state -> 1)));

    private ModBlocks() {}
}
