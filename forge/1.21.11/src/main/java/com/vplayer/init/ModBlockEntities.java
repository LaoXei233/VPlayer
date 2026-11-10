package com.vplayer.init;

import com.vplayer.VPlayerMod;
import com.vplayer.block.ScreenBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, VPlayerMod.MODID);

    // 1.21.11 正式版 BlockEntityType 的构造器参数是 Set<Block>（Forge 的 AT 把它开放为 public）
    public static final RegistryObject<BlockEntityType<ScreenBlockEntity>> SCREEN =
            BLOCK_ENTITIES.register("screen",
                    () -> new BlockEntityType<>(ScreenBlockEntity::new, java.util.Set.of(ModBlocks.SCREEN.get())));

    private ModBlockEntities() {}
}
