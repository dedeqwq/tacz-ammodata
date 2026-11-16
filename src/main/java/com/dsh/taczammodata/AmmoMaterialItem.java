package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

/**
 * 复合弹料（2026-11-09 主人要的「通用弹药材料」）。
 *
 * 解决的问题：玩家手里一堆「没对应枪」的子弹、或者干脆只有火药和铁，
 * 没法喂进弹药台。这里给一个**不看口径**的通货：
 *   右键创造弹药台 → 直接加台子的弹药点；
 *   弹药箱 → **只能把弹料放进界面的左槽**（2026-11-14 主人要求）。
 *
 * 以前这里还允许右键塞进弹药箱，走的是 AmmoCrateData.generic 老池子，
 * 界面根本不读那玩意儿 —— 表现为"东西被吃、箱子里看不到"。
 * 现在那条路径已删除；老存档里的 generic 会在读档时并进待分配点数。
 */
public class AmmoMaterialItem extends Item {

    /** 1 个弹料的产出走 Config.cratePointsPerMaterial（默认 200） */
    public static int pointsPerItem() {
        return Config.cratePointsPerMaterial;
    }

    public AmmoMaterialItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockEntity be = level.getBlockEntity(context.getClickedPos());
        int count = stack.getCount();
        int want = count * pointsPerItem();

        // 只有创造弹药台还能右键投料；弹药箱必须开界面从左槽放
        if (be instanceof AmmoStationBlockEntity station) {
            int added = station.addPoints(want);
            if (added <= 0) {
                player.displayClientMessage(Component.literal(String.format(
                        "§c台子满了（%d / %d 点），弹料没动",
                        station.getPoints(), station.capacity()))
                        .withStyle(ChatFormatting.RED), true);
                return InteractionResult.SUCCESS;
            }
            // 只消耗真正换成点数的那几个弹料，剩下的留在手上
int used = added / pointsPerItem();
            if (used <= 0) {
                // 2026-11-17 修（零头白得，和 AmmoStationBlock 子弹分支同款）：
                // addPoints 已经收下了 added，但一个弹料都扣不出来 → 必须退回，否则能刷点。
                station.setPoints(station.getPoints() - added);
                player.displayClientMessage(Component.literal(String.format(
                        "§c台子快满了（%d / %d 点），一个弹料都塞不下",
                        station.getPoints(), station.capacity()))
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                return InteractionResult.SUCCESS;
            }
            // ⚠️ 2026-11-16 修（C8 同款）：收下的点数对齐到实际消耗的弹料数，零头退回
            int credited = used * pointsPerItem();
            if (credited != added) {
                station.setPoints(station.getPoints() - (added - credited));
            }
            stack.shrink(used);
            player.displayClientMessage(Component.literal(String.format(
                    "§a投入 %d 个复合弹料（+%d 弹药点，台内共 %d / %d）",
                    used, credited, station.getPoints(), station.capacity())), true);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        // 文案走 lang（2026-11-16 主人要求"改得正经一点"）：预制原料、单件打包、快速后勤
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.material.desc"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.material.usage"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.material.rate", pointsPerItem()));
    }
}