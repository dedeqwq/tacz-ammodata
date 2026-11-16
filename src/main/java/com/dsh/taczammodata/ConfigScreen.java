package com.dsh.taczammodata;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 配置界面（Cloth Config 版）。
 *
 * ===== 为什么要单独一个类 =====
 * 这个类**只在检测到 Cloth Config 装了的时候才会被加载**（见 TaczAmmoData 的注册）。
 * 没装 Cloth Config 的玩家，类加载器根本不会碰它 → 不会 NoClassDefFoundError。
 *
 * ===== 配置项分两块 =====
 *  ① 弹药容量表：7 类 × 5 级，共 35 个整数
 *  ② 行为开关：HUD / 应急包 / 日志等
 */
@OnlyIn(Dist.CLIENT)
public final class ConfigScreen {

    private ConfigScreen() {
    }

    /**
     * 创建配置界面。
     *
     * @param parent 从哪个界面进来的（关掉后回那儿）
     */
    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("tacz_ammodata.config.title"))
                .setSavingRunnable(() -> {
                    // 2026-11-09 修：先把界面改的静态值写回 spec，再落盘，最后刷新运行时缓存。
                    // 原来只调 bake()，它从配置文件读旧值，会把界面改的值又覆盖回去 —— 保存不生效。
                    Config.pushToSpec();
                    Config.SPEC.save();
                    Config.bake();
                });

        ConfigEntryBuilder eb = builder.entryBuilder();

        // ==================== 弹药容量表 ====================
        for (AmmoCategory c : AmmoCategory.values()) {
            ConfigCategory cat = builder.getOrCreateCategory(
                    Component.literal(c.displayName + " (" + c.key() + ")"));

            cat.addEntry(eb.startIntField(
                            Component.literal("基础容量"),
                            Config.baseCapacity(c))
                    .setDefaultValue(30)
                    .setMin(1).setMax(1_000_000)
                    .setTooltip(Component.literal("出生自带 / 该等级的上限"))
                    .setSaveConsumer(v -> setCap(c, 0, v))
                    .build());

            cat.addEntry(eb.startIntField(
                            Component.literal("铜（×1.5）"),
                            Config.copperCapacity(c))
                    .setDefaultValue(45)
                    .setMin(1).setMax(1_000_000)
                    .setSaveConsumer(v -> setCap(c, 1, v))
                    .build());

            cat.addEntry(eb.startIntField(
                            Component.literal("铁（×2）"),
                            Config.ironCapacity(c))
                    .setDefaultValue(60)
                    .setMin(1).setMax(1_000_000)
                    .setSaveConsumer(v -> setCap(c, 2, v))
                    .build());

            cat.addEntry(eb.startIntField(
                            Component.literal("金（×4）"),
                            Config.goldCapacity(c))
                    .setDefaultValue(120)
                    .setMin(1).setMax(1_000_000)
                    .setSaveConsumer(v -> setCap(c, 3, v))
                    .build());

            cat.addEntry(eb.startIntField(
                            Component.literal("钻石（×8）"),
                            Config.diamondCapacity(c))
                    .setDefaultValue(240)
                    .setMin(1).setMax(1_000_000)
                    .setSaveConsumer(v -> setCap(c, 4, v))
                    .build());

            cat.addEntry(eb.startIntField(
                            Component.literal("下界合金（×16）"),
                            Config.netheriteCapacity(c))
                    .setDefaultValue(480)
                    .setMin(1).setMax(1_000_000)
                    .setSaveConsumer(v -> setCap(c, 5, v))
                    .build());
        }

        // ==================== 行为开关 ====================
        ConfigCategory misc = builder.getOrCreateCategory(Component.literal("行为"));

        misc.addEntry(eb.startBooleanToggle(Component.literal("打印换弹日志（调试）"), Config.logReload)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Config.logReload = v)
                .build());

        misc.addEntry(eb.startBooleanToggle(Component.literal("换弹时显示剩余弹药"), Config.showReloadMessage)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Config.showReloadMessage = v)
                .build());

        misc.addEntry(eb.startBooleanToggle(Component.literal("首次进服自动补满"), Config.autoRefillOnSpawn)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Config.autoRefillOnSpawn = v)
                .build());

        // ==================== HUD ====================
        ConfigCategory hud = builder.getOrCreateCategory(Component.literal("HUD"));

        hud.addEntry(eb.startBooleanToggle(Component.literal("启用自制 HUD"), Config.hudEnabled)
                .setDefaultValue(false)
                .setTooltip(Component.literal("TaCZ 原生 HUD 已经显示备弹数了，默认关闭避免打架"))
                .setSaveConsumer(v -> Config.hudEnabled = v)
                .build());

        hud.addEntry(eb.startEnumSelector(Component.literal("显示位置"), Config.HudPosition.class,
                        Config.hudPosition)
                .setDefaultValue(Config.HudPosition.BOTTOM_RIGHT)
                .setEnumNameProvider(e -> Component.literal(e.name()))
                .setSaveConsumer(v -> Config.hudPosition = v)
                .build());

        hud.addEntry(eb.startIntField(Component.literal("边缘留白（像素）"), Config.hudPadding)
                .setDefaultValue(8).setMin(0).setMax(200)
                .setSaveConsumer(v -> Config.hudPadding = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(Component.literal("显示全部 7 类"), Config.showAllCategories)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Config.showAllCategories = v)
                .build());

        hud.addEntry(eb.startBooleanToggle(Component.literal("画容量进度条"), Config.showBar)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Config.showBar = v)
                .build());

        // ==================== 应急补给包 ====================
        ConfigCategory emergency = builder.getOrCreateCategory(Component.literal("应急补给包"));

        // 模式选择器已删（2026-11-16）：补给包只有一种行为 —— 扫快捷栏 + 副手，缺多少补多少

        emergency.addEntry(eb.startIntField(Component.literal("每次补多少发"), Config.emergencyFlatAmount)
                .setDefaultValue(30).setMin(1).setMax(100_000)
                .setSaveConsumer(v -> Config.emergencyFlatAmount = v)
                .build());

        emergency.addEntry(eb.startIntField(Component.literal("冷却（tick）"), Config.emergencyCooldownTicks)
                .setDefaultValue(40).setMin(0).setMax(72_000)
                .setSaveConsumer(v -> Config.emergencyCooldownTicks = v)
                .build());

        // ==================== 能量挂带 ====================
        ConfigCategory energy = builder.getOrCreateCategory(Component.literal("能量挂带"));

        String[] tierNames = {"基础", "铁", "金", "钻石", "下界合金"};
        int[] tierDefaults = {1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            energy.addEntry(eb.startIntField(
                            Component.literal(tierNames[i] + "容量（RF）"), Config.energyCapacity[i])
                    .setDefaultValue(tierDefaults[i])
                    .setMin(100).setMax(1_000_000_000)
                    .setSaveConsumer(v -> Config.energyCapacity[idx] = v)
                    .build());
        }

        energy.addEntry(eb.startBooleanToggle(
                        Component.literal("站在充能方块上自动充电"), Config.energyFromBlocks)
                .setDefaultValue(true)
                .setTooltip(Component.literal("Mekanism 充电板 / 龙研电容 / 通量网络都认（IEnergyStorage）"))
                .setSaveConsumer(v -> Config.energyFromBlocks = v)
                .build());

        energy.addEntry(eb.startBooleanToggle(
                        Component.literal("背包里的电池自动充电"), Config.energyFromItems)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Config.energyFromItems = v)
                .build());

        energy.addEntry(eb.startIntField(
                        Component.literal("充电扫描范围（格）"), Config.energyChargeRadius)
                .setDefaultValue(2).setMin(0).setMax(8)
                .setTooltip(Component.literal("找充能方块的水平范围，垂直固定 ±2"))
                .setSaveConsumer(v -> Config.energyChargeRadius = v)
                .build());

        energy.addEntry(eb.startIntField(
                        Component.literal("方块充电速率（RF/t）"), Config.energyBlockPerTick)
                .setDefaultValue(5_000).setMin(1).setMax(1_000_000_000)
                .setSaveConsumer(v -> Config.energyBlockPerTick = v)
                .build());

        energy.addEntry(eb.startIntField(
                        Component.literal("物品充电速率（RF/t）"), Config.energyItemPerTick)
                .setDefaultValue(5_000).setMin(1).setMax(1_000_000_000)
                .setSaveConsumer(v -> Config.energyItemPerTick = v)
                .build());

        energy.addEntry(eb.startBooleanToggle(
                        Component.literal("没电时退回实体弹药"), Config.energyFallbackToAmmo)
                .setDefaultValue(true)
                .setTooltip(Component.literal("true = 没电先吃背包里的电池/纳米弹药；false = 换不了弹"))
                .setSaveConsumer(v -> Config.energyFallbackToAmmo = v)
                .build());

        energy.addEntry(eb.startIntField(
                        Component.literal("未登记能量弹 · 每次换弹消耗（RF）"), Config.energyFuelCostDefault)
                .setDefaultValue(300_000).setMin(0).setMax(10_000_000)
                .setSaveConsumer(v -> Config.energyFuelCostDefault = v)
                .build());

        // ==================== 弹药箱（2026-11-14 全部可配置）====================
        ConfigCategory crate = builder.getOrCreateCategory(Component.literal("弹药箱"));

        crate.addEntry(eb.startIntField(
                        Component.literal("初级基础容量（点）"), Config.crateBaseBasic)
                .setDefaultValue(300).setMin(1).setMax(1_000_000)
                .setSaveConsumer(v -> Config.crateBaseBasic = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("中级基础容量（点）"), Config.crateBaseIntermediate)
                .setDefaultValue(600).setMin(1).setMax(1_000_000)
                .setSaveConsumer(v -> Config.crateBaseIntermediate = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("高级基础容量（点）"), Config.crateBaseAdvanced)
                .setDefaultValue(1000).setMin(1).setMax(1_000_000)
                .setSaveConsumer(v -> Config.crateBaseAdvanced = v)
                .build());

        crate.addEntry(eb.startDoubleField(
                        Component.literal("铁扩容倍率"), Config.crateUpgradeIron)
                .setDefaultValue(2.0).setMin(1.0).setMax(1000.0)
                .setSaveConsumer(v -> Config.crateUpgradeIron = v)
                .build());
        crate.addEntry(eb.startDoubleField(
                        Component.literal("金扩容倍率"), Config.crateUpgradeGold)
                .setDefaultValue(4.0).setMin(1.0).setMax(1000.0)
                .setSaveConsumer(v -> Config.crateUpgradeGold = v)
                .build());
        crate.addEntry(eb.startDoubleField(
                        Component.literal("钻石扩容倍率"), Config.crateUpgradeDiamond)
                .setDefaultValue(6.0).setMin(1.0).setMax(1000.0)
                .setSaveConsumer(v -> Config.crateUpgradeDiamond = v)
                .build());
        crate.addEntry(eb.startDoubleField(
                        Component.literal("下界扩容倍率"), Config.crateUpgradeNetherite)
                .setDefaultValue(20.0).setMin(1.0).setMax(1000.0)
                .setSaveConsumer(v -> Config.crateUpgradeNetherite = v)
                .build());

        crate.addEntry(eb.startIntField(
                        Component.literal("制作时间（tick）"), Config.crateCookTicks)
                .setDefaultValue(40).setMin(1).setMax(20_000)
                .setTooltip(Component.literal("20 tick = 1 秒"))
                .setSaveConsumer(v -> Config.crateCookTicks = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("快速制作时间（tick）"), Config.crateSpeedCookTicks)
                .setDefaultValue(20).setMin(1).setMax(20_000)
                .setSaveConsumer(v -> Config.crateSpeedCookTicks = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("单个弹料产出（点）"), Config.cratePointsPerMaterial)
                .setDefaultValue(200).setMin(1).setMax(1_000_000)
                .setSaveConsumer(v -> Config.cratePointsPerMaterial = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("高效产出加成（%）"), Config.crateEfficiencyBonusPct)
                .setDefaultValue(30).setMin(0).setMax(1000)
                .setSaveConsumer(v -> Config.crateEfficiencyBonusPct = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("幸运概率（%）"), Config.crateLuckyChancePct)
                .setDefaultValue(25).setMin(0).setMax(100)
                .setSaveConsumer(v -> Config.crateLuckyChancePct = v)
                .build());
        crate.addEntry(eb.startIntField(
                        Component.literal("[+N] 按钮步长"), Config.crateButtonStep)
                .setDefaultValue(50).setMin(1).setMax(1_000_000)
                .setSaveConsumer(v -> Config.crateButtonStep = v)
                .build());
        crate.addEntry(eb.startBooleanToggle(
                        Component.literal("死亡时跟随「死亡不掉落」"), Config.crateDropFollowsKeepInventory)
                .setDefaultValue(true)
                .setTooltip(Component.literal("true = 开了死亡不掉落才保留弹药表；false = 永远保留"))
                .setSaveConsumer(v -> Config.crateDropFollowsKeepInventory = v)
                .build());

        // ==================== 死亡规则 ====================
        ConfigCategory death = builder.getOrCreateCategory(Component.literal("死亡规则"));

        death.addEntry(eb.startBooleanToggle(
                        Component.literal("死亡时永久保留等级"), Config.keepTierOnDeath)
                .setDefaultValue(true)
                .setTooltip(Component.literal("弹药袋（铜/铁/金/钻/下界）和挂带的等级，花材料换来的能力，默认死了也不丢"))
                .setSaveConsumer(v -> Config.keepTierOnDeath = v)
                .build());

        death.addEntry(eb.startBooleanToggle(
                        Component.literal("死亡时保留存量"), Config.keepStoredOnDeath)
                .setDefaultValue(true)
                .setTooltip(Component.literal("弹药点数和电量 RF 这类可消耗的东西；true = 跟随 keepInventory 规则"))
                .setSaveConsumer(v -> Config.keepStoredOnDeath = v)
                .build());

        death.addEntry(eb.startBooleanToggle(
                        Component.literal("死亡时保留纳米通道解锁"), Config.keepNanoOnDeath)
                .setDefaultValue(true)
                .setTooltip(Component.literal("纳米通道解锁状态和已收集的样本，默认和等级同待遇"))
                .setSaveConsumer(v -> Config.keepNanoOnDeath = v)
                .build());

        // 「死亡时挂带不掉落」已删（2026-11-16）：挂带现在全程按普通物品处理

        return builder.build();
    }

    /** 改某个类别某一级的容量（写回 Forge 配置） */
    private static void setCap(AmmoCategory c, int tier, int value) {
        // 走 Config 内部已有的 IntValue 映射，保证写回正确的那一项
        Config.setCapacityValue(c, tier, value);
    }
}
