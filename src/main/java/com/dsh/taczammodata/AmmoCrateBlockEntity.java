package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 生存弹药箱的方块实体（2026-11-09 起）。
 *
 * 和创造弹药台分工：
 *   台子 = 「变」，凭空填满，点数制、创造专属
 *   箱子 = 「搬」，只能搬运箱子里的存量，生存可合成
 *
 * 三档箱子共用这一个实体，档位从方块本身取（AmmoCrateBlock.tier）。
 *
 * 2026-11-14：客户端同步走 BE 数据广播（getUpdatePacket + setChanged 里
 * sendBlockUpdated），界面上的容量 / 存量 / 待分配都读客户端镜像。
 */
public class AmmoCrateBlockEntity extends BlockEntity {

    private final AmmoCrateData data;
    private final AmmoCrateCooking cooking;

    /** 热源检测计时（2026-11-16 新增：箱子也易燃易爆了） */
    private int hazardTicks = 0;
    /** 警告冷却：到这个 tick 之前不重复提醒 */
    private int warnCooldownUntil = 0;

    public AmmoCrateBlockEntity(BlockPos pos, BlockState state) {
        super(TaczAmmoData.AMMO_CRATE_BE.get(), pos, state);
        AmmoCrateData.Tier tier = state.getBlock() instanceof AmmoCrateBlock crate
                ? crate.tier
                : AmmoCrateData.Tier.BASIC;
        this.data = new AmmoCrateData(tier);
        this.cooking = new AmmoCrateCooking(data);
    }

    public AmmoCrateData data() {
        return data;
    }

    public AmmoCrateCooking cooking() {
        return cooking;
    }

    /**
     * 每 tick：左槽弹料制作 + 右槽子弹折算 + **热源检测**（2026-11-16 新增）。
     * 两者都会改变 pending，变化了才广播，避免每 tick 一包。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  AmmoCrateBlockEntity be) {
        // ===== 热源检测（2026-11-16 新增：箱子也易燃易爆了，和文案对齐）=====
        be.hazardTicks++;
        if (be.hazardTicks % 20 == 0) {
            if (HazardUtil.selfDetonateIfHeated(level, pos, be.explosionPower())) {
                return;   // 炸了就别再跑制作了
            }
            long[] until = {be.warnCooldownUntil};
            HazardUtil.warnNearbyHeat(level, pos, be.hazardTicks, until);
            be.warnCooldownUntil = (int) until[0];
        }

        boolean changed = false;
        if (be.cooking.tick(level.random)) {
            changed = true;
        }
        if (be.cooking.convertAmmo() > 0) {
            changed = true;
        }
        if (changed) {
            be.setChanged();
        }
    }

    /**
     * 殉爆威力（2026-11-16 新增）。
     *
     * 和弹药台不同，箱子的「火药量」就是它存的东西：
     *   · 分类存量各占一定点数
     *   · pending（待分配点数）也算
     *   · 两个输入槽（弹料 / 子弹）也算一点
     *
     * 公式和弹药台保持一个手感：基础 4 + 存量/500，封顶读配置。
     * 所以空箱子挨炸只是普通爆炸，塞满的箱子才是真的会炸出一片坑。
     */
    public float explosionPower() {
        int points = data.totalPoints() + cooking.pending();
        // 输入槽里还有货，多少也算点燃料
        if (!cooking.fuel().isEmpty()) {
            points += cooking.fuel().getCount() * Config.cratePointsPerMaterial;
        }
        if (!cooking.ammoInput().isEmpty()) {
            points += cooking.ammoInput().getCount() * 4;
        }
        float cap = (float) Math.max(4, Config.explosionPowerCap);
        return Math.min(cap, 4.0F + points / 500.0F);
    }

    /**
     * 数据一变就推给附近客户端。
     * 界面里的容量条、分类存量、待分配点数全靠这条链路刷新。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(),
                    this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * ⚠️ 原版默认实现返回**空 CompoundTag**，客户端收到空包后 load 不到 "Crate"，
     * 镜像永远是 0 —— 这就是"里面消耗了但界面不涨条"的根因。
     */
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag root = data.save();
        cooking.save(root);
        tag.put("Crate", root);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Crate")) {
            CompoundTag root = tag.getCompound("Crate");
            data.load(root);
            cooking.load(root);
        }
    }
}