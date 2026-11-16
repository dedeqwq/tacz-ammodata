package com.dsh.taczammodata;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/**
 * 配置。
 *
 * 关键设计（按主人的要求）：
 *  1. **每类弹药有独立的容量**（手枪 30、步枪 90…，各不相同）
 *  2. **升级链**：基础 → 铁 → 金 → 钻石 → 下界合金，每一级各自一个容量值
 *  3. **全部可配置** —— 整合包作者能直接改
 */
public final class Config {

    public enum HudPosition {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    // 应急补给包的工作模式已删除（2026-11-16 主人定）：
    //   原来有 ALL / HELD / DEPLOY 三档，全都删了。
    //   现在只有唯一一种行为：**扫快捷栏 1→9 + 副手，每类各补一次**（原 ALL 的行为）。
    //   删掉的原因：
    //     · HELD 用包时手上必然是包，「只补手持那把枪」永远找不到枪（死锁，修过一次）
    //     · DEPLOY 要先把包放地上、再拿枪右键，操作绕，玩家不会用
    //     · 只剩一种行为，就不会有"改错模式导致补不出去"的坑

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ==================== 每类弹药的容量表 ====================
    // 主人原话：「手枪基础备弹只有 30，升级成铁的会有 60，等所有的那些不同的枪是不一样的」

    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> BASE = new EnumMap<>(AmmoCategory.class);
    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> COPPER = new EnumMap<>(AmmoCategory.class);
    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> IRON = new EnumMap<>(AmmoCategory.class);
    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> GOLD = new EnumMap<>(AmmoCategory.class);
    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> DIAMOND = new EnumMap<>(AmmoCategory.class);
    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> NETHERITE = new EnumMap<>(AmmoCategory.class);

    /** 默认值表：基础 / 铜 / 铁 / 金 / 钻石 / 下界合金 */
    private static final Map<AmmoCategory, int[]> DEFAULTS = new EnumMap<>(AmmoCategory.class);

    static {
        //             基础   铁    金    钻石   下界合金
        //
        // ⚠️ 2026-11-08 起，**这里的单位是「点」，不是「发」**（见 AmmoWeights）：
        //     实际发数 = 点数 ÷ 该口径的权重
        //   权重 1 的口径（9mm / 5.56 / 7.62x39…）发的数量和以前一样；
        //   权重高的口径（12g 霰弹 4、.338 4、40mm 5、RPG 火箭 8）占地方更多。
        //
        //   所以霰弹/狙击/特殊三类的数值按「常用口径的权重」放大了，
        //   目的是让它们在**常用弹药下**还是以前那个发数：
        //     霰弹 ×4（12g = 4 点）、狙击 ×4（.338 = 4 点）、特殊 ×8（RPG 火箭 = 8 点）
        //   其余四类的常用口径权重都是 1，数值不动。
        //
        // 倍率：铜/铁/金/钻石/下界合金 = 基础 × 1.5 / × 2 / × 4 / × 8 / × 16
        DEFAULTS.put(AmmoCategory.PISTOL,  new int[]{30, 45, 60, 120, 240, 480});
        DEFAULTS.put(AmmoCategory.SMG,     new int[]{120, 180, 240, 480, 960, 1920});
        DEFAULTS.put(AmmoCategory.SHOTGUN, new int[]{96, 144, 192, 384, 768, 1536});
        DEFAULTS.put(AmmoCategory.RIFLE,   new int[]{90, 135, 180, 360, 720, 1440});
        DEFAULTS.put(AmmoCategory.SNIPER,  new int[]{80, 120, 160, 320, 640, 1280});
        DEFAULTS.put(AmmoCategory.MG,      new int[]{150, 225, 300, 600, 1200, 2400});
        DEFAULTS.put(AmmoCategory.RPG,     new int[]{24, 36, 48, 96, 192, 384});
    }

    static {
        BUILDER.comment("========== 弹药容量表 ==========",
                        "每类弹药有独立的容量，升级链：基础 -> 铜 -> 铁 -> 金 -> 钻石 -> 下界合金",
                        "整合包作者可以随意改这些数值")
                .push("capacity");

        for (AmmoCategory c : AmmoCategory.values()) {
            int[] d = DEFAULTS.get(c);
            BUILDER.comment("--- " + c.displayName + " (" + c.key() + ") ---").push(c.key());
            BASE.put(c, BUILDER.comment("基础容量（出生自带）").defineInRange("base", d[0], 1, 1_000_000));
            COPPER.put(c, BUILDER.comment("铜弹药袋容量（×1.5）").defineInRange("copper", d[1], 1, 1_000_000));
            IRON.put(c, BUILDER.comment("铁弹药袋容量").defineInRange("iron", d[2], 1, 1_000_000));
            GOLD.put(c, BUILDER.comment("金弹药袋容量").defineInRange("gold", d[3], 1, 1_000_000));
            DIAMOND.put(c, BUILDER.comment("钻石弹药袋容量").defineInRange("diamond", d[4], 1, 1_000_000));
            NETHERITE.put(c, BUILDER.comment("下界合金弹药袋容量").defineInRange("netherite", d[5], 1, 1_000_000));
            BUILDER.pop();
        }
        BUILDER.pop();
    }

