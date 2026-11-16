package com.dsh.taczammodata;

import com.tacz.guns.api.item.IAmmo;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 右槽（TaCZ 子弹）的容器包装。
 * 槽里放进去的子弹会被 server tick 立刻折算成待分配点数。
 */
public class AmmoCrateAmmoContainer implements Container {

    private final AmmoCrateBlockEntity crate;

    public AmmoCrateAmmoContainer(AmmoCrateBlockEntity crate) {
        this.crate = crate;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return crate.cooking().ammoInput().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return crate.cooking().ammoInput();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack cur = crate.cooking().ammoInput();
        if (cur.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack out = cur.split(amount);
        if (cur.isEmpty()) {
            crate.cooking().setAmmoInput(ItemStack.EMPTY);
        }
        setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack cur = crate.cooking().ammoInput();
        crate.cooking().setAmmoInput(ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        crate.cooking().setAmmoInput(stack);
        setChanged();
    }

    @Override
    public void setChanged() {
        crate.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return IAmmo.getIAmmoOrNull(stack) != null;
    }

    @Override
    public void clearContent() {
        crate.cooking().setAmmoInput(ItemStack.EMPTY);
    }
}