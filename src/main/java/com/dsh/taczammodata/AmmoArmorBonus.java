package com.dsh.taczammodata;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 下界合金弹药袋的额外防御。
 *
 * 主人 2026-11-08：「下界合金造价这么贵，加 8 点护甲吧」。
 * 只要有**任意一类**弹药袋升到下界合金（4 级），就常驻 +8 护甲。
 *
 * 用 transient modifier（不写进玩家 NBT 属性表），每 20 tick 自检一次：
 * 有就加、没了就删，死亡重生也会自动补回来。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID)
public final class AmmoArmorBonus {

    private static final UUID MODIFIER_ID =
            UUID.nameUUIDFromBytes("tacz_ammodata:netherite_armor".getBytes(StandardCharsets.UTF_8));
    private static final String MODIFIER_NAME = "tacz_ammodata_netherite_bonus";
    // 数值见 Config.netheriteArmorBonus（2026-11-09 提成配置，0 = 关掉）

    private AmmoArmorBonus() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide() || player.tickCount % 20 != 0) {
            return;
        }
        AttributeInstance armor = player.getAttribute(Attributes.ARMOR);
        if (armor == null) {
            return;
        }

        boolean hasNetherite = false;
        for (AmmoCategory c : AmmoCategory.values()) {
            if (AmmoDataStore.getTier(player, c) >= 5) {
                hasNetherite = true;
                break;
            }
        }

        double bonus = Config.netheriteArmorBonus;
        boolean applied = armor.getModifier(MODIFIER_ID) != null;
        boolean want = hasNetherite && bonus > 0;
        if (want && !applied) {
            armor.addTransientModifier(new AttributeModifier(
                    MODIFIER_ID, MODIFIER_NAME, bonus, AttributeModifier.Operation.ADDITION));
            if (Config.logReload) {
                TaczAmmoData.LOGGER.info("[弹药数据] {} 获得下界合金弹药袋护甲 +{}",
                        player.getName().getString(), bonus);
            }
        } else if (!want && applied) {
            armor.removeModifier(MODIFIER_ID);
        }
    }
}