    // ==================== 行为开关 ====================

    private static final ForgeConfigSpec.BooleanValue LOG_RELOAD = BUILDER
            .comment("换弹/消耗时是否打印日志（调试用）")
            .define("logReload", true);

    private static final ForgeConfigSpec.BooleanValue SHOW_RELOAD_MESSAGE = BUILDER
            .comment("换弹时是否在物品栏上方显示剩余弹药")
            .define("showReloadMessage", true);

    private static final ForgeConfigSpec.BooleanValue AUTO_REFILL_ON_SPAWN = BUILDER
            .comment("玩家首次进游戏时，是否自动把各弹药表填满到基础容量",
                    "⚠️ 2026-11-09 改成默认 false —— 进服就满配会毁掉生存节奏；",
                    "想要便利就自己开成 true（老存档进服会自动补一次，之后走正常消耗）")
            .define("autoRefillOnSpawn", false);

    // ==================== HUD ====================

    private static final ForgeConfigSpec.BooleanValue HUD_ENABLED = BUILDER
            .comment("========== 自制 HUD（默认关闭） ==========",
                    "TaCZ 原生 HUD 显示的备弹数**就是本模组写进去的值**，功能上不缺什么，",
                    "所以默认不启用自制 HUD，避免两个 HUD 打架。",
                    "想用就改成 true。")
            .define("hud.enabled", false);

    private static final ForgeConfigSpec.EnumValue<HudPosition> HUD_POSITION = BUILDER
            .comment("HUD 显示在屏幕哪个角")
            .defineEnum("hud.position", HudPosition.BOTTOM_RIGHT);

    private static final ForgeConfigSpec.IntValue HUD_PADDING = BUILDER
            .comment("HUD 距屏幕边缘的像素")
            .defineInRange("hud.padding", 8, 0, 200);

    private static final ForgeConfigSpec.BooleanValue HUD_HIDE_WHEN_GUI_OPEN = BUILDER
            .comment("打开任何界面（背包等）时收起 HUD")
            .define("hud.hideWhenGuiOpen", true);

    private static final ForgeConfigSpec.BooleanValue HUD_SHOW_BAR = BUILDER
            .comment("是否在数值下面画一条容量进度条")
            .define("hud.showBar", true);

    private static final ForgeConfigSpec.BooleanValue HUD_SHOW_ALL_CATEGORIES = BUILDER
            .comment("是否显示**全部**弹药表（主人原话：开局就把所有枪的弹药标出来）",
                    "false = 只显示当前手持枪那一类")
            .define("hud.showAllCategories", true);

    private static final ForgeConfigSpec.IntValue HUD_COLOR_TEXT = BUILDER
            .comment("HUD 文字颜色（十进制 RGB）")
            .defineInRange("hud.colorText", 0xFFFFFF, 0, 0xFFFFFF);

    private static final ForgeConfigSpec.IntValue HUD_COLOR_DATA = BUILDER
            .comment("HUD 进度条颜色")
            .defineInRange("hud.colorData", 0xFFAA00, 0, 0xFFFFFF);

    private static final ForgeConfigSpec.IntValue HUD_COLOR_DIM = BUILDER
            .comment("非当前手持类别的暗色（显示全部时用）")
            .defineInRange("hud.colorDim", 0x888888, 0, 0xFFFFFF);

    // ==================== 应急补给包 ====================

    private static final ForgeConfigSpec.IntValue EMERGENCY_FLAT_AMOUNT = BUILDER
            .comment("========== 应急弹药补给包 ==========",
                    "拿包右键 = 扫快捷栏 1→9 + 副手，每类缺多少补多少（唯一行为，没有模式可选）",
                    "这条配置现在只是显示用，实际按「缺多少补多少」走")
            .defineInRange("emergency.flatAmount", 30, 1, 100000);

    private static final ForgeConfigSpec.IntValue EMERGENCY_COOLDOWN = BUILDER
            .comment("使用冷却（tick，20 tick = 1 秒）")
            .defineInRange("emergency.cooldownTicks", 40, 0, 72000);

    // ==================== 弹药价值倍率（补给包扣费）====================
    //
    // 补给包有个「弹药池」。补不同类别，扣池子的量不一样：
    //   手枪/冲锋 1 倍 → 初级包能补 100 发
    //   霰弹      5 倍 → 只能补 20 发（一发霰弹是 8~16 颗弹丸，值这个价）
    //   步枪      2 倍 → 50 发
    //   狙击      8 倍 → 12 发
    //   机枪      3 倍 → 33 发
    //   火箭      20 倍 → 5 发
    //
    // 整合包作者可以按自己的平衡调这些数字。

    private static final Map<AmmoCategory, ForgeConfigSpec.IntValue> AMMO_COST = new EnumMap<>(AmmoCategory.class);

    static {
        BUILDER.comment("========== 弹药价值倍率 ==========",
                        "补给包每补 1 发，扣掉池子里的多少点",
                        "数字越大越贵。手枪便宜、火箭最贵")
                .push("cost");
        for (AmmoCategory c : AmmoCategory.values()) {
            AMMO_COST.put(c, BUILDER
                    .comment(c.displayName + " 每发的池子消耗")
                    .defineInRange(c.key(), defaultCost(c), 1, 100));
        }
        BUILDER.pop();
    }

