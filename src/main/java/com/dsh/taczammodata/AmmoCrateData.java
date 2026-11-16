package com.dsh.taczammodata;

import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 生存弹药箱的**数据层**（2026-11-09 凌晨起的头，方块还没套）。
 *
 * ===== 为什么先写这个 =====
 *
 * 主人定的规矩是「台子是变，箱子是搬」：
 *   创造弹药台 → 凭空把表填满
 *   生存弹药箱 → 只能把**箱子里已有的弹药**搬到身上的挂袋里
 *
 * 所以箱子不需要懂玩家、不需要懂枪，它只需要会三件事：
 *   ① 按类别存「点」（跟 AmmoDataStore 一个单位）
 *   ② 存不进去就留在手上（绝不多生成）
 *   ③ 拿得出来，拿多少扣多少
 *
 * 为什么存「点」不存「发」：同一类里 .50AE 是 3 格、9mm 是 1 格，
 * 存发数就没法公平取。存点 = 存「容量」，取的时候再按手里的枪换算成发。
 *
 * ===== 还没做的 =====
 *   - 方块 / BlockEntity / 交互（明天）
 *   - 破坏时内容跟着掉（潜影盒那套 NBT）
 *   - 三档箱子的配方
 */
public final class AmmoCrateData {

    /** 三档箱子：能收哪几类 + 基础总容量（容量读配置） */
    public enum Tier {
        BASIC("初级"),
        INTERMEDIATE("中级"),
        ADVANCED("高级");

        public final String displayName;

        Tier(String displayName) {
            this.displayName = displayName;
        }

        /** 本档基础总容量（点，读配置）；升级倍率另乘 */
        public int totalCapacity() {
            return switch (this) {
                case BASIC -> Config.crateBaseBasic;
                case INTERMEDIATE -> Config.crateBaseIntermediate;
                case ADVANCED -> Config.crateBaseAdvanced;
            };
        }

        /** 这档箱子收不收这一类 */
        public boolean allows(AmmoCategory c) {
            return switch (this) {
                case BASIC -> c == AmmoCategory.PISTOL || c == AmmoCategory.SMG;
                case INTERMEDIATE -> c == AmmoCategory.PISTOL || c == AmmoCategory.SMG
                        || c == AmmoCategory.RIFLE || c == AmmoCategory.SHOTGUN;
                case ADVANCED -> true;   // 全部（能量弹本来就不进表）
            };
        }
    }

    private static final String KEY_POINTS = "Points";
    private static final String KEY_GENERIC = "Generic";
    private static final String KEY_UPGRADE = "Upgrade";

    /** 各类的基础权重，用来把档位总容量按比例分下去（2026-11-14 主人定的 300/600/1000/2000 梯度） */
    private static final Map<AmmoCategory, Integer> BASE_WEIGHT = new EnumMap<>(AmmoCategory.class);

    static {
        BASE_WEIGHT.put(AmmoCategory.PISTOL, 30);
        BASE_WEIGHT.put(AmmoCategory.SMG, 120);
        BASE_WEIGHT.put(AmmoCategory.SHOTGUN, 96);
        BASE_WEIGHT.put(AmmoCategory.RIFLE, 90);
        BASE_WEIGHT.put(AmmoCategory.SNIPER, 80);
        BASE_WEIGHT.put(AmmoCategory.MG, 150);
        BASE_WEIGHT.put(AmmoCategory.RPG, 24);
    }

    /** 老存档的通用弹药点（新逻辑已迁到 pending，留字段只为兼容读写） */
    private int generic = 0;

    /** 各升级线的等级（2026-11-14 升级家族；容量 = CAPACITY） */
    private final Map<CrateUpgradeType, Integer> upgrades = new EnumMap<>(CrateUpgradeType.class);

    private final Tier tier;
    private final Map<AmmoCategory, Integer> points = new EnumMap<>(AmmoCategory.class);

    public AmmoCrateData(Tier tier) {
        this.tier = tier;
    }

    public Tier tier() {
        return tier;
    }

    // ==================== 升级 ====================

    public int getUpgrade(CrateUpgradeType type) {
        return Math.max(0, upgrades.getOrDefault(type, 0));
    }

    public void setUpgrade(CrateUpgradeType type, int level) {
        upgrades.put(type, Math.max(0, Math.min(level, type.maxLevel)));
    }

    /** 兼容旧代码：容量升级等级 */
    public int upgradeLevel() {
        return getUpgrade(CrateUpgradeType.CAPACITY);
    }

    public void setUpgradeLevel(int level) {
        setUpgrade(CrateUpgradeType.CAPACITY, level);
    }

    /** 当前容量倍率（1 / 1.5 / 2 / 4 / 6 / 10） */
    public double upgradeMultiplier() {
        return multiplierOf(upgradeLevel());
    }

    public boolean hasEfficiency() {
        return getUpgrade(CrateUpgradeType.EFFICIENCY) > 0;
    }

    public boolean hasLucky() {
        return getUpgrade(CrateUpgradeType.LUCKY) > 0;
    }

    public boolean hasSpeed() {
        return getUpgrade(CrateUpgradeType.SPEED) > 0;
    }

