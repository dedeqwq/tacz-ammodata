package com.dsh.taczammodata;

/**
 * 应急补给包的规格等级。
 *
 * 一个补给包 = 一个「弹药池」，用的时候缺多少补多少，从池子里扣。
 *
 * 主人 2026-11-07 定：初级/中级/高级，先做这三档；
 * 后面可能还要加「特级」「终极」，所以等级用枚举，**加档位不用改逻辑**。
 *
 * 2026-11-08 主人补了风味文案（flavor），显示在实用说明下面。
 *
 * ===== 三档的使用规则（2026-11-09 主人定稿）=====
 *
 *   初级·纸包：一次性。合出来是空的，能灌满一次；用光**直接消失**。
 *   中级·铁罐：合出来是空的，能灌满一次；用光留空壳，
 *               **必须把里面的弹药彻底用空**才能重新灌满（半满时灌不进去）。
 *   高级·铁箱：随时能灌，用空留空箱，是主力装备。
 *
 * 平衡性（主人原话「这样感觉能更好一点，平衡性也有」）：
 *   纸包最便宜、最容易带、但用完就没了；
 *   铁罐容量大，但得打空才能复装，逼你清仓；
 *   铁箱最贵，随时可灌，是「主力装备」。
 */
public enum PackTier {
    BASIC("初级", 100, 16, 0xFFCC66, true, false, new String[]{
            "对于大部分应急情况下，具备应急补给能力，",
            "适配大部分枪械子弹的应急补给包",
    }),
    ADVANCED("中级", 200, 12, 0x66CCFF, false, false, new String[]{
            "利用精致的盒子，严丝合缝的包装，每一发弹药，",
            "且做好了非常完美的分类，",
            "在任何应急情况下，也能快速补给自己弹药",
    }),
    ELITE("高级", 400, 10, 0xCC66FF, false, true, new String[]{
            // 2026-11-16：角色配色 —— lin 粉 §d（原计划纯黑 §0，深色背景看不见），dede 白 §f
            "§fdede：等等我，等等我",
            "§dlin：你这家伙带这么一大包，什么玩意儿啊？",
            "§fdede：子弹啊",
            "§dlin：你带多少子弹呢？",
            "§fdede：300 发！",
            "§dlin：咋的，你去参加战役呀？打生化呢",
            "§fdede：你不懂，我有火力不足恐惧症",
    });

    /** 显示名 */
    public final String displayName;
    /** 耐久条颜色（RGB） */
    public final int barColor;
    /** 风味文案（一行一条） */
    public final String[] flavor;
    /**
     * 最大堆叠数（2026-11-09 主人定：初级 16 / 中级 12 / 高级 10）。
     *
     * ⚠️ **余量不同的包不会自动合并**——MC 按 NBT 判同，Remaining 不同就叠不到一起。
     * 所以真正能叠的只有「都是空包」和「都是满包」，半满包永远独占一格。
     */
    public final int stackLimit;

    /**
     * 用空后**物品是否消失**（2026-11-09 主人定）。
     *
     * true  = 纸包：用光直接没了，不占格子
     * false = 铁罐 / 铁箱：留空壳
     */
    public final boolean vanishesWhenEmpty;

    /**
     * 空壳能不能**重新灌满**（2026-11-09 主人定）。
     *
     * true  = 铁箱：右键弹药台随时复装（唯一可复装档）
     * false = 纸包（本来就消失）/ 铁罐（留壳但废了）
     */
    public final boolean refillable;

    PackTier(String displayName, int capacity, int stackLimit, int barColor,
             boolean vanishesWhenEmpty, boolean refillable, String[] flavor) {
        this.displayName = displayName;
        this.barColor = barColor;
        this.flavor = flavor;
        this.stackLimit = stackLimit;
        this.vanishesWhenEmpty = vanishesWhenEmpty;
        this.refillable = refillable;
    }

    /**
     * 弹药池容量（弹药点）—— **2026-11-09 起读配置**，见 `tuning.emergencyPool.*`。
     * 构造参数里的 capacity 只作为「配置还没加载」时的兜底。
     */
    public int capacity() {
        return switch (this) {
            case BASIC -> Config.poolBasic;
            case ADVANCED -> Config.poolAdvanced;
            case ELITE -> Config.poolElite;
        };
    }

    /** 按序号取档（0=初级 1=中级 2=高级），越界夹紧 */
    public static PackTier byIndex(int i) {
        PackTier[] all = values();
        return all[Math.max(0, Math.min(i, all.length - 1))];
    }
}