    /** 默认倍率 */
    private static int defaultCost(AmmoCategory c) {
        return switch (c) {
            case PISTOL, SMG -> 1;
            case RIFLE -> 2;
            case MG -> 3;
            case SHOTGUN -> 5;
            case SNIPER -> 8;
            case RPG -> 20;
        };
    }

    // ==================== 原版子弹装填（已删，别加回来）====================
    //
    // 2026-11-09 主人拍板：**不要「背包子弹自动压进弹药表」**。
    //   定好的链路是：表空了 → 自动吃背包里的实弹 → 背包也空了 → 停在 0（表说了算）。
    //   子弹提前吸进表会把这个手感整个毁掉。
    //   以后子弹要装的地方是**弹药箱**（按权重存点），不是玩家的表。
    // 原来的 ammoLoad.auto / ammoLoad.fromBoxes 配置和 AmmoAutoLoader 已一并移除。

    // ==================== 能量挂带（能量武器）====================
    //
    // 主人 2026-11-08 定：能量枪（换弹类型 fuel）不吃 7 类弹药表，吃独立的能量池。
    // 容量按扩容链翻倍；充能靠 Forge 的 IEnergyStorage（Mekanism / 龙研 / 通量 都能接）。

    private static final ForgeConfigSpec.IntValue ENERGY_CAP_BASE = BUILDER
            .comment("========== 能量挂带 ==========",
                    "能量池上限（RF），默认每级翻倍：1,000,000 → ... → 16,000,000")
            .defineInRange("energy.capacity.base", 1_000_000, 100, 1_000_000_000);
    private static final ForgeConfigSpec.IntValue ENERGY_CAP_IRON = BUILDER
            .defineInRange("energy.capacity.iron", 2_000_000, 100, 1_000_000_000);
    private static final ForgeConfigSpec.IntValue ENERGY_CAP_GOLD = BUILDER
            .defineInRange("energy.capacity.gold", 4_000_000, 100, 1_000_000_000);
    private static final ForgeConfigSpec.IntValue ENERGY_CAP_DIAMOND = BUILDER
            .defineInRange("energy.capacity.diamond", 8_000_000, 100, 1_000_000_000);
    private static final ForgeConfigSpec.IntValue ENERGY_CAP_NETHERITE = BUILDER
            .defineInRange("energy.capacity.netherite", 16_000_000, 100, 1_000_000_000);

    private static final ForgeConfigSpec.BooleanValue ENERGY_FROM_BLOCKS = BUILDER
            .comment("站在带 Forge 能量的方块上时，自动从方块抽电",
                    "（Mekanism 充电板 / 龙研电容 / 通量网络 …… 只要是 IEnergyStorage 就认）")
            .define("energy.charge.fromBlocks", true);
    private static final ForgeConfigSpec.BooleanValue ENERGY_FROM_ITEMS = BUILDER
            .comment("背包里带 Forge 能量的物品（电容、电池）自动给挂带充电")
            .define("energy.charge.fromItems", true);
    private static final ForgeConfigSpec.IntValue ENERGY_CHARGE_RADIUS = BUILDER
            .comment("找充电方块时扫多大范围（水平格数；垂直固定 ±2）",
                    "站在充电台旁边一两格也能充到，就是靠这个")
            .defineInRange("energy.charge.radius", 2, 0, 8);

    private static final ForgeConfigSpec.IntValue ENERGY_PER_TICK_BLOCK = BUILDER
            .comment("每 tick 从方块抽多少 RF")
            .defineInRange("energy.charge.blockPerTick", 5_000, 1, 1_000_000_000);
    private static final ForgeConfigSpec.IntValue ENERGY_PER_TICK_ITEM = BUILDER
            .comment("每 tick 从背包物品抽多少 RF")
            .defineInRange("energy.charge.itemPerTick", 5_000, 1, 1_000_000_000);
    private static final ForgeConfigSpec.BooleanValue ENERGY_FALLBACK = BUILDER
            .comment("挂带没电时，能量枪是否退回 TaCZ 原生（消耗背包里的电池/纳米弹药物品）",
                    "true  = 没电就先吃实体弹药，不至于卡死",
                    "false = 没电就彻底换不了弹，必须去充电")
            .define("energy.fallbackToAmmo", true);

    private static final ForgeConfigSpec.IntValue ENERGY_FUEL_DEFAULT = BUILDER
            .comment("没登记过的能量弹药，换一次弹默认扣多少 RF")
            .defineInRange("energy.reloadCost.default", 300_000, 0, 10_000_000);

    private static final ForgeConfigSpec.BooleanValue ENERGY_TACZ_HUD = BUILDER
            .comment("能量枪是否用 **TaCZ 原生 HUD** 显示剩余弹药：",
                    "true  = 把「当前电量还能打多少发」写进 DummyAmmo，TaCZ 右上角直接显示真实数字（推荐）",
                    "false = 保持旧行为，DummyAmmo 只写 0/1，HUD 会一直显示 1")
            .define("energy.taczHud", true);

