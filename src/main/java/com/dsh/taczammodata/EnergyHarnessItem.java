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
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 背包电源 —— 装上之后能量武器走电量。
 *
 * ===== 2026-11-08 改版（主人实测充电台充不进去）=====
 *
 * 旧版：用一次就变成玩家身上的等级，物品消失 → 充电台「往物品里充电」，我们身上没东西可充。
 * 新版：**物品留着，自带 Forge 能量能力**（IEnergyStorage）。
 *   - 充电台 / 能量立方 会直接给它充电（Mekanism 的充能逻辑就是遍历背包物品）
 *   - 我们每 tick 把它里面的电倒进玩家的能量池（{@link EnergyStore}）
 *
 * 所以现在挂带既是「等级证书」，也是「电池」。
 */
public class EnergyHarnessItem extends Item {

    /** 每级风味文案（0=基础 1=铁 2=金 3=钻石 4=下界合金），主人 2026-11-08 写的 */
    private static final String[][] FLAVOR = {
            {},
            {
                    "你找了一些铁架和大部分工具重新固定，",
                    "并增加了一倍电容体积",
            },
            {
                    "听说黄金对于导电方面有非常大的帮助，",
                    "你重新设计了一下供电装置，将大部分功能的线缆都改成纯金线缆，",
                    "哦，这也太奢侈了",
            },
            {
                    "为解决散热问题，你选择了金刚石，有着极强的散热效果还不导电，",
                    "是极佳的绝缘材料，虽然十分贵，",
                    "但这也能保证你的电池组可以一直稳定地工作，",
                    "短时间再也不需要一直充电了",
            },
            {
                    "你联系到了某些特别的人物，他们愿意以大量的下界合金锭为前提，",
                    "向你交换一整套曾经为某个特别战舰供电的电池组，",
                    "极其高的能量密度，使得在你这挂带里堪比无尽能源般，",
                    "容量更是达到了不敢想象的程度",
            },
    };


    private static final String KEY_ENERGY = "Energy";

    private final int targetTier;

    public EnergyHarnessItem(Properties properties, int targetTier) {
        super(properties.stacksTo(1));
        this.targetTier = Math.max(0, Math.min(targetTier, 4));
    }

    public int getTargetTier() {
        return targetTier;
    }

    public int capacity() {
        return Config.energyCapacity(targetTier);
    }

    // ==================== 物品自带的电量 ====================

    public int getStored(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : Math.max(0, Math.min(tag.getInt(KEY_ENERGY), capacity()));
    }

    public void setStored(ItemStack stack, int rf) {
        stack.getOrCreateTag().putInt(KEY_ENERGY, Math.max(0, Math.min(rf, capacity())));
    }

    /** 把物品里的电抽出来（给能量池用） */
    public int drain(ItemStack stack, int max) {
        int stored = getStored(stack);
        int move = Math.min(stored, Math.max(0, max));
        if (move > 0) {
            setStored(stack, stored - move);
        }
        return move;
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new HarnessCapabilityProvider(this, stack);
    }

    /**
     * 能量能力提供者 —— **必须是具名静态内部类**。
     *
     * 2026-11-09 崩溃教训：原来写成匿名 `ICapabilityProvider` + 匿名 `IEnergyStorage`，
     * 编出来是 `EnergyHarnessItem$1` 和 `$1$1`。jar 里明明有这俩 class、
     * 本地 javap 也能解析，但 Forge 的 ModuleClassLoader 运行时就是
     * `NoClassDefFoundError: EnergyHarnessItem$1$1`（新地图一进就崩）。
     * 换成具名静态内部类后类名不再是编译器自动编号，绕开这个坑。
     */
    private static final class HarnessCapabilityProvider implements ICapabilityProvider {
        private final EnergyHarnessItem item;
        private final ItemStack stack;
        private final LazyOptional<IEnergyStorage> holder;

        private HarnessCapabilityProvider(EnergyHarnessItem item, ItemStack stack) {
            this.item = item;
            this.stack = stack;
            this.holder = LazyOptional.of(() -> new HarnessEnergyStorage(item, stack));
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable net.minecraft.core.Direction side) {
            return cap == ForgeCapabilities.ENERGY ? holder.cast() : LazyOptional.empty();
        }
    }

