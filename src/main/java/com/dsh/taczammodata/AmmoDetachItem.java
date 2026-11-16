package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 扩容拆卸器 —— 把身上那颗「弹药扩容包」拆下来。
 *
 * ===== 主人 2026-11-07 定的规矩 =====
 *
 * 扩容包用掉以后不是真的消失，而是「挂在玩家身上」——
 * 也就是 {@link AmmoDataStore} 里那个按类别存的等级，人死了重进游戏都还在。
 *
 * 这把拆卸器就是那个「拆」的动作：
 *   副手拿枪 → 右键 → 该类等级 -1，并把拆下来的那颗扩容包**原样退回背包**
 *   （4 级拆下来退下界合金包，1 级拆下来退铁包）
 *
 * 拆卸器本身**不会被消耗**（它是装备，不是耗材），只有 10 tick 冷却防连点。
 * 所以它是可逆的：装上去 → 拆下来 → 再装回去都能走。
 */
public class AmmoDetachItem extends Item {

    public AmmoDetachItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack strap = player.getItemInHand(hand);

        AmmoCategory cat = AmmoCategory.ofGun(player.getOffhandItem());

        if (level.isClientSide()) {
            return InteractionResultHolder.success(strap);
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(strap);
        }
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(strap);
        }

        // ===== 副手是能量枪 → 拆能量挂带 =====
        if (ReloadHandler.isEnergyGun(player.getOffhandItem())) {
            return detachEnergy(sp, level, strap);
        }

        if (cat == null) {
            sp.displayClientMessage(Component.literal(
                    "把要拆扩容包的那把枪拿在副手（默认按 F 交换），再右键拆卸器")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(strap);
        }

        int tier = AmmoDataStore.getTier(sp, cat);
        if (tier <= 0) {
            sp.displayClientMessage(Component.literal(
                    cat.displayName + " 还是基础级，身上没有扩容包可拆")
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(strap);
        }

        // ===== 拆！等级 -1，扩容包退回 =====
        int oldCap = AmmoDataStore.capacity(sp, cat);
        AmmoDataStore.setTier(sp, cat, tier - 1);
        int newCap = AmmoDataStore.capacity(sp, cat);

        ItemStack refund = new ItemStack(TaczAmmoData.AMMO_BAGS[tier - 1].get());
        if (!sp.getInventory().add(refund)) {
            // 背包满了就掉在脚下，别凭空吞掉玩家的东西
            sp.drop(refund, false);
        }

        ReloadHandler.syncGunsToTable(sp);
        AmmoNetwork.syncAll(sp);

        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.8F, 0.9F);

        sp.displayClientMessage(Component.literal(String.format(
                "%s 拆下 %s级扩容包 → 回到 %s级（容量 %d → %d），包已退回",
                cat.displayName, Config.tierName(tier), Config.tierName(tier - 1),
                oldCap, newCap))
                .withStyle(ChatFormatting.YELLOW), true);
        sp.getCooldowns().addCooldown(this, 10);

        if (Config.logReload) {
            TaczAmmoData.LOGGER.info("[弹药数据] {} 用拆卸器把 {} 从 {}级拆到 {}级，退还 {}",
                    sp.getName().getString(), cat.displayName,
                    Config.tierName(tier), Config.tierName(tier - 1),
                    refund.getItem().getDescriptionId());
        }
        return InteractionResultHolder.success(strap);
    }

    /**
     * 拆一级能量挂带。
     *
     * ⚠️ **不退物品**（2026-11-08 修无限刷）：
     * 挂带物品现在是「电池」，装上不消失、一直在背包里；如果拆的时候再退一个，
     * 装 → 拆 → 装 → 拆 就能无限复制。所以这里只降等级，不动物品。
     */
    private static InteractionResultHolder<ItemStack> detachEnergy(ServerPlayer sp, Level level, ItemStack strap) {
        int tier = EnergyStore.getTier(sp);
        if (tier < 0) {
            sp.displayClientMessage(Component.literal("身上没装背包电源，没得拆")
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(strap);
        }

        if (tier == 0) {
            EnergyStore.clear(sp);
        } else {
            EnergyStore.setTier(sp, tier - 1);
        }

        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.8F, 0.9F);
        sp.displayClientMessage(Component.literal(String.format(
                "拆下 %s级背包电源（挂带物品保留在背包），上限变成 %d RF",
                Config.tierName(tier), EnergyStore.capacity(sp)))
                .withStyle(ChatFormatting.YELLOW), true);
        sp.getCooldowns().addCooldown(TaczAmmoData.AMMO_DETACH.get(), 10);
        return InteractionResultHolder.success(strap);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        // 文案走 lang（2026-11-16 主人写的），方便以后自己改字不用重编译
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.detach.desc"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.detach.usage"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.detach.reusable"));
        tooltip.add(Component.translatable("tooltip.tacz_ammodata.detach.more"));
    }
}