    private static final ForgeConfigSpec.BooleanValue ENERGY_CHAT_STATUS = BUILDER
            .comment("手持能量枪时是否在动作栏刷电量百分比：",
                    "false = 不刷（推荐，电量看 TaCZ HUD 就够了）",
                    "true  = 每 10 秒且百分比变化时才发一条")
            .define("energy.chatStatus", false);

    // ==================== 微调（给整合包作者 / 创作向） ====================
    //
    // 这些以前都写死在代码里，2026-11-09 提上来：
    // 想做「硬核生存」或「爽包」的人，不用改代码，改这里就行。

    private static final ForgeConfigSpec.IntValue TUNE_REFILL_THRESHOLD = BUILDER
            .comment("========== 微调（整合包作者用） ==========",
                    "影子补弹阈值：枪上显示的备弹比账本少多少『发』才去补一次",
                    "调小 = 数字更跟手，但 NBT 写得勤（PvP 服务器建议 ≥ 8）；调大 = 更省性能")
            .defineInRange("tuning.dummyRefillThreshold", 8, 0, 1000);

    private static final ForgeConfigSpec.IntValue TUNE_WEIGHTS_FALLBACK = BUILDER
            .comment("口径权重表里没登记的弹，一发按多少『点』算",
                    "新枪包加了口径又懒得填表时，靠它兜底")
            .defineInRange("tuning.weightsFallback", 1, 1, 2000);

    private static final ForgeConfigSpec.IntValue TUNE_NANO_UNLOCK = BUILDER
            .comment("纳米通道解锁需要多少个纳米弹药（潜行右键）")
            .defineInRange("tuning.nanoUnlockCount", 10, 0, 1000);

    private static final ForgeConfigSpec.DoubleValue TUNE_ARMOR_BONUS = BUILDER
            .comment("任意一类弹药袋升到下界合金时的护甲加成（不叠加）；0 = 关掉")
            .defineInRange("tuning.netheriteArmorBonus", 8.0, 0.0, 30.0);

    private static final ForgeConfigSpec.IntValue TUNE_STATION_COOLDOWN = BUILDER
            .comment("弹药台 / 弹药台物品右键的冷却（tick，20 = 1 秒）")
            .defineInRange("tuning.stationCooldownTicks", 10, 0, 200);

    private static final ForgeConfigSpec.IntValue TUNE_POOL_BASIC = BUILDER
            .comment("初级补给包的弹药池容量（发）")
            .defineInRange("tuning.emergencyPool.basic", 200, 1, 1_000_000);
    private static final ForgeConfigSpec.IntValue TUNE_POOL_ADVANCED = BUILDER
            .comment("中级补给包的弹药池容量（发）")
            .defineInRange("tuning.emergencyPool.advanced", 400, 1, 1_000_000);
    private static final ForgeConfigSpec.IntValue TUNE_POOL_ELITE = BUILDER
            .comment("高级补给包的弹药池容量（发）")
            .defineInRange("tuning.emergencyPool.elite", 800, 1, 1_000_000);

    private static final ForgeConfigSpec.IntValue TUNE_STATION_SMALL = BUILDER
            .comment("弹药台（1 格）的弹药点容量。默认 5000：够补满下界合金全部 7 类一次多")
            .defineInRange("tuning.stationPoints.small", 5_000, 0, 100_000_000);

