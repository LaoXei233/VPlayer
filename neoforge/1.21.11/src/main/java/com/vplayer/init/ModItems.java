package com.vplayer.init;

import com.vplayer.VPlayerMod;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(VPlayerMod.MODID);

    // 1.21.11 起物品 Properties 必须带 id：用 registerSimpleBlockItem 让 NeoForge 自动设置 id
    public static final DeferredItem<BlockItem> SCREEN_ITEM = ITEMS.registerSimpleBlockItem("screen", ModBlocks.SCREEN);

    private ModItems() {}
}
