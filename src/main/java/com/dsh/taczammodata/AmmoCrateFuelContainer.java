package com.dsh.taczammodata;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 把 BlockEntity 里的「输入槽」包装成 Container，
 * 让 Slot 能直接读写它（不用再手动同步）。
 */
public class AmmoCrateFuelContainer implements Container {

    private final AmmoCrateBlockEntity crate;

    public AmmoCrateFuelContainer(AmmoCrateBlockEntity crate) {
        this.crate = crate;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return crate.cooking().fuel().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return crate.cooking().fuel();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack cur = crate.cooking().fuel();
        if (cur.isEmpty() || amount <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack out = cur.split(amount);
        if (cur.isEmpty()) {
            crate.cooking().setFuel(ItemStack.EMPTY);
        }
        setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack cur = crate.cooking().fuel();
        crate.cooking().setFuel(ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        crate.cooking().setFuel(stack);
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
        return stack.getItem() instanceof AmmoMaterialItem;
    }

    @Override
    public void clearContent() {
        crate.cooking().setFuel(ItemStack.EMPTY);
    }
}
