package com.vplayer.init;

import com.vplayer.VPlayerMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, VPlayerMod.MODID);

    public static final RegistryObject<Item> SCREEN_ITEM = ITEMS.register("screen",
            () -> new BlockItem(ModBlocks.SCREEN.get(), new Item.Properties()));

    private ModItems() {}
}
