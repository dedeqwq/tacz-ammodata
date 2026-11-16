package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 弹药台的方块实体 —— 只干一件事：盯着周围的火。
 *
 * 规则（2026-11-09 主人定）：
 *   ① 周边 16 格内有火焰 / 岩浆 / 营火 / 岩浆块 → 给附近玩家弹一条动作栏警告（不弹窗）
 *   ② 紧贴台子的**周围一圈**（3×3×3）有火或岩浆 → **直接爆炸**，台子连同周围一起炸飞
 *
 * 为什么要有这个：弹药台是便宜又无限补给的设备，必须用环境风险来平衡，
 * 不能让它被随手摆在岩浆边上当永久补给站。
 */
public class AmmoStationBlockEntity extends BlockEntity {

    /** 每 20 tick 查一次贴身，每 40 tick 查一次近处、200 tick 查一次远处 */
    private int ticks = 0;
    /** 警告冷却：到这个 tick 之前不再重复提醒（绝对 tick，不是倒数计数器） */
    private int warnCooldownUntil = 0;

    /** 台子里存的弹药点（点数制：右键补表从这里扣，拿子弹右键往里存） */
    private static final String TAG_POINTS = "Points";
    /** 台子是不是在创造模式放下的（创造台殉爆 = 核弹） */
    private static final String TAG_CREATIVE = "CreativePlaced";
    /** 容量读配置 `tuning.stationPoints.small`，默认 5000；但**初始点数是 0** */
    private int points = 0;   // 2026-11-16 修（C6）：以前初始化成容量值，新台子直接满 5000 点，生存玩家摸到就白补
    private boolean creativePlaced = false;

    public boolean isCreativePlaced() {
        return creativePlaced;
    }

    public void setCreativePlaced(boolean value) {
        creativePlaced = value;
        setChanged();
    }

    /**
     * 殉爆威力（2026-11-09 主人要求：点数越多炸得越狠）。
     *
     *   普通台：4 + 弹药点 / 500，封顶 {@link Config#explosionPowerCap}
     *     · 5000 点 → 14   · 20000 点 → 44   · 80000 点 → 64
     *   创造台：{@link Config#creativeExplosionPower}（主人要的「核弹」）
     *
     * ⚠️ 2026-11-14 修：这里原来把 64 / 100 写死了，
     *   `tuning.explosionPowerCap` 和 `tuning.creativeExplosionPower` 两个配置项
     *   从头到尾没人读 —— 改了不生效。
     */
    public float explosionPower() {
        if (creativePlaced) {
            return (float) Math.max(4, Config.creativeExplosionPower);
        }
        float cap = (float) Math.max(4, Config.explosionPowerCap);
        return Math.min(cap, 4.0F + points / 500.0F);
    }

    public int getPoints() {
        return points;
    }

    /**
     * 台子能存多少点。
     *
     * ⚠️ 2026-11-14 修：`tuning.stationPoints.small` 以前只是**初始值**，
     *   `addPoints` 完全不做上限 —— 一直右键塞子弹/弹料就能无限堆点数，
     *   而且堆得越大殉爆越狠（威力按 points 算），跟配置里写的「容量」对不上。
     */
    public int capacity() {
        return Math.max(1, Config.stationPointsSmall);
    }

    public void setPoints(int value) {
        points = Math.max(0, Math.min(value, capacity()));
        setChanged();
    }

    /** 加点数，返回**实际加进去多少**（放不下的部分由调用方决定退不退货） */
    public int addPoints(int value) {
        if (value <= 0) {
            return 0;
        }
        int before = points;
        setPoints(points + value);
        return points - before;
    }

    public AmmoStationBlockEntity(BlockPos pos, BlockState state) {
        super(TaczAmmoData.AMMO_STATION_BE.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, AmmoStationBlockEntity be) {
        if (level.isClientSide()) {
            return;
        }
        be.ticks++;
        if (be.ticks % 20 != 0) {
            return;
        }

        // ① 贴身热源（3×3×3）→ 直接炸
        if (HazardUtil.selfDetonateIfHeated(level, pos, be.explosionPower())) {
            return;
        }

        // ② 远处热源 → 警告（近处 2 秒查、远处 10 秒查，同一条 30 秒不重复）
        long[] until = {be.warnCooldownUntil};
        HazardUtil.warnNearbyHeat(level, pos, be.ticks, until);
        be.warnCooldownUntil = (int) until[0];
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(TAG_POINTS, points);
        tag.putBoolean(TAG_CREATIVE, creativePlaced);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        points = tag.contains(TAG_POINTS) ? tag.getInt(TAG_POINTS) : 0;   // 2026-11-16 修（C6）：缺省 0，不再回落成满容量
        creativePlaced = tag.getBoolean(TAG_CREATIVE);
    }

}