package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 弹药台方块对应的物品 —— 只多做一件事：在 tooltip 里写清「怕火」。
 *
 * 2026-11-09 主人要求：弹药台的说明里必须提醒，
 * 放置时周围不准有任何可燃/热源，否则会过热爆炸。
 */
public class AmmoStationBlockItem extends BlockItem {

    public AmmoStationBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.station.3"));
        // 2026-11-09 主人要的创造专属「整活」提示
        tooltip.add(Component.literal("§c创造专属：无限弹药 —— 殉爆威力极高")
                .withStyle(ChatFormatting.RED));
        tooltip.add(Component.literal("§c不推荐把创造弹药箱摆着用，容易炸到自己的家")
                .withStyle(ChatFormatting.RED));
        tooltip.add(Component.literal("§7补弹药推荐用「创造弹药箱（便携式）」，右键即补")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("§c§m我偏要看看它炸起来是什么样")
                .withStyle(ChatFormatting.RED));
        tooltip.add(Component.literal("§c§m    行，那就放下去，站远点")
                .withStyle(ChatFormatting.RED));
    }
}