    /** 背包电源的 IEnergyStorage 实现（同样不用匿名类） */
    private static final class HarnessEnergyStorage implements IEnergyStorage {
        private final EnergyHarnessItem item;
        private final ItemStack stack;

        private HarnessEnergyStorage(EnergyHarnessItem item, ItemStack stack) {
            this.item = item;
            this.stack = stack;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int space = item.capacity() - item.getStored(stack);
            int add = Math.min(space, Math.max(0, maxReceive));
            if (add > 0 && !simulate) {
                item.setStored(stack, item.getStored(stack) + add);
            }
            return add;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int stored = item.getStored(stack);
            int take = Math.min(stored, Math.max(0, maxExtract));
            if (take > 0 && !simulate) {
                item.setStored(stack, stored - take);
            }
            return take;
        }

        @Override
        public int getEnergyStored() {
            return item.getStored(stack);
        }

        @Override
        public int getMaxEnergyStored() {
            return item.capacity();
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
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

        int cur = EnergyStore.getTier(sp);
        if (cur >= targetTier) {
            sp.displayClientMessage(Component.literal(String.format(
                    "身上已经是 %s级电源了，这个用不上", Config.tierName(cur)))
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(stack);
        }
        if (cur != targetTier - 1) {
            sp.displayClientMessage(Component.literal(String.format(
                    "得先装 %s级电源才能用这个（当前 %s）",
                    Config.tierName(targetTier - 1),
                    cur < 0 ? "没装" : Config.tierName(cur) + "级"))
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(stack);
        }

        EnergyStore.setTier(sp, targetTier);

        // ===== 消耗规则（2026-11-16 主人定稿）=====
        //
        //   全部等级（基础 0 / 铁 1 / 金 2 / 钻 3 / 下界 4）→ **装上后一律消耗**。
        //
        //   和弹药袋（AmmoTierItem）完全一致的模型：
        //     · 物品是"一次性的升级凭证"
        //     · 等级记在玩家身上（EnergyStore.Tier），死了也不丢（death.keepTier）
        //     · 背包里没装的那件，死了就正常掉落
        //
        //   为什么不再"基础挂带保留"：
        //     主人原话 —— 「他和那个普通挂带一样，装备了之后等级可以升上去」。
        //     弹药袋就是这么做的（铜/铁/金/钻/下界都消耗），挂带跟它统一。
        //     电量来源不依赖身上带物品：背包里的通量容器/电容/电池，
        //     或者站在充电方块旁边，EnergyChargeHandler 本来就支持这些。
        if (!sp.isCreative()) {
            stack.shrink(1);
        }

        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                SoundEvents.ARMOR_EQUIP_CHAIN, SoundSource.PLAYERS, 0.9F, 1.1F);

        sp.displayClientMessage(Component.literal(String.format(
                "背包电源装上：%s级，电量上限 %d RF §7（等级记在身上，物品已消耗）",
                Config.tierName(targetTier), EnergyStore.capacity(sp)))
                .withStyle(ChatFormatting.AQUA), true);
        sp.getCooldowns().addCooldown(this, 10);

        if (Config.logReload) {
            TaczAmmoData.LOGGER.info("[弹药数据] {} 装上 {}级背包电源（上限 {} RF）",
                    sp.getName().getString(), Config.tierName(targetTier), EnergyStore.capacity(sp));
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal(targetTier == 0
                        ? "§7能量系统的入口：装上后能量武器改吃电量，不再消耗实体弹药"
                        : "§7把挂带升到下一级")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(String.format("电量 %d / %d RF",
                        getStored(stack), capacity()))
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.literal("§e需要储电模块供能§7：充电台 / 电容 / 电池 / 能量立方")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("§8它的电来自那些模块，自己不会发电")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("右键接上§c物品消耗§7，等级记在身上；必须按顺序升级")
                .withStyle(ChatFormatting.DARK_GRAY));
        for (String line : FLAVOR[Math.max(0, Math.min(targetTier, 4))]) {
            tooltip.add(Component.literal(line).withStyle(ChatFormatting.WHITE));
        }
    }
}
