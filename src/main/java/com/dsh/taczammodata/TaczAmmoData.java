package com.dsh.taczammodata;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

/**
 * TaCZ 弹药数据系统（附属模组）。
 *
 * 第一阶段最小实现：
 *  1. 弹药数据独立存储（不占背包格）—— {@link AmmoDataStore}
 *  2. 换弹扣数据、不掏背包子弹 —— {@link ReloadHandler}
 *  3. HUD 显示 —— {@link AmmoHudOverlay}
 *  4. 弹药台一键补满 —— {@link AmmoStationBlock}
 */
@Mod(TaczAmmoData.MODID)
public class TaczAmmoData {

    public static final String MODID = "tacz_ammodata";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    /** 容器菜单（弹药箱 GUI） */
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MODID);

    public static final RegistryObject<MenuType<AmmoCrateMenu>> AMMO_CRATE_MENU =
            MENUS.register("ammo_crate", () -> IForgeMenuType.create(
                    (int id, net.minecraft.world.entity.player.Inventory inv,
                     net.minecraft.network.FriendlyByteBuf buf) -> new AmmoCrateMenu(id, inv, buf)));

    /** 弹药台方块 */
    public static final RegistryObject<Block> AMMO_STATION = BLOCKS.register("ammo_station",
            () -> new AmmoStationBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> AMMO_STATION_ITEM = ITEMS.register("ammo_station",
            () -> new AmmoStationBlockItem(AMMO_STATION.get(), new Item.Properties()));

    /** 弹药台方块实体：盯着周围的火，过热直接炸 */
    public static final RegistryObject<BlockEntityType<AmmoStationBlockEntity>> AMMO_STATION_BE =
            BLOCK_ENTITIES.register("ammo_station",
                    () -> BlockEntityType.Builder.of(AmmoStationBlockEntity::new, AMMO_STATION.get()).build(null));

    /** 弹药台（物品形态）—— 右键一键补满。原创像素贴图 */
    public static final RegistryObject<Item> AMMO_STATION_TOOL = ITEMS.register("ammo_station_tool",
            () -> new AmmoStationItem(new Item.Properties()));

    /** 复合弹料：不看口径的通用弹药材料 */
    public static final RegistryObject<Item> AMMO_MATERIAL = ITEMS.register("ammo_material",
            () -> new AmmoMaterialItem(new Item.Properties()));

    // ==================== 生存弹药箱（三档，独立于创造弹药台）====================

    private static BlockBehaviour.Properties crateProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.5F, 6.0F)
                .sound(SoundType.WOOD)
                // 2026-11-16 修（C4）：多方块箱子不许活塞推 —— 推一格会让 2×2/1×2 散架错位
                .pushReaction(PushReaction.BLOCK)
                // 掉落由 AmmoCrateBlock.playerWillDestroy 自己处理（要带 BlockEntityTag），
                // 用 loot table 会再掉一个空箱子，拆一次变两个。
                .noLootTable();
    }

    public static final RegistryObject<Block> AMMO_CRATE = BLOCKS.register("ammo_crate",
            () -> new AmmoCrateBlock(crateProps(), AmmoCrateData.Tier.BASIC));
    public static final RegistryObject<Block> AMMO_CRATE_ADVANCED = BLOCKS.register("ammo_crate_advanced",
            () -> new AmmoCrateBlock(crateProps(), AmmoCrateData.Tier.INTERMEDIATE));
    public static final RegistryObject<Block> AMMO_CRATE_ELITE = BLOCKS.register("ammo_crate_elite",
            () -> new AmmoCrateBlock(crateProps(), AmmoCrateData.Tier.ADVANCED));

    public static final RegistryObject<Item> AMMO_CRATE_ITEM = ITEMS.register("ammo_crate",
            () -> new AmmoCrateBlockItem(AMMO_CRATE.get(), new Item.Properties(), AmmoCrateData.Tier.BASIC));
    public static final RegistryObject<Item> AMMO_CRATE_ADVANCED_ITEM = ITEMS.register("ammo_crate_advanced",
            () -> new AmmoCrateBlockItem(AMMO_CRATE_ADVANCED.get(), new Item.Properties(), AmmoCrateData.Tier.INTERMEDIATE));
    public static final RegistryObject<Item> AMMO_CRATE_ELITE_ITEM = ITEMS.register("ammo_crate_elite",
            () -> new AmmoCrateBlockItem(AMMO_CRATE_ELITE.get(), new Item.Properties(), AmmoCrateData.Tier.ADVANCED));

    public static final RegistryObject<BlockEntityType<AmmoCrateBlockEntity>> AMMO_CRATE_BE =
            BLOCK_ENTITIES.register("ammo_crate",
                    () -> BlockEntityType.Builder.of(AmmoCrateBlockEntity::new,
                            AMMO_CRATE.get(), AMMO_CRATE_ADVANCED.get(),
                            AMMO_CRATE_ELITE.get()).build(null));

    // ===== 弹药袋（升级物品）=====
    //
    // 5 级链的「获得途径」：基础是出生自带，往上 4 级各一个袋子。
    // 用法：主手拿袋子、副手拿枪、右键 → 副手那把枪的类别升一级。
    // 合成链也是按顺序的（金袋子要铁袋子当材料），和这里的严格顺序对得上。

    /** 皮革铜弹药袋 → 1 级（×1.5，2026-11-14 新增） */
    public static final RegistryObject<Item> AMMO_BAG_COPPER = ITEMS.register("ammo_bag_copper",
            () -> new AmmoTierItem(new Item.Properties(), 1));

    /** 铁制弹药袋 → 2 级（×2） */
    public static final RegistryObject<Item> AMMO_BAG_IRON = ITEMS.register("ammo_bag_iron",
            () -> new AmmoTierItem(new Item.Properties(), 2));

    /** 金制弹药袋 → 3 级（×4） */
    public static final RegistryObject<Item> AMMO_BAG_GOLD = ITEMS.register("ammo_bag_gold",
            () -> new AmmoTierItem(new Item.Properties(), 3));

    /** 钻石弹药袋 → 4 级（×8） */
    public static final RegistryObject<Item> AMMO_BAG_DIAMOND = ITEMS.register("ammo_bag_diamond",
            () -> new AmmoTierItem(new Item.Properties(), 4));

    /** 下界合金弹药袋 → 5 级（×16） */
    public static final RegistryObject<Item> AMMO_BAG_NETHERITE = ITEMS.register("ammo_bag_netherite",
            () -> new AmmoTierItem(new Item.Properties(), 5));

    /** 五个袋子，按等级顺序排好，物品栏/配方都照这个顺序走 */
    public static final RegistryObject<Item>[] AMMO_BAGS = new RegistryObject[]{
            AMMO_BAG_COPPER, AMMO_BAG_IRON, AMMO_BAG_GOLD, AMMO_BAG_DIAMOND, AMMO_BAG_NETHERITE
    };

    // ===== 弹药箱升级家族（2026-11-14）=====
    // 扩容四级（不可跳）：铁 ×2 → 金 ×4 → 钻 ×6 → 下界 ×20
    // 另外三条单级线：高效产出 +30% / 幸运 25% 省料 / 快速制作 -50%
    // ⚠️ 皮革铜不是箱子扩容，是弹药袋（背包）的，别加回这里。

    public static final RegistryObject<Item> CRATE_UPGRADE_IRON = ITEMS.register("crate_upgrade_iron",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.CAPACITY, 1));
    public static final RegistryObject<Item> CRATE_UPGRADE_GOLD = ITEMS.register("crate_upgrade_gold",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.CAPACITY, 2));
    public static final RegistryObject<Item> CRATE_UPGRADE_DIAMOND = ITEMS.register("crate_upgrade_diamond",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.CAPACITY, 3));
    public static final RegistryObject<Item> CRATE_UPGRADE_NETHERITE = ITEMS.register("crate_upgrade_netherite",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.CAPACITY, 4));

    public static final RegistryObject<Item> CRATE_UPGRADE_EFFICIENCY = ITEMS.register("crate_upgrade_efficiency",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.EFFICIENCY, 1));
    public static final RegistryObject<Item> CRATE_UPGRADE_LUCKY = ITEMS.register("crate_upgrade_lucky",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.LUCKY, 1));
    public static final RegistryObject<Item> CRATE_UPGRADE_SPEED = ITEMS.register("crate_upgrade_speed",
            () -> new CrateUpgradeItem(new Item.Properties(), CrateUpgradeType.SPEED, 1));
    public static final RegistryObject<Item>[] CRATE_UPGRADES = new RegistryObject[]{
            CRATE_UPGRADE_IRON, CRATE_UPGRADE_GOLD, CRATE_UPGRADE_DIAMOND, CRATE_UPGRADE_NETHERITE,
            CRATE_UPGRADE_EFFICIENCY, CRATE_UPGRADE_LUCKY, CRATE_UPGRADE_SPEED
    };

    /** 扩容包挂带：把身上那颗扩容包拆下来，等级 -1 并把包退回 */
    public static final RegistryObject<Item> AMMO_DETACH = ITEMS.register("ammo_detach",
            () -> new AmmoDetachItem(new Item.Properties()));

    // ===== 能源挂带（能量武器用，5 级）=====
    //
    // 用一次就穿在身上，玩家数据里记等级；没装挂带时能量枪保持原样（吃实体弹药）。

    public static final RegistryObject<Item> ENERGY_HARNESS_BASE = ITEMS.register("energy_harness_base",
            () -> new EnergyHarnessItem(new Item.Properties(), 0));
    public static final RegistryObject<Item> ENERGY_HARNESS_IRON = ITEMS.register("energy_harness_iron",
            () -> new EnergyHarnessItem(new Item.Properties(), 1));
    public static final RegistryObject<Item> ENERGY_HARNESS_GOLD = ITEMS.register("energy_harness_gold",
            () -> new EnergyHarnessItem(new Item.Properties(), 2));
    public static final RegistryObject<Item> ENERGY_HARNESS_DIAMOND = ITEMS.register("energy_harness_diamond",
            () -> new EnergyHarnessItem(new Item.Properties(), 3));
    public static final RegistryObject<Item> ENERGY_HARNESS_NETHERITE = ITEMS.register("energy_harness_netherite",
            () -> new EnergyHarnessItem(new Item.Properties(), 4));

    public static final RegistryObject<Item>[] ENERGY_HARNESSES = new RegistryObject[]{
            ENERGY_HARNESS_BASE, ENERGY_HARNESS_IRON, ENERGY_HARNESS_GOLD,
            ENERGY_HARNESS_DIAMOND, ENERGY_HARNESS_NETHERITE
    };

    /** 应急弹药补给包 —— 三档，各自独立物品。
     *  档位由构造函数传进去（现场决定，不用 NBT 猜）。 */
    public static final RegistryObject<Item> EMERGENCY_PACK_BASIC = ITEMS.register("emergency_pack_basic",
            () -> new EmergencyPackItem(new Item.Properties(), PackTier.BASIC));

    public static final RegistryObject<Item> EMERGENCY_PACK_ADVANCED = ITEMS.register("emergency_pack_advanced",
            () -> new EmergencyPackItem(new Item.Properties(), PackTier.ADVANCED));

    public static final RegistryObject<Item> EMERGENCY_PACK_ELITE = ITEMS.register("emergency_pack_elite",
            () -> new EmergencyPackItem(new Item.Properties(), PackTier.ELITE));

    /** 创造模式物品栏 */
    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.tacz_ammodata"))
                    .icon(() -> new ItemStack(AMMO_STATION_TOOL.get()))
                    .displayItems((params, output) -> {
                        output.accept(AMMO_STATION_TOOL.get());
                        output.accept(AMMO_STATION_ITEM.get());
                        output.accept(AMMO_CRATE_ITEM.get());
                        output.accept(AMMO_CRATE_ADVANCED_ITEM.get());
                        output.accept(AMMO_CRATE_ELITE_ITEM.get());

                        output.accept(AMMO_MATERIAL.get());
                        output.accept(EMERGENCY_PACK_BASIC.get());
                        output.accept(EMERGENCY_PACK_ADVANCED.get());
                        output.accept(EMERGENCY_PACK_ELITE.get());
                        for (RegistryObject<Item> bag : AMMO_BAGS) {
                            output.accept(bag.get());
                        }
                        output.accept(AMMO_DETACH.get());
                        for (RegistryObject<Item> up : CRATE_UPGRADES) {
                            output.accept(up.get());
                        }
                        for (RegistryObject<Item> h : ENERGY_HARNESSES) {
                            output.accept(h.get());
                        }
                    })
                    .build());

    public TaczAmmoData() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        CREATIVE_TABS.register(modBus);

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::onConfigLoad);
        modBus.addListener(this::onConfigReload);

        // 2026-11-16（D14）：ReloadHandler 已有 @Mod.EventBusSubscriber，别再手动注册一次
        // （EventBus 会去重，实际无害，纯冗余）
        // 玩家登录时同步一次弹药数据，HUD 才有初始值
        MinecraftForge.EVENT_BUS.register(PlayerJoinSync.class);
        // 调试命令 /ammodata
        MinecraftForge.EVENT_BUS.register(AmmoCommand.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // ===== 配置界面（Cloth Config，**可选**）=====
        //
        // 只在 Cloth Config 装了的时候才注册。
        // 没装的话这段整个跳过 —— 玩家照样进游戏，只是手动改 toml。
        //
        // ⚠️ 关键：ConfigScreen 那个类里 import 了 cloth 的类，
        //    所以必须用 isLoaded 判断 + 确保只有真装了才会走到 new ConfigScreenFactory，
        //    否则就是 NoClassDefFoundError。
        if (isClothConfigLoaded()) {
            ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (mc, parent) -> ConfigScreen.create(parent)));
            LOGGER.info("[TaCZ 弹药数据] 检测到 Cloth Config，已挂配置界面");
        } else {
            LOGGER.info("[TaCZ 弹药数据] 没装 Cloth Config，配置界面跳过（改 config/tacz_ammodata-common.toml 即可）");
        }

        LOGGER.info("[TaCZ 弹药数据] 初始化中...");
    }

    /** 构建标记：每次重打包手动改一下，启动日志里一眼能看出加载的是哪一版 */
    public static final String BUILD_STAMP = "1.0.0-beta / 角色配色+拆卸器改字 / 2026-11-16 22:30";

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[构建标记] {}", BUILD_STAMP);
        event.enqueueWork(() -> {
            Config.bake();
            AmmoNetwork.register();
        });
        // 打完日志顺便自查一遍容量表，方便排查
        LOGGER.info("[TaCZ 弹药数据] 就绪。7 类弹药表容量：");
        for (AmmoCategory c : AmmoCategory.values()) {
            LOGGER.info("  {} ({}) 基础 {} → 铁 {} → 金 {} → 钻石 {} → 下界合金 {}",
                    c.displayName, c.key(),
                    Config.baseCapacity(c), Config.ironCapacity(c),
                    Config.goldCapacity(c), Config.diamondCapacity(c),
                    Config.netheriteCapacity(c));
        }
    }

    private void onConfigLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            Config.bake();
        }
    }

    private void onConfigReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            Config.bake();
            LOGGER.info("[TaCZ 弹药数据] 配置已重载");
        }
    }

    /** 有没有装 Cloth Config —— 用 modid 判断，不碰它的类，所以永远不会炸 */
    private static boolean isClothConfigLoaded() {
        return net.minecraftforge.fml.ModList.get().isLoaded("cloth_config");
    }
}