package com.vplayer.init;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, VPlayerMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ScreenBlockEntity>> SCREEN =
            BLOCK_ENTITIES.register("screen",
                    () -> new BlockEntityType<>(ScreenBlockEntity::new, ModBlocks.SCREEN.get()));

    private ModBlockEntities() {}
}
