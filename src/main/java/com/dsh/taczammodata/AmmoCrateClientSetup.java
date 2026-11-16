package com.dsh.taczammodata;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端界面注册（2026-11-14）。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AmmoCrateClientSetup {

    private AmmoCrateClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(
                TaczAmmoData.AMMO_CRATE_MENU.get(), AmmoCrateScreen::new));
    }
}
