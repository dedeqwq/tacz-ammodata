package com.dsh.taczammodata;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 应急弹药补给包 —— **弹药池制**。
 *
 * ===== 规则（主人 2026-11-07 定）=====
 *
 * 一条补给包 = 一个弹药池（初级 200 / 中级 400 / 高级 800 点）。
 *  ① 按快捷栏 1→9 扫，看有哪几把枪
 *  ② 每类「缺多少补多少」，从池子扣
 *  ③ 池子扣完 → 变成空壳（不消失，能重新灌满，2026-11-09 主人定）
 *  ④ 池子**一次性用光**：买不起整发的零头弹药点也直接记进弹药表，不留零头
 *  ⑤ 一发都没补到 → 不扣池子、不消耗
 *
 * 耐久条 = 池子余量。
 *
 * ⚠️ 2026-11-09 主人改：**弹药点制**。
 *    以前用高级枪（狙击 8 点/发）时会剩 1~4 点买不起一发，结果留在包里白白浪费。
 *    现在剩下的零头点直接写进弹药表（表本来就按「点」存），
 *    下一次补给时这些零头会和新弹药点一起凑成整发，不再浪费。
 */
public class EmergencyPackItem extends Item {

    private static final String TAG_REMAINING = "Remaining";


    /** 这一件物品固定是几档 */
    private final PackTier tier;

    public EmergencyPackItem(Properties properties, PackTier tier) {
        // 2026-11-09：可以堆叠了（初级 16 / 中级 12 / 高级 10）。
        //
        // 11-07 那次「一个包用空、剩 15 个全空」的坑，根因不是堆叠本身，
        // 而是**改了一个 count>1 的堆叠的 NBT**。现在两条防线：
        //   ① 余量不同 → tag 不同 → MC 不会把它们合在一起；
        //   ② use() 一旦发现 count>1，先把这一件拆成独立副本再动它（见 use）。
        super(properties.stacksTo(tier.stackLimit));
        this.tier = tier;
    }

    // ==================== 池子读写 ====================

