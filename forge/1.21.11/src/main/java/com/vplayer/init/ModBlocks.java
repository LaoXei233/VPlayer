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

    // 1.21.11 起方块 Properties 必须带 id：BLOCKS.key("screen") 生成 ResourceKey<Block>
    public static final RegistryObject<ScreenBlock> SCREEN = BLOCKS.register("screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of()
                    .setId(BLOCKS.key("screen"))
                    .strength(1.2f, 6.0f)
                    .noOcclusion()
                    .lightLevel(state -> 1)));

    private ModBlocks() {}
}
