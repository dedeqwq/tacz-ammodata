package com.dsh.taczammodata;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端模型谓词：**按「有没有弹」切换补给包贴图**（2026-11-09）。
 *
 * 物品模型 json 里写：
 *   "overrides": [ { "predicate": { "tacz_ammodata:filled": 1.0 }, "model": "..._filled" } ]
 *
 * 这里注册的 `filled`：**有弹 = 1，空包 = 0**。
 * 模型 `emergency_pack_xxx` 的默认贴图是空包，`filled = 1` 才切到满包模型。
 *
 * ⚠️ 2026-11-09 修的真 bug：
 *   谓词方向本来就是「满 1 / 空 0」，但模型 override 的条件写成了 0。
 *   MC 的匹配规则是 `谓词值 >= 条件值` 就命中，满包 1.0 >= 0.0 也成立
 *   → **满包永远被显示成空包**。现在条件统一是 1.0，只有有弹才切满包。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PackClientModels {

    public static final ResourceLocation FILLED = new ResourceLocation(TaczAmmoData.MODID, "filled");

    private PackClientModels() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            for (var ro : new net.minecraftforge.registries.RegistryObject[]{
                    TaczAmmoData.EMERGENCY_PACK_BASIC,
                    TaczAmmoData.EMERGENCY_PACK_ADVANCED,
                    TaczAmmoData.EMERGENCY_PACK_ELITE}) {
                Item item = (Item) ro.get();
                ItemProperties.register(item, FILLED, (stack, level, entity, seed) -> {
                    if (item instanceof EmergencyPackItem pack) {
                        return pack.isEmpty(stack) ? 0.0F : 1.0F;
                    }
                    return 1.0F;
                });
            }
        });
    }
}
