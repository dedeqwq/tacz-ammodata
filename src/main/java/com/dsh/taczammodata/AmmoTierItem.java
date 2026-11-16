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
 * 弹药袋（升级物品）—— 把某一类弹药袋的**等级**往上推一格。
 *
 * ===== 主人要的 5 级升级链 =====
 *
 *   基础(0) → 铁(1) → 金(2) → 钻石(3) → 下界合金(4)
 *
 * 每类弹药各走各的（手枪升到钻石，步枪还是基础）。
 * 以前只能靠 `/ammodata upgrade` 命令，生存里根本走不了这条链；
 * 这个物品就是给它补上「能拿到」的那一环。
 *
 * ===== 怎么用（为什么不直接拿在手上）=====
 *
 * 等级是「哪一类」的等级，所以必须先知道你想升哪一类 —— 那就让玩家把枪拿出来：
 *
 *   **主手拿弹药袋，副手拿要升级的那把枪，右键。**
 *
 * 为什么不「主手拿枪右键」：枪的主手右键是开火/瞄准，根本轮不到我们；
 * 副手物品的 use 也只在主手没动作时才触发。副手放枪才是最干净的方式。
 *
 * 链是**严格顺序**的：铁袋只认「当前是基础」的类别，跳级不给用。
 * 这样和合成链（铁→金→钻石→下界合金，每级拿上一级的袋子当材料）对得上。
 */
public class AmmoTierItem extends Item {

    /**
     * 每级的风味文案（2026-11-08 主人写的）。
     * 钻石那句的「稀世珍宝」用逐字换色做了彩虹效果。
     */
    private static final String[][] FLAVOR = {
            {},
            {
                    "你在小布袋外面缝了一圈铜片和皮革加固，",
                    "比原来能多塞一点点弹药了",
            },
            {
                    "在原有的小布袋上加了几个金属夹、小盒子等一大堆东西，",
                    "更方便携带更多的弹药",
            },
            {
                    "你把自己的弹药挂带镀上了一层黄金，",
                    "似乎多了点附魔和魔法兼容性，",
                    "莫名其妙就获得了「扩容 I」附魔，也许是上天恩赐吧",
            },
            {
                    "利用大部分 §c稀§6世§e珍§a宝§b物§r，你请来了顶尖设计师，",
                    "重新改良了你的弹药挂袋，重构了大部分区块，",
                    "达成了极其完美的人机功效的同时，也比之前更能装了",
            },
            {
                    "这份极其稀少的古代金属，加上你的鬼斧神工，",
                    "成功将你的挂带强化至前无古人、后无来者般的地步，",
                    "你现在看起来就像一个重装兵，获得了额外的防御力加成",
            },
    };


    /** 这个袋子能把弹药袋升到第几级（1=铜 2=铁 3=金 4=钻石 5=下界合金） */
    private final int targetTier;

    public AmmoTierItem(Properties properties, int targetTier) {
        // 可堆叠：每件都一样，没有各自的 NBT（跟补给包不一样，别学它 stacksTo(1)）
        super(properties.stacksTo(16));
        this.targetTier = Math.max(1, Math.min(targetTier, 5));
    }

    public int getTargetTier() {
        return targetTier;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack bag = player.getItemInHand(hand);

        // 目标：副手那把枪的类别
        ItemStack gunStack = player.getOffhandItem();
        AmmoCategory cat = AmmoCategory.ofGun(gunStack);

        if (level.isClientSide()) {
            return InteractionResultHolder.success(bag);
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(bag);
        }
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(bag);
        }

        if (cat == null) {
            sp.displayClientMessage(Component.literal(
                    "把要升级的那把枪拿在副手（默认按 F 交换副手），再右键弹药袋")
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(bag);
        }

        int current = AmmoDataStore.getTier(sp, cat);
        if (current >= targetTier) {
            sp.displayClientMessage(Component.literal(String.format(
                    "%s 已经是 %s级了，这个袋子用不上",
                    cat.displayName, Config.tierName(current)))
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(bag);
        }
        if (current != targetTier - 1) {
            sp.displayClientMessage(Component.literal(String.format(
                    "%s 还停在 %s级，得先升到 %s级才能用这个",
                    cat.displayName, Config.tierName(current), Config.tierName(targetTier - 1)))
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(bag);
        }

        // ===== 升！ =====
        int oldCap = AmmoDataStore.capacity(sp, cat);
        AmmoDataStore.setTier(sp, cat, targetTier);
        int newCap = AmmoDataStore.capacity(sp, cat);

        // 创造模式白嫖，不扣物品
        if (!sp.isCreative()) {
            bag.shrink(1);
        }

        // 枪上的 MaxDummyAmmo 还是老上限，得抄一遍新的，
        // 否则 TaCZ 那边显示的上限要等下次 tick 才跟上（会闪一下）
        ReloadHandler.syncGunsToTable(sp);
        AmmoNetwork.syncAll(sp);

        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.ARMOR_EQUIP_IRON, SoundSource.PLAYERS, 0.9F, 1.2F);

        sp.displayClientMessage(Component.literal(String.format(
                "%s → %s级，容量 %d → %d",
                cat.displayName, Config.tierName(targetTier), oldCap, newCap))
                .withStyle(ChatFormatting.GREEN), true);
        sp.getCooldowns().addCooldown(this, 10);

        if (Config.logReload) {
            TaczAmmoData.LOGGER.info("[弹药数据] {} 用弹药扩容包把 {} 升到 {}级，容量 {} → {}",
                    sp.getName().getString(), cat.displayName,
                    Config.tierName(targetTier), oldCap, newCap);
        }
        return InteractionResultHolder.success(bag);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("把副手那把枪的弹药扩容包升到" + Config.tierName(targetTier) + "级")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("主手拿扩容包 · 副手拿枪 · 右键")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("必须按顺序升：用一次就没了")
                .withStyle(ChatFormatting.DARK_GRAY));
        for (String line : FLAVOR[Math.max(0, Math.min(targetTier, 5))]) {
            tooltip.add(Component.literal(line).withStyle(ChatFormatting.WHITE));
        }
        if (targetTier == 5) {
            tooltip.add(Component.literal("§6任意一类弹药袋升到下界合金后额外 +8 护甲（多类不叠加）"));
        }
    }
}