    /**
     * 池子还剩多少。
     *
     * ⚠️ 2026-11-09 主人定：**没有余量数据 = 空包**（合成出来是空壳，
     * 要拿弹药箱右键灌满才变成能用的弹药包）。
     * 以前这里是「没数据就当满」，那等于合成即送 200 发。
     */
    public int remaining(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_REMAINING)) {
            return 0;
        }
        return tag.getInt(TAG_REMAINING);
    }

    /**
     * 现在能不能往里面灌（2026-11-09 主人定）。
     *
     *   铁箱：随时能灌，剩多少都行。
     *   纸包 / 铁罐：**必须把弹药点彻底用空**（remaining == 0）才能灌。
     *     纸包用完就消失，所以能灌的永远是工作台新造的那只；
     *     铁罐用空后留壳，空壳可以重新灌满 —— 但半满的时候灌不进去。
     */
    private boolean canRefill(ItemStack stack) {
        return tier.refillable || remaining(stack) <= 0;
    }

    /** 是不是已经灌满了（"满了"和"必须用空"是两种提示，得分开） */
    public boolean isFull(ItemStack stack) {
        return remaining(stack) >= tier.capacity();
    }

    /** 灌满（给弹药箱用）：返回实际灌进多少点 */
    public int fill(ItemStack stack, int points) {
        if (!canRefill(stack)) {
            return 0;
        }
        int cap = tier.capacity();
        int cur = remaining(stack);
        int add = Math.max(0, Math.min(points, cap - cur));
        if (add > 0) {
            setRemaining(stack, cur + add);
        }
        return add;
    }

    /**
     * 整摞直接灌满（创造弹药箱用）。
     *
     * 能叠在一起，就说明整摞的余量完全一样（NBT 相同），
     * 所以一摞共用一个 tag、一起改是安全的——这也是 11-07 那个坑的反面用法。
     *
     * ⚠️ 2026-11-09 主人定：新造的空包都能灌一次；
     *    用空后的壳，**只有高级铁箱**还能再灌，铁罐会返回 0，由调用方提示玩家。
     *
     * @return 实际灌进去多少点
     */
    public int fillFull(ItemStack stack) {
        if (!canRefill(stack)) {
            return 0;
        }
        int cap = tier.capacity();
        int cur = remaining(stack);
        if (cur >= cap) {
            return 0;
        }
        setRemaining(stack, cap);
        return cap - cur;
    }

    /** 是不是空包 */
    public boolean isEmpty(ItemStack stack) {
        return remaining(stack) <= 0;
    }

    // ==================== 给弹药台查询用 ====================

    /** 这一档能不能复装（只有高级铁箱是 true） */
    public boolean tierRefillable() {
        return tier.refillable;
    }

    /** 这一档的显示名（初级/中级/高级） */
    public String tierName() {
        return tier.displayName;
    }

    /** 这一档用空后会不会消失 */
    public boolean tierVanishes() {
        return tier.vanishesWhenEmpty;
    }

    private void setRemaining(ItemStack stack, int value) {
        if (value <= 0) {
            // 空包统一「不带 Remaining」，和合成出来的空包保持同一个 NBT —— 否则两者叠不到一起。
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(TAG_REMAINING);
                if (tag.isEmpty()) {
                    stack.setTag(null);
                }
            }
            return;
        }
        stack.getOrCreateTag().putInt(TAG_REMAINING, value);
    }

    // ==================== 使用 ====================

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }

        int pool = remaining(stack);
        if (pool <= 0) {
            sp.displayClientMessage(
                    Component.literal("§7这个是空包——拿它右键弹药箱才能灌弹")
                            .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }

        // 堆叠里拆一件出来单独算账。
        //
        // 只要动 NBT，就绝不能直接动 count>1 的那一摞——那摞里所有包共用一份 tag。
        // 拆出来的独立副本随便改，手上这摞只做 shrink(1)，互不影响。
        ItemStack working = stack;
        boolean split = stack.getCount() > 1;
        if (split) {
            if (sp.getInventory().getFreeSlot() < 0) {
                sp.displayClientMessage(
                        Component.literal("§c背包满了——先腾一个格子，补给包不能掉地上")
                                .withStyle(ChatFormatting.RED), true);
                return InteractionResultHolder.fail(stack);
            }
            working = stack.copy();
            working.setCount(1);
            stack.shrink(1);
        }

        int[] result = refillFromHotbar(sp, pool);
        int used = result[0];        // 补了多少发（整发）
        int left = result[1];        // 池子还剩多少点
        // 池子被消耗过（整发 + 零头都算）—— 注意 used==0 也可能已经灌了零头进表
        boolean consumed = left < pool;

        if (!consumed) {
            // 一点都没补到：拆出来的那个原样塞回背包，池子一点没扣
            if (split) {
                sp.getInventory().add(working);
            }
            sp.displayClientMessage(
                    Component.literal("没有需要补充的弹药（枪都满了 / 池子不够补这类）")
                            .withStyle(ChatFormatting.GRAY), true);
            player.getCooldowns().addCooldown(this, Config.emergencyCooldownTicks);
            return InteractionResultHolder.success(stack);
        }

        setRemaining(working, left);

        player.getCooldowns().addCooldown(this, Config.emergencyCooldownTicks);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8F, 1.4F);

        // 2026-11-16 主人定：一次补一种、不留零头 —— 补不完的点数留在包里，不灌零头
        if (used > 0) {
            sp.displayClientMessage(
                    Component.literal("补充了 " + used + " 发（一种弹药），剩余 " + left + " 点留在包里")
                            .withStyle(ChatFormatting.GREEN), true);
        } else {
            sp.displayClientMessage(
                    Component.literal("没有整发可补（枪都满了 / 池子不够补这一发）")
                            .withStyle(ChatFormatting.YELLOW), true);
        }

        // ★ 关键一步：把账本抄回枪上
        //
        // 原因（主人实测「满弹夹少 5 发」）：
        //   补给包只改了 AmmoDataStore 的账本，但 TaCZ 换弹时是读枪上的 DummyAmmo
        //   来决定能抽多少子弹的。账本补到 120、枪上还是 45 → 换弹只抽得到 45
        //   → 弹夹填不满 → 看起来「少了几发」。
        //
        //   这里把所有快捷栏的枪同步一遍，账本和枪就一致了。
        ReloadHandler.syncGunsToTable(sp);

        AmmoNetwork.syncAll(sp);

        // 2026-11-09 主人定（三档规则）：
        //   纸包：用空 → 物品直接消失
        //   铁罐：用空 → 留空壳，空壳可以重新灌满（半满时不许灌）
        //   铁箱：用空 → 留空箱，随时可灌
        boolean vanished = left <= 0 && tier.vanishesWhenEmpty;

        if (vanished) {
            if (split) {
                // 拆出来的那一件也要销毁，别塞回背包
                working.setCount(0);
            } else {
                // 单件：直接清空手上的物品
                stack.setCount(0);
            }
        } else if (split) {
            sp.getInventory().add(working);
        }

        if (left <= 0) {
            if (tier.vanishesWhenEmpty) {
                sp.displayClientMessage(
                        Component.literal("纸包用完了，已经没了")
                                .withStyle(ChatFormatting.YELLOW), true);
            } else if (tier.refillable) {
                sp.displayClientMessage(
                        Component.literal("箱子空了——右键弹药台可以重新灌满")
                                .withStyle(ChatFormatting.YELLOW), true);
            } else {
                sp.displayClientMessage(
                        Component.literal("罐子空了，这个罐子不能复装")
                                .withStyle(ChatFormatting.YELLOW), true);
            }
        }

        return InteractionResultHolder.success(stack);
    }

    // ==================== 核心：按快捷栏顺序补 ====================

    /**
     * 从快捷栏 1→9 扫枪，缺多少补多少，直到池子耗尽。
     *
     * **按弹药价值倍率扣池子**（Config.ammoCost）：
     *   手枪/冲锋 1 倍、步枪 2、机枪 3、霰弹 5、狙击 8、火箭 20。
     *
     * ⚠️ 2026-11-09 主人改（弹药点制）：**池子剩下的零头不再留在包里**。
     *
     *   以前：狙击 8 点/发，池子剩 3 点 → 买不起一发 → 3 点留在包里，
     *         下次再补时又单独算一遍，跟新的弹药点对不上，看着像「越用越少」。
     *   现在：补完之后，剩下的零头点**直接写进最后一个还得补的表**
     *         （表本身就是按「点」存的，AmmoDataStore.add 收任意点数），
     *         包一次性清零。这样「一包弹药点 = 一次性全部灌进表里」，
     *         表里的点数由玩家自己的枪按权重去花，不再有零头卡在包里。
     *
     *         ⚠️ 这么做会不会超过容量？不会 —— AmmoDataStore.set 会自动夹到上限。
     *         如果所有表都满了，那零头确实无处可去，才真正留下（此时 budget 归零无所谓，
     *         因为主人要的是「别在包里留零头」，表满了本来就没救）。
     *
     * @param budget 池子剩余点数
     * @return int[]{ 补了多少发, 池子还剩多少 }
     */
    private static int[] refillFromHotbar(ServerPlayer player, int budget) {
        int totalRounds = 0;
        AmmoCategory lastFilled = null;
        Set<AmmoCategory> done = EnumSet.noneOf(AmmoCategory.class);

        // 2026-11-16（主人定）：模式体系整体删除（ALL / HELD / DEPLOY），
        //   现在只有唯一一种行为 = 原来的 ALL：
        //     扫快捷栏 1→9 + 副手，每类各补一次，缺多少补多少。
        //
        // 为什么删：
        //   · HELD 用包时手上必然是包，「只补手持那把枪」永远找不到枪（曾经死锁）
        //   · DEPLOY 要先把包放地上、再拿枪右键，操作绕
        //   · 只剩一种行为，就不会有"模式配错导致补不出去"的坑

        // 2026-11-16（补全双持）：快捷栏 1→9 之后再加副手槽（40），
        // 否则副手那把枪永远补不到 —— 主手轮得到、副手轮不到。
        for (int idx = 0; idx < 10 && budget > 0; idx++) {
            int slot = idx < 9 ? idx : 40;
            ItemStack gun = idx < 9 ? player.getInventory().getItem(slot) : player.getOffhandItem();
            if (gun.isEmpty()) {
                continue;
            }
            // 能量枪不吃弹药表（走电量），补给包跳过它
            if (ReloadHandler.isEnergyGun(gun)) {
                continue;
            }
            AmmoCategory cat = AmmoCategory.ofGun(gun);
            if (cat == null || !done.add(cat)) {
                continue;
            }
            int cap = AmmoDataStore.capacity(player, cat);
            int cur = AmmoDataStore.get(player, cat);
            // 表里是「点」，先换成这把枪还能吃几发
            int weight = AmmoWeights.ofGun(gun);
            int lack = AmmoWeights.rounds(cap - cur, weight);
            if (lack <= 0) {
                continue;
            }

            int cost = Math.max(1, Config.ammoCost(cat));
            int affordable = budget / cost;
            if (affordable <= 0) {
                // 买不起整发，但这一类还缺——先记下，等零头统一处理
                if (lastFilled == null) {
                    lastFilled = cat;
                }
                continue;
            }
            int give = Math.min(lack, affordable);
            int addedPoints = AmmoDataStore.add(player, cat, AmmoWeights.points(give, weight));
            int added = AmmoWeights.rounds(addedPoints, weight);
            budget -= added * cost;
            totalRounds += added;
            lastFilled = cat;
        }

        // 2026-11-16 主人定：不留零头 —— 补不完的点数留在包里，下次接着用
        return new int[]{totalRounds, budget};
    }

    // ==================== 显示：耐久条 = 池子 ====================

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int cap = tier.capacity();
        int left = remaining(stack);
        return cap <= 0 ? 0 : Math.round(13.0F * left / cap);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return tier.barColor;
    }

    // ==================== 提示 ====================

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        int cap = tier.capacity();
        int left = remaining(stack);

        tooltip.add(Component.literal(tier.displayName + "弹药补给包")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("剩余 " + left + " / " + cap + " 弹药点")
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.literal("右键：一次补充一种弹药（按 1→9 号位找第一把没满的枪）")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("补不完的零头留在包里，下次接着用")
                .withStyle(ChatFormatting.GRAY));
        // 三档各自的使用规则（2026-11-09 主人定）
        if (tier.vanishesWhenEmpty) {
            tooltip.add(Component.literal("一次性：用完直接消失")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (tier.refillable) {
            tooltip.add(Component.literal("用完留着空箱，随时可以重新灌满")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else if (left > 0) {
            tooltip.add(Component.literal("必须把里面的弹药完全用完，才能重新封装")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.literal("空罐：右键弹药台可以重新灌满")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        for (String line : tier.flavor) {
            tooltip.add(Component.literal(line).withStyle(ChatFormatting.WHITE));
        }
    }

}
