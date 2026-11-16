package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 能量挂带充能。
 *
 * 两个来源，都是问 Forge 要统一接口 {@link ForgeCapabilities#ENERGY}：
 *   ① 玩家附近的方块（Mekanism 充电台 / 能量立方 / 龙研电容 …）
 *   ② 背包里的物品（电容、电池）
 *
 * ⚠️ 之前只查「脚下方块」，结果站在充电台旁边就充不到（实测：台子里 531 kFE，我们看的是草方块）。
 * 现在改成每 20 tick 扫一次周围 radius 格范围，找到带能量的方块就缓存下来，之后每 tick 从缓存抽。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID)
public final class EnergyChargeHandler {

    /** 缓存的充能源坐标（每 20 tick 重新找一次） */
    private static final Map<UUID, BlockPos> SOURCE = new HashMap<>();
    /** 扫描间隔见 Config.chargeScanInterval（2026-11-09 提成配置） */

    private EnergyChargeHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }
        if (!EnergyStore.isUnlocked(player)) {
            return;
        }
        int space = EnergyStore.space(player);
        if (space <= 0) {
            return;
        }

        int got = 0;
        if (Config.energyFromBlocks) {
            got += chargeFromBlocks(player, space);
        }
        if (got < space && Config.energyFromItems) {
            got += chargeFromItems(player, space - got);
        }
        if (got > 0) {
            EnergyStore.add(player, got);
        }
    }

    // ==================== 方块 ====================

    private static int chargeFromBlocks(ServerPlayer player, int need) {
        UUID id = player.getUUID();
        BlockPos pos = SOURCE.get(id);

        // 每 20 tick 重新扫一圈；缓存失效（方块没了/没电了）也重扫
        if (pos == null || player.tickCount % Config.chargeScanInterval == 0) {
            pos = findSource(player);
            if (pos == null) {
                SOURCE.remove(id);
                return 0;
            }
            SOURCE.put(id, pos);
        }

        BlockEntity be = player.level().getBlockEntity(pos);
        if (be == null) {
            SOURCE.remove(id);
            return 0;
        }
        IEnergyStorage storage = be.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
        if (storage == null) {
            SOURCE.remove(id);
            return 0;
        }
        int want = Math.min(Config.energyBlockPerTick, need);
        int got = storage.extractEnergy(want, true);
        if (got <= 0) {
            SOURCE.remove(id);
            return 0;
        }
        got = storage.extractEnergy(got, false);
        if (got > 0 && Config.logReload) {
        }
        return Math.max(0, got);
    }

    /** 在玩家周围找第一个「有能量、抽得动」的方块 */
    private static BlockPos findSource(ServerPlayer player) {
        int r = Math.max(0, Config.energyChargeRadius);
        BlockPos base = player.blockPosition();
        BlockPos best = null;
        int bestEnergy = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    BlockEntity be = player.level().getBlockEntity(p);
                    if (be == null) {
                        continue;
                    }
                    IEnergyStorage storage = be.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
                    if (storage == null) {
                        continue;
                    }
                    int stored = storage.getEnergyStored();
                    if (stored > bestEnergy && storage.extractEnergy(1, true) > 0) {
                        bestEnergy = stored;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    // ==================== 物品 ====================

    private static int chargeFromItems(ServerPlayer player, int need) {
        int want = Math.min(Config.energyItemPerTick, need);
        if (want <= 0) {
            return 0;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            IEnergyStorage storage = stack.getCapability(ForgeCapabilities.ENERGY, null).orElse(null);
            if (storage == null) {
                continue;
            }
            int got = storage.extractEnergy(want, true);
            if (got <= 0) {
                continue;
            }
            got = storage.extractEnergy(got, false);
            if (got > 0) {
                return got;
            }
        }
        return 0;
    }
}
