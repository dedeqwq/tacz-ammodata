package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 弹药存储方块的「热源检测 + 爆炸」公共逻辑（2026-11-16 抽出来给箱子复用）。
 *
 * ===== 为什么抽成工具类 =====
 * 以前只有 {@link AmmoStationBlockEntity} 自带火焰自爆，三个弹药箱完全没有 ——
 * 但箱子文案里写着「散装弹药易燃易爆」，行为对不上。
 * 现在弹药台和三种弹药箱共用这一套，规矩统一。
 *
 * ===== 两条规矩（2026-11-09 主人定的，现在扩展到箱子）=====
 *   ① 周围一圈（radius=1，3×3×3）有火/岩浆 → **直接炸**
 *   ② 周边 16 格有热源 → 弹一条动作栏警告（不炸）
 *
 * 认哪些方块：火、灵魂火、营火、灵魂营火、岩浆块、任何岩浆流体。
 */
public final class HazardUtil {

    /** 警告间隔（tick）：30 秒内同一条警告不重复 */
    public static final int WARN_INTERVAL_TICKS = 600;

    private HazardUtil() {
    }

    /**
     * 扫一圈找热源。
     *
     * @param radius 水平/垂直半径（1 = 贴身 3×3×3，16 = 远处警告用）
     * @return 找到热源返回 true
     */
    public static boolean hasHeat(Level level, BlockPos pos, int radius) {
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    BlockPos p = pos.offset(dx, dy, dz);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(p);
                    if (s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE)
                            || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE)
                            || s.is(Blocks.MAGMA_BLOCK)
                            || s.getFluidState().is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 贴身热源自爆：周围一圈有火 → 方块自己炸掉。
     *
     * @param power 爆炸威力
     * @return 真的炸了返回 true
     */
    public static boolean selfDetonateIfHeated(Level level, BlockPos pos, float power) {
        if (!hasHeat(level, pos, 1)) {
            return false;
        }
        TaczAmmoData.LOGGER.warn("[弹药] {} 被火焰/岩浆引燃，爆炸（威力 {}）", pos, power);
        level.removeBlock(pos, false);
        level.explode(null,
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                power, Level.ExplosionInteraction.BLOCK);
        return true;
    }

    /**
     * 远处热源警告：周边 16 格内有热源就弹一条，不炸。
     *
     * 分段扫描（2026-11-16 性能）：
     *   · 近处半径 5 每 40 tick（2 秒）查一次 —— 真正会炸的范围
     *   · 远处半径 16 每 200 tick（10 秒）查一次 —— 只为了那条警告
     */
    public static void warnNearbyHeat(Level level, BlockPos pos, long ticks, long[] warnUntil) {
        if (ticks < warnUntil[0]) {
            return;
        }
        boolean nearHeat = ticks % 40 == 0 && hasHeat(level, pos, 5);
        boolean farHeat = ticks % 200 == 0 && hasHeat(level, pos, 16);
        if (!nearHeat && !farHeat) {
            return;
        }
        warnUntil[0] = ticks + WARN_INTERVAL_TICKS;
        var players = level.getEntitiesOfClass(
                net.minecraft.server.level.ServerPlayer.class,
                new net.minecraft.world.phys.AABB(pos).inflate(24.0D));
        for (var sp : players) {
            sp.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "§c附近有火焰或岩浆！再靠近就会炸，快清掉热源"), true);
        }
    }

    /**
     * 被别的爆炸波及 → 延时一 tick 二次爆炸（殉爆）。
     *
     * 为什么延迟：本方法是从 {@code Block#wasExploded} 调过来的，
     * 那是在 Explosion#finalizeExplosion 的遍历中间，
     * 直接在调用栈里再 explode() 会嵌套 + ConcurrentModification。
     *
     * ⚠️ 2026-11-16（崩溃修复）：延迟期间原爆炸会把方块全清掉，
     *   所以下一 tick 去查 BlockEntity 时**不能假设它还在**，
     *   而且绝不能读那格的 BlockState（空气没有 facing 之类的属性，会抛异常）。
     *   这里只查 BlockEntity，null 就放弃 —— 安全第一。
     *
     * @param power 威力要在**方块还在的时候**算好传进来
     */
    public static void chainDetonate(Level level, BlockPos pos, float power) {
        if (level.isClientSide()) {
            return;
        }
        var server = level.getServer();
        if (server == null) {
            return;
        }
        BlockPos target = pos.immutable();
        server.execute(() -> {
            // 这一 tick 里可能已经被原爆炸带走了；只查 BlockEntity，不碰 BlockState
            if (level.getBlockEntity(target) == null) {
                return;
            }
            TaczAmmoData.LOGGER.warn("[弹药] {} 被爆炸波及，殉爆（威力 {}）", target, power);
            level.removeBlock(target, false);
            level.explode(null,
                    target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D,
                    power, Level.ExplosionInteraction.BLOCK);
        });
    }
}