    /**
     * 等级 → 倍率（2026-11-14，箱子扩容四级，数值读配置）：
     *   0=1 / 1=铁 / 2=金 / 3=钻 / 4=下界
     * ⚠️ 皮革铜是弹药袋（背包）的，不在箱子里。
     */
    public static double multiplierOf(int level) {
        return switch (Math.max(0, Math.min(level, 4))) {
            case 1 -> Config.crateUpgradeIron;
            case 2 -> Config.crateUpgradeGold;
            case 3 -> Config.crateUpgradeDiamond;
            case 4 -> Config.crateUpgradeNetherite;
            default -> 1.0;
        };
    }

    /** 本箱当前各类容量之和（含扩容倍率） */
    public int totalCapacity() {
        long total = Math.round(tier.totalCapacity() * upgradeMultiplier());
        return (int) Math.min(total, 1_000_000_000L);
    }

    /** 这档箱子、这一类的存量上限（点，含扩容） */
    public int capacity(AmmoCategory c) {
        if (!tier.allows(c)) {
            return 0;
        }
        int weightSum = 0;
        for (AmmoCategory x : AmmoCategory.values()) {
            if (tier.allows(x)) {
                weightSum += BASE_WEIGHT.getOrDefault(x, 0);
            }
        }
        if (weightSum <= 0) {
            return 0;
        }
        // 前缀和分配：每类取「累计到我这」的份额差，保证各类相加正好等于总容量，
        // 否则逐类独立取整会让中级只剩 598、高级只剩 996（仿真抓到的）。
        int acc = 0;
        int before = 0;
        int total = totalCapacity();
        for (AmmoCategory x : AmmoCategory.values()) {
            if (!tier.allows(x)) {
                continue;
            }
            acc += BASE_WEIGHT.getOrDefault(x, 0);
            int now = (int) ((long) total * acc / weightSum);
            if (x == c) {
                return Math.max(0, now - before);
            }
            before = now;
        }
        return 0;
    }

    /** 通用池上限 = 这档允许类别容量之和 */
    public int genericCapacity() {
        int sum = 0;
        for (AmmoCategory c : AmmoCategory.values()) {
            if (tier.allows(c)) {
                sum += capacity(c);
            }
        }
        return sum;
    }

    public int generic() {
        return Math.max(0, generic);
    }

    /**
     * 把老存档里的通用池整个取走（迁移到待分配点数用）。
     * 弹药箱 2026-11-14 之后不再有"通用池"这个概念，统一走 pending。
     */
    public int takeGeneric() {
        int taken = Math.max(0, generic);
        generic = 0;
        return taken;
    }

    public int get(AmmoCategory c) {
        return Math.max(0, points.getOrDefault(c, 0));
    }


    /**
     * 取弹药出来。
     *
     * @return 实际取出多少点（不够就全都不取，避免半发）
     */
    public int withdraw(AmmoCategory c, int units) {
        if (units <= 0) {
            return 0;
        }
        int cur = get(c);
        if (cur < units) {
            return 0;
        }
        // 2026-11-17 清理（N10）：走到这里 cur >= units 恒成立，
        // 原来那套「分类不够再从通用池补」的 fromCat/rest 分支永远不可达；
        // 通用池（generic）现在只用于老档迁移（takeGeneric），不再参与扣点。
        points.put(c, cur - units);
        return units;
    }

    /** 箱子里总共多少点（Jade / 提示用） */
    public int totalPoints() {
        int sum = generic;
        for (AmmoCategory c : AmmoCategory.values()) {
            sum += get(c);
        }
        return sum;
    }

    /** 空箱？ */
    public boolean isEmpty() {
        return totalPoints() <= 0;
    }

    // ==================== 存档 ====================

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        CompoundTag p = new CompoundTag();
        for (AmmoCategory c : AmmoCategory.values()) {
            int v = get(c);
            if (v > 0) {
                p.putInt(c.key(), v);
            }
        }
        root.put(KEY_POINTS, p);
        root.putInt(KEY_GENERIC, generic);
        CompoundTag up = new CompoundTag();
        for (CrateUpgradeType t : CrateUpgradeType.values()) {
            int lv = getUpgrade(t);
            if (lv > 0) {
                up.putInt(t.name(), lv);
            }
        }
        root.put("Upgrades", up);
        root.putString("Tier", tier.name());
        return root;
    }

    public void load(CompoundTag root) {
        points.clear();
        generic = Math.max(0, root.getInt(KEY_GENERIC));
        // 新版存 Upgrades 复合；老存档只有单个 Upgrade（容量），迁移一下
        upgrades.clear();
        if (root.contains("Upgrades")) {
            CompoundTag up = root.getCompound("Upgrades");
            for (CrateUpgradeType t : CrateUpgradeType.values()) {
                // ⚠️ 2026-11-16 修（C10 不校验档位）：setUpgrade 内部已经夹到 [0, maxLevel]，
                //   这里以前直接塞原始值，存档被手工改成 99 时界面会显示 Lv.99、
                //   而 multiplierOf() 因为夹到 4 只按 ×20 算 —— 显示和实际对不上。
                setUpgrade(t, up.getInt(t.name()));
            }
        } else {
            setUpgradeLevel(root.getInt(KEY_UPGRADE));
        }
        CompoundTag p = root.getCompound(KEY_POINTS);
        for (AmmoCategory c : AmmoCategory.values()) {
            int v = p.getInt(c.key());
            if (v > 0) {
                points.put(c, Math.min(v, capacity(c)));
            }
        }
    }

    }
