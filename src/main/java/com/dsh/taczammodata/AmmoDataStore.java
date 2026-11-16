package com.dsh.taczammodata;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

import java.util.EnumMap;
import java.util.Map;

/**
 * 弹药容量表存储层。
 *
 * 从原来「按口径存一个数字」改成「**按 7 大类存当前量 + 该类的等级**」：
 *
 *   TaczAmmoData
 *     ├─ Amount: { pistol: 30, rifle: 90, ... }   ← 当前有多少**点**（不是发！）
 *     └─ Tier:   { pistol: 1,  rifle: 0,  ... }   ← 该类的弹药袋等级（0基础~4下界合金）
 *
 * ⚠️ 2026-11-08 起 Amount 的单位是「点」，实际发数 = 点数 ÷ 口径权重。
 *    换算只许走 {@link AmmoWeights#rounds(int, int)} / {@link AmmoWeights#points(int, int)}，
 *    别在别处自己写除法 —— 9mm 是 1 点/发，RPG 火箭是 8 点/发，乱算会差 8 倍。
 *
 * 上限由 {@link Config#capacityFor(AmmoCategory, int)} 决定，所以每类容量不同、
 * 每类可独立升级，正好对应主人要的「手枪 30→铁 60，步枪不一样」。
 */
public final class AmmoDataStore {

    public static final String ROOT_KEY = "TaczAmmoData";
    private static final String AMOUNT_KEY = "Amount";
    private static final String TIER_KEY = "Tier";
    /** 等级索引版本：2 = 已插入铜级（2026-11-14） */
    private static final String TIER_VERSION_KEY = "TierVersion";

    private AmmoDataStore() {
    }

