package com.vplayer;

import com.mojang.logging.LogUtils;
import com.vplayer.init.ModBlockEntities;
import com.vplayer.init.ModBlocks;
import com.vplayer.init.ModItems;
import com.vplayer.net.VPlayerNetwork;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(VPlayerMod.MODID)
public class VPlayerMod {

    public static final String MODID = "vplayer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VPlayerMod(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        VPlayerNetwork.init0(modEventBus);

        modEventBus.addListener(this::addCreative);

        if (FMLEnvironment.getDist().isClient()) {
            com.vplayer.client.VPlayerClient.init(modEventBus, NeoForge.EVENT_BUS);
        }
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(new ItemStack(ModItems.SCREEN_ITEM.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
        }
    }
}
