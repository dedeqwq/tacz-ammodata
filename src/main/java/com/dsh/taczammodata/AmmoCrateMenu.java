package com.dsh.taczammodata;

import com.tacz.guns.api.item.IAmmo;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 弹药箱界面容器（2026-11-14 第四轮）。
 *
 * 两个输入槽：
 *   0 = 复合弹料（制作成点数）
 *   1 = TaCZ 子弹（按权重表立刻折算成点数）
 *
 * ⚠️ 按钮语义（第四轮修正）：[+50] / [满] 是把**待分配点数发到玩家弹药表**，
 * 不是塞进箱子自己的分类存量。之前写进 crate.data()，玩家点完身上一点子弹都没有。
 *
 * 分类行显示的是玩家弹药表的「当前 / 上限」；
 * 容量条显示箱子的「待分配点数 / 本档总容量」。
 */
public class AmmoCrateMenu extends AbstractContainerMenu {

    public static final int FUEL_SLOT = 0;
    public static final int AMMO_SLOT = 1;
    public static final int PLAYER_SLOT_START = 2;

    // 每类一次分配多少点走 Config.crateButtonStep（2026-11-14 配置化）

    public static final int BTN_FILL_OFFSET = 10;
    public static final int BTN_ALL = 99;

    private final AmmoCrateBlockEntity crate;
    private final Container fuelContainer;
    private final Container ammoContainer;
    private final BlockPos pos;
    private final Player owner;
    private final int tierOrdinal;

    // ---- 服务端 ----
    public AmmoCrateMenu(int id, Inventory inv, AmmoCrateBlockEntity crate, BlockPos pos) {
        super(TaczAmmoData.AMMO_CRATE_MENU.get(), id);
        this.crate = crate;
        this.pos = pos;
        this.owner = inv.player;
        this.tierOrdinal = crate.data().tier().ordinal();
        this.fuelContainer = new AmmoCrateFuelContainer(crate);
        this.ammoContainer = new AmmoCrateAmmoContainer(crate);
        addSlots(inv);
    }

