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
 * 弹药箱的物品形态 —— 只多做一件事：按档位写说明（2026-11-16 主人写的文案）。
 *
 * ===== 为什么单独一个类 =====
 * 三个箱子用的是原版 {@link BlockItem}，它没有 appendHoverText 的定制，
 * 直接拿三个原版 BlockItem 的话，写多少文案都显示不出来。
 *
 * ===== 文案排布（三档共用一个首行）=====
 *   ① 热源警告：三档都放第一行，红色，统一
 *   ② 档位说明：初级 / 中级各 4 行，高级 5 行
 *   ③ 风味 / 对话：中级和高级各带一段"划掉的纸条"，用 §m 划线
 *
 * 划线用的是 §m（strikethrough），和创造弹药箱那两句是同一个手法。
 * 文案走 lang key（`tooltip.tacz_ammodata.crate.*`），中英文各一份。
 */
public class AmmoCrateBlockItem extends BlockItem {

    private final AmmoCrateData.Tier tier;

    public AmmoCrateBlockItem(Block block, Properties properties, AmmoCrateData.Tier tier) {
        super(block, properties);
        this.tier = tier;
    }

    public AmmoCrateData.Tier tier() {
        return tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        // ① 热源警告（三档统一，放最上面）
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.hazard"));

        // ② + ③ 按档位铺开
        switch (tier) {
            case BASIC -> {
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.basic.1"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.basic.2"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.basic.3"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.basic.4"));
            }
            case INTERMEDIATE -> {
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.1"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.2"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.3"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.4"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.5"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.6"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.advanced.7"));
            }
            case ADVANCED -> {
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.1"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.2"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.3"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.4"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.5"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.6"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.7"));
                tooltip.add(Component.translatable("tooltip.tacz_ammodata.crate.elite.8"));
            }
        }
    }
}
