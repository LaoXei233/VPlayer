package com.vplayer;

import com.mojang.logging.LogUtils;
import com.vplayer.init.ModBlockEntities;
import com.vplayer.init.ModBlocks;
import com.vplayer.init.ModItems;
import com.vplayer.net.VPlayerNetwork;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Forge 1.21.11 (Forge 61.2.5) 模组入口。
 * Forge 61 的 @Mod 构造器由框架注入 FMLJavaModLoadingContext，
 * 通过 context.getModBusGroup() 拿到模组事件总线组（BusGroup）。
 */
@Mod(VPlayerMod.MODID)
public class VPlayerMod {

    public static final String MODID = "vplayer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VPlayerMod(FMLJavaModLoadingContext context) {
        BusGroup modBusGroup = context.getModBusGroup();

        ModBlocks.BLOCKS.register(modBusGroup);
        ModItems.ITEMS.register(modBusGroup);
        ModBlockEntities.BLOCK_ENTITIES.register(modBusGroup);

        VPlayerNetwork.init0(modBusGroup);

        BuildCreativeModeTabContentsEvent.BUS.addListener(this::addCreative);

        if (FMLEnvironment.dist.isClient()) {
            com.vplayer.client.VPlayerClient.init(modBusGroup);
        }
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SCREEN_ITEM);
        }
    }
}