    private static CompoundTag root(Player player) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(ROOT_KEY)) {
            persistent.put(ROOT_KEY, new CompoundTag());
        }
        return persistent.getCompound(ROOT_KEY);
    }

    private static CompoundTag amountMap(Player player, boolean create) {
        CompoundTag root = root(player);
        if (!root.contains(AMOUNT_KEY)) {
            if (!create) {
                return new CompoundTag();
            }
            root.put(AMOUNT_KEY, new CompoundTag());
        }
        return root.getCompound(AMOUNT_KEY);
    }

    private static CompoundTag tierMap(Player player, boolean create) {
        CompoundTag root = root(player);
        if (!root.contains(TIER_KEY)) {
            if (!create) {
                return new CompoundTag();
            }
            root.put(TIER_KEY, new CompoundTag());
        }
        return root.getCompound(TIER_KEY);
    }

    // ==================== 等级 ====================

    /** 该类的弹药袋等级（0=基础 1=铜 2=铁 3=金 4=钻石 5=下界合金） */
    public static int getTier(Player player, AmmoCategory c) {
        return Math.max(0, Math.min(tierMap(player, false).getInt(c.key()), 5));
    }

    public static void setTier(Player player, AmmoCategory c, int tier) {
        tierMap(player, true).putInt(c.key(), Math.max(0, Math.min(tier, 5)));
        // 升级后把当前量夹到新上限（降级时可能会超）
        int max = capacity(player, c);
        if (get(player, c) > max) {
            set(player, c, max);
        }
    }

    /** 该类当前上限（由等级 + 模块加成共同决定） */
    public static int capacity(Player player, AmmoCategory c) {
        return ModuleSystem.effectiveCapacity(player, c);
    }

    /**
     * **不含模块加成**的裸容量（只由等级决定）。
     *
     * 模块系统里要拿它当基准算百分比，所以单独留一个 ——
     * 别在 {@link ModuleSystem} 里再调 {@link #capacity} 否则会无限递归。
     */
    public static int baseCapacityByTier(Player player, AmmoCategory c) {
        return Config.capacityFor(c, getTier(player, c));
    }

    // ==================== 数量 ====================

    public static int get(Player player, AmmoCategory c) {
        return Math.max(0, amountMap(player, false).getInt(c.key()));
    }

    public static void set(Player player, AmmoCategory c, int amount) {
        int max = capacity(player, c);
        amountMap(player, true).putInt(c.key(), Math.max(0, Math.min(amount, max)));
    }

    /** 增加，返回实际增加量 */
    public static int add(Player player, AmmoCategory c, int amount) {
        if (amount <= 0) {
            return 0;
        }
        int before = get(player, c);
        set(player, c, before + amount);
        return get(player, c) - before;
    }

    /** 消耗，不够则一点不扣并返回 false */
    public static boolean consume(Player player, AmmoCategory c, int amount) {
        if (amount <= 0) {
            return false;
        }
        int cur = get(player, c);
        if (cur < amount) {
            return false;
        }
        set(player, c, cur - amount);
        return true;
    }

    public static boolean has(Player player, AmmoCategory c, int amount) {
        return get(player, c) >= amount;
    }

    // ==================== 批量 ====================

    /** 把所有类别的表都填满到各自上限（弹药台用） */
    public static int refillAll(Player player) {
        int n = 0;
        for (AmmoCategory c : AmmoCategory.values()) {
            int before = get(player, c);
            set(player, c, capacity(player, c));
            if (get(player, c) != before) {
                n++;
            }
        }
        return n;
    }

    /**
     * 在预算内把所有类别的表补满，返回**实际消耗的弹药点**（弹药台点数制用）。
     *
     * 预算不够就按类别顺序补，补到哪算哪 —— 台子里点数用完了，玩家就得先塞子弹。
     */
    public static int refillAllWithBudget(Player player, int budget) {
        if (budget <= 0) {
            return 0;
        }
        int spent = 0;
        for (AmmoCategory c : AmmoCategory.values()) {
            int cur = get(player, c);
            int need = capacity(player, c) - cur;
            if (need <= 0) {
                continue;
            }
            int use = Math.min(need, budget - spent);
            if (use <= 0) {
                break;
            }
            set(player, c, cur + use);
            spent += use;
        }
        return spent;
    }

    /**
     * 死亡掉落模式专用：**只把等级搬过去，数量不搬**（2026-11-16，U2 真凶）。
     *
     * 等级是花材料换来的能力，不是可掉落的物品，不该跟 keepInventory 走。
     * 数量（Amount）不搬 → 重生后弹药表从 0 开始，和物品栏逻辑一致。
     *
     * @return 搬过去的类别数
     */
    public static int carryTiersOnly(Player from, Player to) {
        CompoundTag srcRoot = from.getPersistentData().getCompound(ROOT_KEY);
        if (!srcRoot.contains(TIER_KEY)) {
            return 0;
        }
        CompoundTag srcTiers = srcRoot.getCompound(TIER_KEY);
        if (srcTiers.isEmpty()) {
            return 0;
        }
        CompoundTag dstRoot = to.getPersistentData()
                .getCompound(ROOT_KEY);
        if (dstRoot.isEmpty() && !to.getPersistentData().contains(ROOT_KEY)) {
            to.getPersistentData().put(ROOT_KEY, dstRoot);
        }
        dstRoot.put(TIER_KEY, srcTiers.copy());
        if (srcRoot.contains(TIER_VERSION_KEY)) {
            dstRoot.putInt(TIER_VERSION_KEY, srcRoot.getInt(TIER_VERSION_KEY));
        }
        int n = 0;
        for (AmmoCategory c : AmmoCategory.values()) {
            if (srcTiers.getInt(c.key()) > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 老存档等级索引迁移（2026-11-14 插入铜级）：
     * 旧档 1=铁…4=下界 → 新档 2=铁…5=下界；只做一次（TierVersion=2）。
     */
    public static void migrateTierIndexIfNeeded(Player player) {
        CompoundTag r = root(player);
        if (r.getInt(TIER_VERSION_KEY) >= 2) {
            return;
        }
        CompoundTag tiers = tierMap(player, true);
        for (AmmoCategory c : AmmoCategory.values()) {
            int old = tiers.getInt(c.key());
            if (old > 0) {
                tiers.putInt(c.key(), Math.min(old + 1, 5));
            }
        }
        r.putInt(TIER_VERSION_KEY, 2);
    }

    /**
     * 首次进游戏时初始化：给基础容量，等级 0。
     * 只对「还没初始化过」的玩家生效。
     */
    public static void initIfNeeded(Player player) {
        CompoundTag r = root(player);
        // 2026-11-17 修（新玩家永远初始化不了）：
        //   onLoggedIn 先跑 migrateTierIndexIfNeeded()，它会创建一份**空的** Tier 复合标签。
        //   原来这里一看到 Tier 存在就 return —— 等于开了 autoRefillOnSpawn 也永远拿不到基础容量。
        //   现在只认 Amount 判断是否初始化过；Tier 已经存在就不覆盖（避免清掉老档等级），只补 Amount。
        if (r.contains(AMOUNT_KEY)) {
            return;
        }
        boolean hasTier = r.contains(TIER_KEY);
        for (AmmoCategory c : AmmoCategory.values()) {
            if (!hasTier) {
                tierMap(player, true).putInt(c.key(), 0);
            }
            // 用 Config 的 base 容量（不经过 capacity()，因为 tier 刚写）
            amountMap(player, true).putInt(c.key(), Config.baseCapacity(c));
        }
        TaczAmmoData.LOGGER.info("[弹药数据] 初始化玩家 {} 的弹药容量表（基础容量）",
                player.getName().getString());
    }

    /** 快照：全部类别的 当前量/上限/等级 （同步给客户端用） */
    public static Map<AmmoCategory, int[]> snapshot(Player player) {
        Map<AmmoCategory, int[]> out = new EnumMap<>(AmmoCategory.class);
        for (AmmoCategory c : AmmoCategory.values()) {
            out.put(c, new int[]{get(player, c), capacity(player, c), getTier(player, c)});
        }
        return out;
    }

    /** 调试：dump 成一行行文本 */
    public static String dump(Player player) {
        StringBuilder sb = new StringBuilder();
        for (AmmoCategory c : AmmoCategory.values()) {
            sb.append(String.format("%-10s %s/%s  (%s级)%n",
                    c.key(), get(player, c), capacity(player, c), Config.tierName(getTier(player, c))));
        }
        return sb.toString();
    }
}