    private static final ForgeConfigSpec.IntValue TUNE_EXPLOSION_CAP = BUILDER
            .comment("普通弹药台的爆炸威力上限（默认 64，原版 TNT 才 4）",
                    "⚠️ MC 的爆炸半径太大会直接卡死甚至崩游戏；想填 21 亿请自己负责")
            .defineInRange("tuning.explosionPowerCap", 64, 4, Integer.MAX_VALUE);
    private static final ForgeConfigSpec.IntValue TUNE_EXPLOSION_CREATIVE = BUILDER
            .comment("创造弹药台的爆炸威力（默认 500 = 主人的整活核弹）",
                    "⚠️ 同上，超过几百基本就是自杀")
            .defineInRange("tuning.creativeExplosionPower", 500, 4, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.IntValue TUNE_CHARGE_INTERVAL = BUILDER
            .comment("挂带自动充电的扫描间隔（tick）。调大省性能，调小充得快")
            .defineInRange("tuning.chargeScanIntervalTicks", 20, 1, 200);

    // ===== 生存弹药箱（2026-11-14 全部可配置）=====
    private static final ForgeConfigSpec.IntValue CRATE_BASE_BASIC = BUILDER
            .comment("初级弹药箱基础总容量（点）")
            .defineInRange("crate.baseCapacity.basic", 300, 1, 1_000_000);
    private static final ForgeConfigSpec.IntValue CRATE_BASE_INTERMEDIATE = BUILDER
            .comment("中级弹药箱基础总容量（点）")
            .defineInRange("crate.baseCapacity.intermediate", 600, 1, 1_000_000);
    private static final ForgeConfigSpec.IntValue CRATE_BASE_ADVANCED = BUILDER
            .comment("高级弹药箱基础总容量（点）")
            .defineInRange("crate.baseCapacity.advanced", 1000, 1, 1_000_000);

    private static final ForgeConfigSpec.DoubleValue CRATE_UP_IRON = BUILDER
            .comment("铁扩容倍率")
            .defineInRange("crate.upgrade.iron", 2.0, 1.0, 1000.0);
    private static final ForgeConfigSpec.DoubleValue CRATE_UP_GOLD = BUILDER
            .comment("金扩容倍率")
            .defineInRange("crate.upgrade.gold", 4.0, 1.0, 1000.0);
    private static final ForgeConfigSpec.DoubleValue CRATE_UP_DIAMOND = BUILDER
            .comment("钻石扩容倍率")
            .defineInRange("crate.upgrade.diamond", 6.0, 1.0, 1000.0);
    private static final ForgeConfigSpec.DoubleValue CRATE_UP_NETHERITE = BUILDER
            .comment("下界扩容倍率（下界之星太贵，默认直接 ×20）")
            .defineInRange("crate.upgrade.netherite", 20.0, 1.0, 1000.0);

    private static final ForgeConfigSpec.IntValue CRATE_COOK_TICKS = BUILDER
            .comment("一个复合弹料的制作时间（tick，20=1 秒）")
            .defineInRange("crate.cookTicks", 40, 1, 20_000);
    private static final ForgeConfigSpec.IntValue CRATE_SPEED_COOK_TICKS = BUILDER
            .comment("装了快速制作后的制作时间（tick）")
            .defineInRange("crate.speedCookTicks", 20, 1, 20_000);
    private static final ForgeConfigSpec.IntValue CRATE_POINTS_PER_MATERIAL = BUILDER
            .comment("一个复合弹料产出多少弹药点")
            .defineInRange("crate.pointsPerMaterial", 200, 1, 1_000_000);
    private static final ForgeConfigSpec.IntValue CRATE_EFFICIENCY_PCT = BUILDER
            .comment("高效产出加成百分比（默认 +30）")
            .defineInRange("crate.efficiencyBonusPct", 30, 0, 1000);
    private static final ForgeConfigSpec.IntValue CRATE_LUCKY_PCT = BUILDER
            .comment("幸运升级：制作完成不消耗弹料的概率（百分比）")
            .defineInRange("crate.luckyChancePct", 25, 0, 100);
    private static final ForgeConfigSpec.IntValue CRATE_BUTTON_STEP = BUILDER
            .comment("界面 [+N] 按钮一次分配多少点")
            .defineInRange("crate.buttonStep", 50, 1, 1_000_000);
    private static final ForgeConfigSpec.BooleanValue CRATE_DROP_FOLLOWS_KEEP = BUILDER
            .comment("死亡时弹药要不要保留（只管弹药点，不管物品）：",
                    "true = 跟随「死亡不掉落」规则：开了就保留弹药表，没开就清零",
                    "false = 无论开不开都保留弹药表")
            .define("crate.dropOnDeathFollowsKeepInventory", true);

    // ===== 死亡规则：等级 vs 存量（2026-11-16 主人定，全部可配）=====
    //
    // 等级（弹药袋铜/铁/金/钻/下界、挂带基础/铁/金/钻/下界、纳米解锁）
    //   是花材料换来的**能力**，不是可掉落的物品 → 默认永久保留，跟 keepInventory 无关。
    // 存量（弹药点数、挂带电量的 RF）
    //   是可消耗的东西 → 默认跟随「死亡不掉落」规则，掉了就清零。

    private static final ForgeConfigSpec.BooleanValue KEEP_TIER_ON_DEATH = BUILDER
            .comment("死亡时是否永久保留弹药袋 / 挂带的**等级**（跟死亡不掉落规则无关）：",
                    "true  = 等级永远不丢，死了也还是铜/铁/金/钻/下界（默认）",
                    "false = 等级跟存量一起按下面的 keepStoredOnDeath 规则处理")
            .define("death.keepTier", true);

    private static final ForgeConfigSpec.BooleanValue KEEP_STORED_ON_DEATH = BUILDER
            .comment("死亡时是否保留**存量**（弹药点数 / 挂带电量 RF）：",
                    "true  = 跟随「死亡不掉落」规则：开了 keepInventory 才保留，没开就清零（默认）",
                    "false = 无论开不开都清零",
                    "如果想「无论怎样都保留存量」，把 crate.dropOnDeathFollowsKeepInventory 设成 false")
            .define("death.keepStored", true);

    private static final ForgeConfigSpec.BooleanValue KEEP_NANO_ON_DEATH = BUILDER
            .comment("死亡时是否保留纳米通道解锁状态 / 已收集样本：",
                    "true  = 永久保留（默认，和等级同待遇）",
                    "false = 跟随 death.keepTier 一起按死亡规则处理")
            .define("death.keepNanoUnlock", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    // ==================== 运行时缓存 ====================

    private static final Map<AmmoCategory, int[]> CAP = new EnumMap<>(AmmoCategory.class);
    /** 运行时缓存：每类弹药的价值倍率 */
    private static final Map<AmmoCategory, Integer> COST = new EnumMap<>(AmmoCategory.class);

    public static boolean logReload = true;
    public static boolean showReloadMessage = true;
    public static boolean autoRefillOnSpawn = false;

    public static boolean hudEnabled = true;
    public static HudPosition hudPosition = HudPosition.BOTTOM_RIGHT;
    public static int hudPadding = 8;
    public static boolean hideWhenGuiOpen = true;
    public static boolean showBar = true;
    public static boolean showAllCategories = true;
    public static int colorText = 0xFFFFFF;
    public static int colorData = 0xFFAA00;
    public static int colorDim = 0x888888;

    // 应急补给包
    // 应急补给包（模式体系已删，见上方注释）
    public static int emergencyFlatAmount = 30;
    public static int emergencyCooldownTicks = 40;

    // 能量挂带
    public static int[] energyCapacity = {1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000};
    public static boolean energyFromBlocks = true;
    public static boolean energyFromItems = true;
    public static int energyBlockPerTick = 5_000;
    public static int energyChargeRadius = 2;
    public static int energyItemPerTick = 5_000;
    public static int energyFuelCostDefault = 300_000;
    public static boolean energyFallbackToAmmo = true;
    /** 能量枪是否用 TaCZ 原生 HUD 显示真实剩余弹药 */
    public static boolean energyTaczHud = true;
    /** 手持能量枪时是否刷电量百分比到动作栏 */
    public static boolean energyChatStatus = false;

    // 微调
    public static int refillThreshold = 8;
    public static int weightsFallback = 1;
    public static int nanoUnlockCount = 10;
    public static double netheriteArmorBonus = 8.0;
    public static int stationCooldownTicks = 10;
    public static int poolBasic = 200;
    public static int poolAdvanced = 400;
    public static int poolElite = 800;
    /** 弹药台的弹药点容量 */
    public static int stationPointsSmall = 5_000;
    /** 爆炸威力上限（可随便填，后果自负） */
    public static int explosionPowerCap = 64;
    public static int creativeExplosionPower = 500;
    public static int chargeScanInterval = 20;

    // ===== 生存弹药箱运行时值（2026-11-14 全部可配置）=====
    public static int crateBaseBasic = 300;
    public static int crateBaseIntermediate = 600;
    public static int crateBaseAdvanced = 1000;
    public static double crateUpgradeIron = 2.0;
    public static double crateUpgradeGold = 4.0;
    public static double crateUpgradeDiamond = 6.0;
    public static double crateUpgradeNetherite = 20.0;
    public static int crateCookTicks = 40;
    public static int crateSpeedCookTicks = 20;
    public static int cratePointsPerMaterial = 200;
    public static int crateEfficiencyBonusPct = 30;
    public static int crateLuckyChancePct = 25;
    public static int crateButtonStep = 50;
    public static boolean crateDropFollowsKeepInventory = true;
    /** 死亡时永久保留等级（弹药袋 / 挂带），跟 keepInventory 无关 */
    public static boolean keepTierOnDeath = true;
    /** 死亡时保留存量（弹药点数 / 电量），true = 跟随 keepInventory */
    public static boolean keepStoredOnDeath = true;
    /** 死亡时永久保留纳米解锁状态 */
    public static boolean keepNanoOnDeath = true;

    private Config() {
    }

    /** 某级能量挂带的容量（RF） */
    public static int energyCapacity(int tier) {
        int[] a = energyCapacity;
        int t = Math.max(0, Math.min(tier, a.length - 1));
        return a[t];
    }

    // ==================== 容量查询 ====================

    public static int baseCapacity(AmmoCategory c) {
        return cap(c, 0);
    }

    public static int copperCapacity(AmmoCategory c) {
        return cap(c, 1);
    }

    public static int ironCapacity(AmmoCategory c) {
        return cap(c, 2);
    }

    public static int goldCapacity(AmmoCategory c) {
        return cap(c, 3);
    }

    public static int diamondCapacity(AmmoCategory c) {
        return cap(c, 4);
    }

    public static int netheriteCapacity(AmmoCategory c) {
        return cap(c, 5);
    }

    /**
     * 按等级取容量。
     *
     * @param tier 0=基础 1=铜 2=铁 3=金 4=钻石 5=下界合金
     */
    public static int capacityFor(AmmoCategory c, int tier) {
        return cap(c, Math.max(0, Math.min(tier, 5)));
    }

    private static int cap(AmmoCategory c, int tier) {
        int[] arr = CAP.get(c);
        if (arr == null) {
            int[] d = DEFAULTS.get(c);
            return d[tier];
        }
        return arr[tier];
    }

    /** 某类弹药每补 1 发要扣多少池子 */
    public static int ammoCost(AmmoCategory c) {
        return COST.getOrDefault(c, 1);
    }

    /**
     * 写回某类某一级的容量 —— **给配置界面用的**。
     *
     * 两件事一起做：
     *  ① 改 Forge 的 IntValue（这样保存时会写进 toml）
     *  ② 同步改运行时缓存 CAP（这样当场就生效，不用等重载）
     */
    public static void setCapacityValue(AmmoCategory c, int tier, int value) {
        ForgeConfigSpec.IntValue target = switch (tier) {
            case 1 -> COPPER.get(c);
            case 2 -> IRON.get(c);
            case 3 -> GOLD.get(c);
            case 4 -> DIAMOND.get(c);
            case 5 -> NETHERITE.get(c);
            default -> BASE.get(c);
        };
        if (target == null) {
            return;
        }
        target.set(Math.max(1, Math.min(value, 1_000_000)));

        int[] arr = CAP.get(c);
        if (arr != null) {
            arr[Math.max(0, Math.min(tier, 5))] = value;
        }
    }

    /**
     * 把当前静态字段的值写回 Forge 配置。
     *
     * 2026-11-09 修：Cloth 配置界面里除了容量，其它开关改完点保存本来不生效 ——
     * 因为保存只调了 {@link #bake()}，而它从配置文件读旧值，把界面改的静态值又覆盖回去。
     * 现在界面保存时先调用这里把静态值写进 spec，再由界面调 {@code SPEC.save()} 落盘。
     */
    public static void pushToSpec() {
        LOG_RELOAD.set(logReload);
        SHOW_RELOAD_MESSAGE.set(showReloadMessage);
        AUTO_REFILL_ON_SPAWN.set(autoRefillOnSpawn);

        HUD_ENABLED.set(hudEnabled);
        HUD_POSITION.set(hudPosition);
        HUD_PADDING.set(hudPadding);
        HUD_HIDE_WHEN_GUI_OPEN.set(hideWhenGuiOpen);
        HUD_SHOW_BAR.set(showBar);
        HUD_SHOW_ALL_CATEGORIES.set(showAllCategories);
        HUD_COLOR_TEXT.set(colorText);
        HUD_COLOR_DATA.set(colorData);
        HUD_COLOR_DIM.set(colorDim);

        EMERGENCY_FLAT_AMOUNT.set(emergencyFlatAmount);
        EMERGENCY_COOLDOWN.set(emergencyCooldownTicks);

        ENERGY_CAP_BASE.set(energyCapacity[0]);
        ENERGY_CAP_IRON.set(energyCapacity[1]);
        ENERGY_CAP_GOLD.set(energyCapacity[2]);
        ENERGY_CAP_DIAMOND.set(energyCapacity[3]);
        ENERGY_CAP_NETHERITE.set(energyCapacity[4]);
        ENERGY_FROM_BLOCKS.set(energyFromBlocks);
        ENERGY_FROM_ITEMS.set(energyFromItems);
        ENERGY_CHARGE_RADIUS.set(energyChargeRadius);
        ENERGY_PER_TICK_BLOCK.set(energyBlockPerTick);
        ENERGY_PER_TICK_ITEM.set(energyItemPerTick);
        ENERGY_FALLBACK.set(energyFallbackToAmmo);
        ENERGY_FUEL_DEFAULT.set(energyFuelCostDefault);
        ENERGY_TACZ_HUD.set(energyTaczHud);
        ENERGY_CHAT_STATUS.set(energyChatStatus);

        TUNE_REFILL_THRESHOLD.set(refillThreshold);
        TUNE_WEIGHTS_FALLBACK.set(weightsFallback);
        TUNE_NANO_UNLOCK.set(nanoUnlockCount);
        TUNE_ARMOR_BONUS.set(netheriteArmorBonus);
        TUNE_STATION_COOLDOWN.set(stationCooldownTicks);
        TUNE_POOL_BASIC.set(poolBasic);
        TUNE_POOL_ADVANCED.set(poolAdvanced);
        TUNE_POOL_ELITE.set(poolElite);
        TUNE_STATION_SMALL.set(stationPointsSmall);
        TUNE_EXPLOSION_CAP.set(explosionPowerCap);
        TUNE_EXPLOSION_CREATIVE.set(creativeExplosionPower);
        TUNE_CHARGE_INTERVAL.set(chargeScanInterval);
        CRATE_BASE_BASIC.set(crateBaseBasic);
        CRATE_BASE_INTERMEDIATE.set(crateBaseIntermediate);
        CRATE_BASE_ADVANCED.set(crateBaseAdvanced);
        CRATE_UP_IRON.set(crateUpgradeIron);
        CRATE_UP_GOLD.set(crateUpgradeGold);
        CRATE_UP_DIAMOND.set(crateUpgradeDiamond);
        CRATE_UP_NETHERITE.set(crateUpgradeNetherite);
        CRATE_COOK_TICKS.set(crateCookTicks);
        CRATE_SPEED_COOK_TICKS.set(crateSpeedCookTicks);
        CRATE_POINTS_PER_MATERIAL.set(cratePointsPerMaterial);
        CRATE_EFFICIENCY_PCT.set(crateEfficiencyBonusPct);
        CRATE_LUCKY_PCT.set(crateLuckyChancePct);
        CRATE_BUTTON_STEP.set(crateButtonStep);
        CRATE_DROP_FOLLOWS_KEEP.set(crateDropFollowsKeepInventory);
        KEEP_TIER_ON_DEATH.set(keepTierOnDeath);
        KEEP_STORED_ON_DEATH.set(keepStoredOnDeath);
        KEEP_NANO_ON_DEATH.set(keepNanoOnDeath);
    }

    /** 等级显示名 */
    public static String tierName(int tier) {
        return switch (tier) {
            case 1 -> "铜";
            case 2 -> "铁";
            case 3 -> "金";
            case 4 -> "钻石";
            case 5 -> "下界合金";
            default -> "基础";
        };
    }

    // ==================== 加载 ====================

    public static void bake() {
        try {
            // 配置变了容量表也跟着变，模块系统的容量缓存必须整体作废
            ModuleSystem.clearCapacityCache();
            for (AmmoCategory c : AmmoCategory.values()) {
                CAP.put(c, new int[]{
                        BASE.get(c).get(),
                        COPPER.get(c).get(),
                        IRON.get(c).get(),
                        GOLD.get(c).get(),
                        DIAMOND.get(c).get(),
                        NETHERITE.get(c).get()
                });
            }
            logReload = LOG_RELOAD.get();
            showReloadMessage = SHOW_RELOAD_MESSAGE.get();
            autoRefillOnSpawn = AUTO_REFILL_ON_SPAWN.get();

            hudEnabled = HUD_ENABLED.get();
            hudPosition = HUD_POSITION.get();
            hudPadding = HUD_PADDING.get();
            hideWhenGuiOpen = HUD_HIDE_WHEN_GUI_OPEN.get();
            showBar = HUD_SHOW_BAR.get();
            showAllCategories = HUD_SHOW_ALL_CATEGORIES.get();
            colorText = HUD_COLOR_TEXT.get();
            colorData = HUD_COLOR_DATA.get();
            colorDim = HUD_COLOR_DIM.get();
            emergencyFlatAmount = EMERGENCY_FLAT_AMOUNT.get();
            emergencyCooldownTicks = EMERGENCY_COOLDOWN.get();
            energyCapacity = new int[]{
                    ENERGY_CAP_BASE.get(), ENERGY_CAP_IRON.get(), ENERGY_CAP_GOLD.get(),
                    ENERGY_CAP_DIAMOND.get(), ENERGY_CAP_NETHERITE.get()
            };
            energyFromBlocks = ENERGY_FROM_BLOCKS.get();
            energyFromItems = ENERGY_FROM_ITEMS.get();
            energyBlockPerTick = ENERGY_PER_TICK_BLOCK.get();
            energyChargeRadius = ENERGY_CHARGE_RADIUS.get();
            energyItemPerTick = ENERGY_PER_TICK_ITEM.get();
            energyFuelCostDefault = ENERGY_FUEL_DEFAULT.get();
            energyFallbackToAmmo = ENERGY_FALLBACK.get();
            energyTaczHud = ENERGY_TACZ_HUD.get();
            energyChatStatus = ENERGY_CHAT_STATUS.get();
            refillThreshold = TUNE_REFILL_THRESHOLD.get();
            weightsFallback = TUNE_WEIGHTS_FALLBACK.get();
            nanoUnlockCount = TUNE_NANO_UNLOCK.get();
            netheriteArmorBonus = TUNE_ARMOR_BONUS.get();
            stationCooldownTicks = TUNE_STATION_COOLDOWN.get();
            poolBasic = TUNE_POOL_BASIC.get();
            poolAdvanced = TUNE_POOL_ADVANCED.get();
            poolElite = TUNE_POOL_ELITE.get();
            stationPointsSmall = TUNE_STATION_SMALL.get();
            explosionPowerCap = TUNE_EXPLOSION_CAP.get();
            creativeExplosionPower = TUNE_EXPLOSION_CREATIVE.get();
            chargeScanInterval = TUNE_CHARGE_INTERVAL.get();
            crateBaseBasic = CRATE_BASE_BASIC.get();
            crateBaseIntermediate = CRATE_BASE_INTERMEDIATE.get();
            crateBaseAdvanced = CRATE_BASE_ADVANCED.get();
            crateUpgradeIron = CRATE_UP_IRON.get();
            crateUpgradeGold = CRATE_UP_GOLD.get();
            crateUpgradeDiamond = CRATE_UP_DIAMOND.get();
            crateUpgradeNetherite = CRATE_UP_NETHERITE.get();
            crateCookTicks = CRATE_COOK_TICKS.get();
            crateSpeedCookTicks = CRATE_SPEED_COOK_TICKS.get();
            cratePointsPerMaterial = CRATE_POINTS_PER_MATERIAL.get();
            crateEfficiencyBonusPct = CRATE_EFFICIENCY_PCT.get();
            crateLuckyChancePct = CRATE_LUCKY_PCT.get();
            crateButtonStep = CRATE_BUTTON_STEP.get();
            crateDropFollowsKeepInventory = CRATE_DROP_FOLLOWS_KEEP.get();
            keepTierOnDeath = KEEP_TIER_ON_DEATH.get();
            keepStoredOnDeath = KEEP_STORED_ON_DEATH.get();
            keepNanoOnDeath = KEEP_NANO_ON_DEATH.get();
            for (AmmoCategory c : AmmoCategory.values()) {
                ForgeConfigSpec.IntValue v = AMMO_COST.get(c);
                COST.put(c, v == null ? 1 : v.get());
            }
        } catch (IllegalStateException ignored) {
            // 配置尚未加载完，先用默认值顶着
        }
    }
}