package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 物品说明里直接写清楚「现在几级 / 这一颗能升到几级 / 差哪一级」。
 *
 * ===== 为什么要在 tooltip 上做（主人 2026-11-07 提的）=====
 *
 * 升级链本来就是**严格**的（{@link AmmoTierItem} 里：现在等级必须正好是
 * 「这一颗的目标等级 - 1」，跳级直接被拒），但以前只有右键用错了才弹一句话，
 * 玩家举着下界合金包根本不知道自己差几级。
 *
 * 这里补上：鼠标一悬停就看见
 *   步枪弹：现在 基础级 → 装完 铁级
 *   ✘ 不能跳级：得先到 金级
 *
 * 等级走客户端缓存（{@link AmmoNetwork.ClientAmmoCache}），服务端的玩家数据客户端摸不到。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID, value = Dist.CLIENT)
public final class AmmoClientTooltips {

    private AmmoClientTooltips() {
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        boolean tierItem = stack.getItem() instanceof AmmoTierItem;
        boolean detach = stack.getItem() instanceof AmmoDetachItem;
        if (!tierItem && !detach) {
            return;
        }

        Player player = event.getEntity();
        if (player == null) {
            return;
        }

        AmmoCategory cat = AmmoCategory.ofGun(player.getOffhandItem());
        if (cat == null) {
            event.getToolTip().add(Component.literal("§8副手没拿枪 → 看不到当前等级")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        int cur = AmmoNetwork.ClientAmmoCache.tier(cat);

        if (tierItem) {
            int target = ((AmmoTierItem) stack.getItem()).getTargetTier();
            event.getToolTip().add(Component.literal(
                    cat.displayName + "：现在 " + Config.tierName(cur) + "级 → 装完 "
                            + Config.tierName(target) + "级")
                    .withStyle(ChatFormatting.GRAY));

            if (cur == target - 1) {
                event.getToolTip().add(Component.literal("✔ 现在就能装")
                        .withStyle(ChatFormatting.GREEN));
            } else if (cur >= target) {
                event.getToolTip().add(Component.literal("✘ 已经 " + Config.tierName(cur) + "级了，这颗用不上")
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                event.getToolTip().add(Component.literal(
                                "✘ 不能跳级：得先装到 " + Config.tierName(target - 1) + "级")
                        .withStyle(ChatFormatting.RED));
            }
        } else {
            event.getToolTip().add(Component.literal(cat.displayName + "：现在 " + Config.tierName(cur) + "级")
                    .withStyle(ChatFormatting.GRAY));
            if (cur <= 0) {
                event.getToolTip().add(Component.literal("✘ 还是基础级，拆不出东西")
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                event.getToolTip().add(Component.literal("拆完变 " + Config.tierName(cur - 1)
                                + "级，退还 " + Config.tierName(cur) + "级扩容包")
                        .withStyle(ChatFormatting.YELLOW));
            }
        }
    }
}
