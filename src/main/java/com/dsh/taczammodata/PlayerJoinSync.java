package com.dsh.taczammodata;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家进出游戏时的初始化与同步。
 *
 * 主人原话：「开局就把所有枪的弹药给它标出来」→ 首次进服就按基础容量初始化。
 */
public final class PlayerJoinSync {

    private PlayerJoinSync() {
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 老档等级索引迁移（2026-11-14 插入铜级，1..4 → 2..5）
        AmmoDataStore.migrateTierIndexIfNeeded(player);
        // 首次进服：按基础容量初始化全套弹药表
        if (Config.autoRefillOnSpawn) {
            AmmoDataStore.initIfNeeded(player);
        }
        AmmoNetwork.syncAll(player);
    }

    /**
     * 2026-11-16（D4）：玩家退出时清掉按 UUID 记的运行时表。
     * 以前只进不出，长跑服会缓慢涨内存（每个玩家几十字节，量小但确实是泄漏）。
     */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ReloadHandler.forgetPlayerState(player.getUUID());
            // 容量缓存也一起清（玩家走了就不会再用到）
            ModuleSystem.forgetCapacityCache(player.getUUID());
        }
    }

    /**
     * 登录时也兜一次底（C10 加固）。
     *
     * 有些极端情况不会走 PlayerLoggedOutEvent（服务端崩溃、强制踢出、别的模组截断事件），
     * 玩家重新登录时如果表里还留着他上次的记录，下次死亡复活就会**发出不该发的挂带**。
     * 登录时清掉旧记录是安全的：那时还没死，任何残留都是垃圾。
     */
    @SubscribeEvent
    public static void onLoggedInCleanup(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // 2026-11-17 补：上次崩溃/强踢可能没走 PlayerLoggedOutEvent，
            // 登录时清掉残留的运行态（供弹模式 / 基准账 / 容量缓存），
            // 避免旧记录被下次死亡复活误用。数据在 PersistentData 里，不受影响。
            ReloadHandler.forgetPlayerState(player.getUUID());
            ModuleSystem.forgetCapacityCache(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AmmoNetwork.syncAll(player);
        }
    }

    /**
     * 死亡重生：只有开了「死亡不掉落」才把弹药表带过去。
     * 掉落模式不复制 → 重生后弹药表清零，和物品栏逻辑一致。
     */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }
        // ===== 死亡规则（2026-11-16 主人定：全部可配，不硬编码）=====
        //
        //  keep       = 存量（弹药点数 / 电量）要不要留
        //  keepTier   = 等级（弹药袋 / 挂带）要不要留 —— 配置 death.keepTier
        //
        // 为什么等级默认永留：等级是花材料换来的**能力**，不是可掉落的物品。
        // 实测证据（latest.log，旧版）：
        //   19:21:07  升到 铜级，容量 30 → 45
        //   19:21:16  死亡
        //   19:21:38  再升一次，日志写「容量 30 → 45」← 起点又是 30，等级被清了
        boolean keepStored;
        if (!Config.keepStoredOnDeath) {
            keepStored = false;
        } else if (!Config.crateDropFollowsKeepInventory) {
            keepStored = true;   // 配置说「永远保留」
        } else {
            var level = event.getEntity().level();
            keepStored = level != null && level.getGameRules()
                    .getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        }
        boolean keepTier = Config.keepTierOnDeath || keepStored;
        net.minecraft.nbt.CompoundTag src = event.getOriginal().getPersistentData();
        net.minecraft.nbt.CompoundTag dst = event.getEntity().getPersistentData();
        // ===== 能量挂带数据（等级 / 电量 / 纳米）=====
        // 2026-11-16（主人定）：挂带全程按普通物品处理，**不再做任何死亡保护**。
        //   · 物品死了该掉就掉（没有"摘取/归还"那套了）
        //   · 等级记在玩家身上，按 death.keepTier 决定留不留
        //   · 电量按 death.keepStored 决定留不留
        //   · 纳米解锁按 death.keepNanoUnlock
        // 2026-11-17 修（纳米开关被短路）：原来外层条件是 (keepTierOnDeath || keepStored)，
        // 它等价于 keepTier。keepTier=false 且 keepStored=false 时整段跳过 → 连
        // death.keepNanoUnlock=true 的纳米解锁也一起丢。现在只看 src 有没有能量数据，
        // 等级 / 电量 / 纳米各自按自己的开关 strip。
        if (src.contains(EnergyStore.ROOT_KEY)) {
            CompoundTag energy = src.getCompound(EnergyStore.ROOT_KEY).copy();
            if (!keepTier) {
                EnergyStore.stripTier(energy);
            } else if (!keepStored) {
                EnergyStore.stripEnergy(energy);
            }
            if (!Config.keepNanoOnDeath) {
                EnergyStore.stripNano(energy);
            }
            if (!energy.isEmpty()) {
                dst.put(EnergyStore.ROOT_KEY, energy);
                TaczAmmoData.LOGGER.info("[挂带] 数据迁移 tier={} 电量={} 等级保留={}",
                        energy.getInt("Tier"), energy.contains(EnergyStore.energyKeyName()), keepTier);
            }
        }
        if (!keepStored) {
            // 存量清零：只丢数量，等级按 death.keepTier 决定
            if (keepTier) {
                int carried = AmmoDataStore.carryTiersOnly(event.getOriginal(), event.getEntity());
                TaczAmmoData.LOGGER.info("[弹药数据] 掉落模式：保留 {} 类弹药袋等级，数量清零", carried);
            }
            return;
        }
        if (src.contains(AmmoDataStore.ROOT_KEY)) {
            // 存量全留（等级自然也在里面）
            dst.put(AmmoDataStore.ROOT_KEY, src.getCompound(AmmoDataStore.ROOT_KEY).copy());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AmmoNetwork.syncAll(player);
        }
    }
}