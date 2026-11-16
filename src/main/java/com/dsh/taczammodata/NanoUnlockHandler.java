package com.dsh.taczammodata;

import com.tacz.guns.api.item.IAmmo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 纳米通道解锁。
 *
 * 主人 2026-11-08 定的路子：不用配方、不碰任何枪包文件，直接
 * **拿着纳米弹药，潜行右键**，塞 10 个进能量挂带 → 永久解锁纳米枪走电量。
 *
 * 首次捡到纳米弹药会在聊天栏提示一次（“似乎能用带电离子仿制”）。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID)
public final class NanoUnlockHandler {

    private NanoUnlockHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
        if (ammo == null) {
            return;
        }
        ResourceLocation ammoId = ammo.getAmmoId(stack);
        if (!EnergyStore.isNanoAmmo(ammoId)) {
            return;
        }

        if (!player.isShiftKeyDown()) {
            player.displayClientMessage(Component.literal(
                    "§b潜行 + 右键§7：把纳米弹药塞进能量挂带（集齐 10 个解锁纳米通道）"), true);
            return;
        }
        if (!EnergyStore.isUnlocked(player)) {
            player.displayClientMessage(Component.literal(
                    "§c先装一条背包电源，再往里面塞纳米样本"), true);
            return;
        }
        if (EnergyStore.isNanoUnlocked(player)) {
            player.displayClientMessage(Component.literal(
                    "§a纳米通道已经解锁了，不用再喂"), true);
            return;
        }

        int need = Config.nanoUnlockCount - EnergyStore.getNanoSamples(player);
        int take = Math.min(need, stack.getCount());
        if (take <= 0) {
            return;
        }
        stack.shrink(take);
        boolean unlocked = EnergyStore.addNanoSample(player, take);

        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.2F);
        event.setCanceled(true);

        if (unlocked) {
            player.displayClientMessage(Component.literal(
                    "§d带电离子仿制成功！纳米通道已解锁，纳米武器现在吃电量了")
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        } else {
            player.displayClientMessage(Component.literal(String.format(
                    "§b纳米样本 %d / %d", EnergyStore.getNanoSamples(player), Config.nanoUnlockCount)), true);
        }
    }

    /** 第一次碰到纳米弹药，提示一次 */
    @SubscribeEvent
    public static void onTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 40 != 0) {
            return;
        }
        if (EnergyStore.isNanoUnlocked(player)) {
            return;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
            if (ammo == null || !EnergyStore.isNanoAmmo(ammo.getAmmoId(stack))) {
                continue;
            }
            if (EnergyStore.markNanoHinted(player)) {
                player.displayClientMessage(Component.literal(
                        "§d这弹药似乎能用带电离子仿制……拿着它潜行右键背包电源试试"), false);
            }
            return;
        }
    }
}
