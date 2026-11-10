package com.vplayer;

import com.vplayer.command.VPlayCommand;
import com.vplayer.init.ModBlockEntities;
import com.vplayer.init.ModBlocks;
import com.vplayer.init.ModItems;
import com.vplayer.net.VPlayerNetwork;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.21.1 模组入口。
 * 1.21.1 的 @Mod 构造器可以注入 FMLJavaModLoadingContext（Forge 52.0.9+，官方 1.21.1 MDK 写法）。
 */
@Mod(VPlayerMod.MODID)
public class VPlayerMod {

    public static final String MODID = "vplayer";
    public static final Logger LOGGER = LoggerFactory.getLogger("VPlayer");

    public VPlayerMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::addCreative);

        VPlayerNetwork.init();
        MinecraftForge.EVENT_BUS.addListener(VPlayerMod::registerCommands);

        if (FMLEnvironment.dist.isClient()) {
            com.vplayer.client.VPlayerClient.init(modEventBus);
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        VPlayCommand.register(event.getDispatcher());
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SCREEN_ITEM.get());
        }
    }
}
