package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 弹药台（物品）—— 右键「一键把所有弹药表补满」。
 *
 * 主人原话：「去专门的弹药台里一键获取所有的弹药」。
 * 用 SF2403 的物资箱贴图。
 */
public class AmmoStationItem extends Item {

    public AmmoStationItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }

        // 便携版：副手拿着补给包 → 先灌包；否则补满玩家的弹药表
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof EmergencyPackItem pack) {
            int added = pack.fillFull(off);
            if (player instanceof ServerPlayer sp) {
                AmmoNetwork.syncAll(sp);
            }
            player.displayClientMessage(Component.literal(
                            added > 0
                                    ? String.format("§a灌满 %d 个补给包（每个 +%d 弹药点）", off.getCount(), added)
                                    : pack.isFull(off)
                                            ? "§7已经满了"
                                            : "§7铁罐必须把里面的弹药完全用完，才能重新封装"), true);
            player.getCooldowns().addCooldown(this, Config.stationCooldownTicks);
            return InteractionResultHolder.success(stack);
        }

        int filled = AmmoDataStore.refillAll(player);

        if (player instanceof ServerPlayer sp) {
            AmmoNetwork.syncAll(sp);
        }

        if (Config.logReload) {
            TaczAmmoData.LOGGER.info("[弹药台] 玩家 {} 补满 {}/{} 类弹药表",
                    player.getName().getString(), filled, AmmoCategory.values().length);
        }
        player.displayClientMessage(
                Component.translatable("msg.tacz_ammodata.station_filled",
                        filled, AmmoCategory.values().length), true);

        player.getCooldowns().addCooldown(this, Config.stationCooldownTicks);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.station.1"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.station.2"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.station.3"));
        tooltip.add(Component.literal("§7你手里拿的就是推荐的那款：随身补，不用摆放")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("§c§m但我还是想放个箱子在地上试试")
                .withStyle(ChatFormatting.RED));
    }
}