package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 弹药箱升级物品（2026-11-14 主人要的升级家族）。
 *
 * 右键任意一档弹药箱（含 1×2 / 2×2 的任意一格）把对应升级线提到目标等级。
 * 规则：只升不降；当前等级 >= 目标时不消耗物品，只提示。允许跳级。
 */
public class CrateUpgradeItem extends Item {

    private final CrateUpgradeType type;
    private final int targetLevel;

    public CrateUpgradeItem(Properties properties, CrateUpgradeType type, int targetLevel) {
        // 可堆叠，无 NBT
        super(properties.stacksTo(16));
        this.type = type;
        this.targetLevel = Math.max(1, Math.min(targetLevel, type.maxLevel));
    }

    public CrateUpgradeType type() {
        return type;
    }

    public int targetLevel() {
        return targetLevel;
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

        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AmmoCrateBlock block)) {
            return InteractionResult.PASS;
        }

        BlockPos base = block.basePos(pos, state);
        BlockEntity be = level.getBlockEntity(base);
        if (!(be instanceof AmmoCrateBlockEntity crate)) {
            return InteractionResult.PASS;
        }

        AmmoCrateData data = crate.data();
        int current = data.getUpgrade(type);
        if (current >= targetLevel) {
            player.displayClientMessage(Component.literal(
                            current == targetLevel
                                    ? "§7这个箱子已经是「" + type.displayName + "」这一级了"
                                    : "§7这个箱子的「" + type.displayName + "」已经更高了")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.SUCCESS;
        }
        // 2026-11-14 主人定：不可跳级，必须一级一级往上装
        if (current < targetLevel - 1) {
            player.displayClientMessage(Component.literal(
                            "§7必须先装上一级「" + type.displayName + "」才能继续升级")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.SUCCESS;
        }

        data.setUpgrade(type, targetLevel);
        crate.setChanged();
        stack.shrink(1);

        String detail = type == CrateUpgradeType.CAPACITY
                ? "容量 ×" + fmt(data.upgradeMultiplier()) + "（总容量 " + data.genericCapacity() + " 点）"
                : type.effectText;
        player.displayClientMessage(Component.literal(
                        "§a升级成功：" + type.displayName + " → " + detail)
                .withStyle(ChatFormatting.GREEN), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        // 2026-11-16：文案全走 lang，按「升级线 + 等级」拼 key。
        //   CAPACITY  → capacity.iron / .gold / .diamond / .netherite
        //   其余三线  → efficiency / lucky / speed
        // 每件先给一句通用用法，再铺该等级自己的说明（含 NPC 对话）。
        tooltip.add(Component.translatable(type == CrateUpgradeType.CAPACITY
                ? "tooltip.tacz_ammodata.upgrade.capacity"
                : "tooltip.tacz_ammodata.upgrade.other"));

        String key = upgradeTextKey();
        int lines = upgradeTextLines();
        for (int i = 1; i <= lines; i++) {
            tooltip.add(Component.translatable(key + "." + i));
        }
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.upgrade.warn"));
    }

    /** 这件升级对应的文案前缀 */
    private String upgradeTextKey() {
        return switch (type) {
            case CAPACITY -> switch (targetLevel) {
                case 1 -> "tooltip.tacz_ammodata.upgrade.iron";
                case 2 -> "tooltip.tacz_ammodata.upgrade.gold";
                case 3 -> "tooltip.tacz_ammodata.upgrade.diamond";
                default -> "tooltip.tacz_ammodata.upgrade.netherite";
            };
            case EFFICIENCY -> "tooltip.tacz_ammodata.upgrade.efficiency";
            case LUCKY -> "tooltip.tacz_ammodata.upgrade.lucky";
            case SPEED -> "tooltip.tacz_ammodata.upgrade.speed";
        };
    }

    /** 这件升级的文案有几行（和 lang 里的条目数对齐） */
    private int upgradeTextLines() {
        return switch (type) {
            case CAPACITY -> 2;      // 说明 + 一句 dede
            case EFFICIENCY -> 7;    // 说明 + 6 句 ranyu/dede 对话
            case LUCKY -> 4;         // 说明 + 3 句 zixiao/dede 对话
            case SPEED -> 4;         // 4 句程序梗
        };
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}