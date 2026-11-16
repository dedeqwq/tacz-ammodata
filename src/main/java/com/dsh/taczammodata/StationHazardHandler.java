package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 弹药台的殉爆监听（2026-11-09 主人要求，2026-11-16 三次整改后定稿）。
 *
 * 主人原话：有人朝弹药台打一发火箭弹，只要爆炸效果危及到它，就立刻爆炸。
 * 所以别的爆炸炸到台子时，台子会**二次爆炸** —— 这就是「散装弹药易燃易爆」的体现。
 *
 * ===== 为什么最终挂在 Block#wasExploded =====
 *
 * 试过三个挂载点，全都不可靠：
 *
 * ① {@code ExplosionEvent.Detonate}（原实现）：
 *    反编译 Explosion#finalizeExplosion 字节码，顺序是
 *      偏移 171~205 → 遍历 toBlow 把方块炸掉
 *      偏移 589     → 才 post Detonate 事件
 *    事件触发时方块已经没了，getAffectedBlocks() 读到空表或已消失的方块
 *    → getBlockEntity() 永远 null，**殉爆从来没生效过**（主人实测 TNT 和枪械爆炸都不炸）。
 *
 * ② {@code ExplosionEvent.Start}：
 *    在爆炸开始时触发，方块还在 —— 但它**拿不到"将被波及的方块列表"**
 *    （getToBlow() 那时还是空的），只能自己按半径扫球。
 *    而 Explosion 的 radius 字段是 private final，没有 getter，
 *    要读它就得写 accesstransformer。
 *
 * ③ {@code Block#wasExploded(Level, BlockPos, Explosion)}（**最终方案**）：
 *    MC 在 finalizeExplosion 里**逐个方块**调用它，此时 BlockEntity 还在，
 *    参数直接给 BlockPos + Explosion，不需要任何反射或 AT。
 *    这是唯一"在方块消失前、且知道是哪个方块"的干净钩子。
 *
 * ===== 为什么要延迟一 tick =====
 * wasExploded 是在 Explosion#finalizeExplosion 的遍历中间被调用的，
 * 在那个调用栈里再 level.explode() 会造成嵌套爆炸 + ConcurrentModification。
 * 所以先记下来，用 server.execute 推到本 tick 结束后统一处理。
 */
public final class StationHazardHandler {

    private StationHazardHandler() {
    }

    /**
     * 由 {@link AmmoStationBlock#wasExploded} 调用。
     *
     * @param be 被波及的台子方块实体（调用方已确认非 null）
     */
    public static void onStationCaughtInExplosion(Level level,
                                                  BlockPos pos,
                                                  Explosion explosion,
                                                  AmmoStationBlockEntity be) {
        if (level.isClientSide() || be == null) {
            return;
        }
        var server = level.getServer();
        if (server == null) {
            return;
        }

        BlockPos target = pos.immutable();
        // 威力要在**方块还在的时候**读，延迟后就取不到了
        float power = be.explosionPower();

        server.execute(() -> {
            // 这一 tick 里可能已经被别的爆炸带走了
            if (!(level.getBlockEntity(target) instanceof AmmoStationBlockEntity)) {
                return;
            }
            TaczAmmoData.LOGGER.warn("[弹药台] {} 被爆炸波及，殉爆（威力 {}）", target, power);
            level.removeBlock(target, false);
            level.explode(null,
                    target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D,
                    power, Level.ExplosionInteraction.BLOCK);
        });
    }
}
