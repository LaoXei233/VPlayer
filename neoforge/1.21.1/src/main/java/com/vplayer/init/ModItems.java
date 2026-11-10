package com.vplayer.init;

import com.vplayer.VPlayerMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(VPlayerMod.MODID);

    public static final DeferredItem<Item> SCREEN_ITEM = ITEMS.register("screen",
            () -> new BlockItem(ModBlocks.SCREEN.get(), new Item.Properties()));

    private ModItems() {}
}