    // ---- 客户端 ----
    public AmmoCrateMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        super(TaczAmmoData.AMMO_CRATE_MENU.get(), id);
        BlockPos p = BlockPos.ZERO;
        int tierOrd = 0;
        if (buf != null) {
            p = buf.readBlockPos();
            tierOrd = buf.readVarInt();
        }
        this.pos = p;
        this.owner = inv.player;
        this.tierOrdinal = tierOrd;
        Level level = inv.player.level();
        BlockEntity be = level.getBlockEntity(p);
        this.crate = be instanceof AmmoCrateBlockEntity c ? c : null;
        this.fuelContainer = crate != null
                ? new AmmoCrateFuelContainer(crate)
                : new net.minecraft.world.SimpleContainer(1);
        this.ammoContainer = crate != null
                ? new AmmoCrateAmmoContainer(crate)
                : new net.minecraft.world.SimpleContainer(1);
        addSlots(inv);
    }

    private void addSlots(Inventory inv) {
        // 左槽：复合弹料
        this.addSlot(new Slot(fuelContainer, 0, 26, 26) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof AmmoMaterialItem;
            }
        });
        // 右槽：TaCZ 子弹
        this.addSlot(new Slot(ammoContainer, 0, 52, 26) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return IAmmo.getIAmmoOrNull(stack) != null;
            }
        });

        // 玩家背包
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 16 + col * 18, 170 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inv, col, 16 + col * 18, 224));
        }
    }


    public int pending() {
        return crate == null ? 0 : crate.cooking().pending();
    }

    public int cookProgressPercent() {
        return crate == null ? 0 : crate.cooking().cookProgressPercent();
    }

    public boolean hasFuel() {
        return crate != null && !crate.cooking().fuel().isEmpty();
    }

    /** 玩家弹药表：当前点数 */
    public int playerAmount(AmmoCategory c) {
        if (owner == null) {
            return 0;
        }
        if (owner.level().isClientSide) {
            return AmmoNetwork.ClientAmmoCache.amount(c);
        }
        return AmmoDataStore.get(owner, c);
    }

    /** 玩家弹药表：上限 */
    public int playerCapacity(AmmoCategory c) {
        if (owner == null) {
            return 0;
        }
        if (owner.level().isClientSide) {
            return AmmoNetwork.ClientAmmoCache.capacity(c);
        }
        return AmmoDataStore.capacity(owner, c);
    }

    public boolean allows(AmmoCategory c) {
        return tier().allows(c);
    }

    /** 箱子总容量（本档允许类别之和，含扩容倍率） */
    public int totalCapacity() {
        return crate == null ? 0 : crate.data().genericCapacity();
    }

    /** 当前扩容倍率（1 / 1.5 / 2 / 4 / 6 / 10） */
    public double upgradeMultiplier() {
        return crate == null ? 1.0 : crate.data().upgradeMultiplier();
    }

    /** 某条升级线的等级（界面图标用） */
    public int upgradeLevel(CrateUpgradeType type) {
        return crate == null ? 0 : crate.data().getUpgrade(type);
    }

    public AmmoCrateData.Tier tier() {
        if (crate != null) {
            return crate.data().tier();
        }
        AmmoCrateData.Tier[] all = AmmoCrateData.Tier.values();
        return tierOrdinal >= 0 && tierOrdinal < all.length
                ? all[tierOrdinal]
                : AmmoCrateData.Tier.BASIC;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (crate == null || player.level().isClientSide) {
            return false;
        }
        AmmoCategory[] all = AmmoCategory.values();

        if (id >= 0 && id < all.length) {
            if (grant(player, all[id], Config.crateButtonStep) > 0) {
                finish(player);
            }
            return true;
        }
        if (id >= BTN_FILL_OFFSET && id < BTN_FILL_OFFSET + all.length) {
            if (grant(player, all[id - BTN_FILL_OFFSET], Integer.MAX_VALUE) > 0) {
                finish(player);
            }
            return true;
        }
        if (id == BTN_ALL) {
            if (grantAll(player) > 0) {
                finish(player);
            }
            return true;
        }
        return false;
    }

    /**
     * 从待分配点数里扣点，加到**玩家自己的弹药表**。
     *
     * @return 实际发放到玩家的点数
     */
    private int grant(Player player, AmmoCategory c, int points) {
        if (!tier().allows(c) || points <= 0) {
            return 0;
        }
        int pending = crate.cooking().pending();
        if (pending <= 0) {
            return 0;
        }
        int room = AmmoDataStore.capacity(player, c) - AmmoDataStore.get(player, c);
        if (room <= 0) {
            return 0;
        }
        int use = Math.min(Math.min(points, pending), room);
        if (use <= 0) {
            return 0;
        }
        int added = AmmoDataStore.add(player, c, use);
        crate.cooking().setPending(pending - added);
        return added;
    }

    private int grantAll(Player player) {
        int total = 0;
        for (AmmoCategory c : AmmoCategory.values()) {
            if (!tier().allows(c)) {
                continue;
            }
            total += grant(player, c, Integer.MAX_VALUE);
            if (crate.cooking().pending() <= 0) {
                break;
            }
        }
        return total;
    }

    private void finish(Player player) {
        crate.setChanged();
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            ReloadHandler.syncGunsToTable(sp);
            AmmoNetwork.syncAll(sp);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (index < PLAYER_SLOT_START) {
            // 输入槽 → 背包
            if (!this.moveItemStackTo(stack, PLAYER_SLOT_START, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof AmmoMaterialItem) {
            if (!this.moveItemStackTo(stack, FUEL_SLOT, FUEL_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (IAmmo.getIAmmoOrNull(stack) != null) {
            if (!this.moveItemStackTo(stack, AMMO_SLOT, AMMO_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        if (crate == null) {
            return false;
        }
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }
}