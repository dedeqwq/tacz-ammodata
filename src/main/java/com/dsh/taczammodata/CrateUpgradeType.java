package com.dsh.taczammodata;

/**
 * 弹药箱的升级类型（2026-11-14 主人要的升级家族）。
 *
 * 一个箱子可以同时装多条升级线，各自独立记等级。
 */
public enum CrateUpgradeType {

    /** 扩容：×2（铁）/ ×4（金）/ ×6（钻）/ ×20（下界），四级不可跳 */
    CAPACITY("扩容", 4, "容量 ×2 / ×4 / ×6 / ×20"),

    /** 高效产出：制作/折算多给 30% 点数 */
    EFFICIENCY("高效产出", 1, "产出 +30%"),

    /** 幸运：制作完成时有概率不消耗弹料 */
    LUCKY("幸运", 1, "25% 概率不消耗弹料"),

    /** 快速制作：制作时间减半 */
    SPEED("快速制作", 1, "制作时间 -50%");

    public final String displayName;
    public final int maxLevel;
    public final String effectText;

    CrateUpgradeType(String displayName, int maxLevel, String effectText) {
        this.displayName = displayName;
        this.maxLevel = maxLevel;
        this.effectText = effectText;
    }
}