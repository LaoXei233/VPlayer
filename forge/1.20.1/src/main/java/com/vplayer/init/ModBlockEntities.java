package com.vplayer.init;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, VPlayerMod.MODID);

    public static final RegistryObject<BlockEntityType<ScreenBlockEntity>> SCREEN =
            BLOCK_ENTITIES.register("screen",
                    () -> BlockEntityType.Builder.of(ScreenBlockEntity::new, ModBlocks.SCREEN.get()).build(null));

    private ModBlockEntities() {}
}
