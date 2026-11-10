package com.vplayer;

import com.vplayer.block.ScreenBlock;
import com.vplayer.init.ModBlockEntities;
import com.vplayer.init.ModBlocks;
import com.vplayer.init.ModItems;
import com.vplayer.net.VPlayerNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(VPlayerMod.MODID)
public class VPlayerMod {

    public static final String MODID = "vplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger("VPlayer");

    public VPlayerMod(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        VPlayerNetwork.init0(modEventBus);

        modEventBus.addListener(this::addCreative);

        if (FMLEnvironment.dist.isClient()) {
            com.vplayer.client.VPlayerClient.init(modEventBus);
        }
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SCREEN_ITEM.get());
        }
    }
}
