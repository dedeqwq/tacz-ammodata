package com.dsh.taczammodata;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 调试/管理命令：/ammodata
 *
 *   /ammodata list                  —— 列出玩家 7 类弹药表的当前量/上限/等级
 *   /ammodata set <类> <数>          —— 设置某一类的数量
 *   /ammodata tier <类> <0-4>        —— 设置某一类的弹药袋等级（0基础/1铁/2金/3钻石/4下界合金）
 *   /ammodata upgrade <类>           —— 升一级
 *   /ammodata fill                  —— 全部补满
 *   /ammodata held                  —— 显示当前手持枪属于哪一类
 *   /ammodata types                 —— 列出 TaCZ 认到的所有枪械分类
 */
public final class AmmoCommand {

    private AmmoCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("ammodata")
                // ⚠️ 2026-11-14 修：原来整条命令是 hasPermission(0)，
                //    普通玩家在生存服里能直接 /ammodata set rifle 1440 刷满弹药表，
                //    也能 /ammodata fill -- 所有权限检查等于没有。
                //    现在拆两级：
                //      list / held / weights       → 谁都能看（查自己的数据）
                //      其余（set/fill/tier/upgrade/energy/harness/weights reload）
                //                                  → 需要 OP（2 级）
                .requires(src -> src.hasPermission(0))

                .then(Commands.literal("list").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Component.literal("§b===== 弹药容量表（单位：点）====="), false);
                    for (AmmoCategory c : AmmoCategory.values()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                "§7%-10s §f%4d §7/ %-5d  §8[%s级]",
                                c.key(), AmmoDataStore.get(p, c), AmmoDataStore.capacity(p, c),
                                Config.tierName(AmmoDataStore.getTier(p, c)))), false);
                    }
                    return 1;
                }))

                .then(Commands.literal("fill").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    int n = AmmoDataStore.refillAll(p);
                    AmmoNetwork.syncAll(p);
                    ctx.getSource().sendSuccess(() -> Component.literal("§a已补满 " + n + " 类弹药表"), false);
                    return n;
                }))

                .then(Commands.literal("held").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    AmmoCategory c = AmmoCategory.ofGun(p.getMainHandItem());
                    if (c == null) {
                        ctx.getSource().sendFailure(Component.literal("§c手持的不是 TaCZ 枪械"));
                        return 0;
                    }
                    AmmoWeights.ensureLoaded();
                    var ammoId = GunHelper.getAmmoId(p.getMainHandItem());
                    int weight = AmmoWeights.of(ammoId);
                    int points = AmmoDataStore.get(p, c);
                    int cap = AmmoDataStore.capacity(p, c);
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "§b当前手持: §f%s §7(%s, gunType=%s)",
                            c.displayName, c.key(), c.gunType)), false);
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "§7口径 §f%s §7权重 §a%d 点/发 §7→ 表里 %d 点 = §f%d 发 §7(上限 %d 发)",
                            ammoId, weight, points, AmmoWeights.rounds(points, weight),
                            AmmoWeights.rounds(cap, weight))), false);
                    return 1;
                }))

                .then(Commands.literal("weights")
                        .executes(ctx -> {
                            int n = AmmoWeights.size();
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "§b口径权重表：§f%d §b个口径\n§7文件：§f%s",
                                    n, AmmoWeights.configFile())), false);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§7查具体口径：看日志，或直接编辑那个 json；改完 §f/ammodata weights reload"), false);
                            return n;
                        })
                        .then(Commands.literal("reload").requires(src -> src.hasPermission(2)).executes(ctx -> {
                            AmmoWeights.reload();
                            EnergyStore.reloadCosts();
                            int n = AmmoWeights.size();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a权重表已重载：" + n + " 个口径（能量成本表也一起重读了）"), false);
                            return n;
                        })
                        .then(Commands.literal("dump").executes(ctx -> {
                            String s = AmmoWeights.dump();
                            for (String line : s.split("\n")) {
                                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            return 1;
                        }))))

                .then(Commands.literal("upgrade").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("category", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String key = StringArgumentType.getString(ctx, "category");
                                    AmmoCategory c = AmmoCategory.byKey(key);
                                    if (c == null) {
                                        ctx.getSource().sendFailure(Component.literal("§c未知类别: " + key));
                                        return 0;
                                    }
                                    int t = Math.min(AmmoDataStore.getTier(p, c) + 1, 5);
                                    AmmoDataStore.setTier(p, c, t);
                                    AmmoNetwork.syncAll(p);
                                    int tt = t;
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "§a%s 已升级到 §f%s级§a，上限 %d",
                                            c.displayName, Config.tierName(tt), AmmoDataStore.capacity(p, c))), false);
                                    return t;
                                })))

                .then(Commands.literal("tier").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("category", StringArgumentType.word())
                                // ⚠️ 2026-11-14 修：原来上限写 4，但 tier 5（下界合金）是合法档位，
                                //    /ammodata tier rifle 5 直接被 Brigadier 拒绝，命令树和实际等级链对不上。
                                .then(Commands.argument("tier", IntegerArgumentType.integer(0, 5))
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            AmmoCategory c = AmmoCategory.byKey(StringArgumentType.getString(ctx, "category"));
                                            if (c == null) {
                                                ctx.getSource().sendFailure(Component.literal("§c未知类别"));
                                                return 0;
                                            }
                                            int t = IntegerArgumentType.getInteger(ctx, "tier");
                                            AmmoDataStore.setTier(p, c, t);
                                            AmmoNetwork.syncAll(p);
                                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                                    "§a%s 设为 %s级，上限 %d",
                                                    c.displayName, Config.tierName(t), AmmoDataStore.capacity(p, c))), false);
                                            return t;
                                        }))))

                // ===== 能量槽（能量武器用）=====
                .then(Commands.literal("energy").requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            int tier = EnergyStore.getTier(p);
                            if (tier < 0) {
                                ctx.getSource().sendFailure(Component.literal("§c还没装背包电源"));
                                return 0;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "§b能量槽：§f%d §7/ %d RF  §7(%d%%)  §8[%s级电源]",
                                    EnergyStore.get(p), EnergyStore.capacity(p),
                                    EnergyStore.percent(p), Config.tierName(tier))), false);
                            return EnergyStore.get(p);
                        })
                        .then(Commands.argument("rf", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    int rf = IntegerArgumentType.getInteger(ctx, "rf");
                                    EnergyStore.set(p, rf);
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "§a能量已设为 %d / %d RF",
                                            EnergyStore.get(p), EnergyStore.capacity(p))), false);
                                    return rf;
                                }))
                        .then(Commands.literal("probe").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            ctx.getSource().sendSuccess(() -> Component.literal("§b===== 能量探测 ====="), false);
                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                    "§7玩家位置：§f%s", p.blockPosition().toShortString())), false);

                            int radius = 3;
                            int found = 0;
                            BlockPos base = p.blockPosition();
                            for (int dy = -2; dy <= 2; dy++) {
                                for (int dx = -radius; dx <= radius; dx++) {
                                    for (int dz = -radius; dz <= radius; dz++) {
                                        BlockPos pos = base.offset(dx, dy, dz);
                                        BlockEntity be = p.level().getBlockEntity(pos);
                                        if (be == null) {
                                            continue;
                                        }
                                        var storage = be.getCapability(
                                                net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, null)
                                                .orElse(null);
                                        if (storage == null) {
                                            continue;
                                        }
                                        found++;
                                        int relX = dx, relY = dy, relZ = dz;
                                        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                                "§a[%d,%d,%d] §f%s §7→ %d / %d RF，可抽 %d",
                                                relX, relY, relZ,
                                                be.getBlockState().getBlock().getName().getString(),
                                                storage.getEnergyStored(), storage.getMaxEnergyStored(),
                                                storage.extractEnergy(1, true))), false);
                                    }
                                }
                            }
                            if (found == 0) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§c附近 7x5x7 内没有找到带 Forge 能量接口的方块"), false);
                            }

                            int items = 0;
                            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                                var st = p.getInventory().getItem(i);
                                if (st.isEmpty()) {
                                    continue;
                                }
                                var es = st.getCapability(
                                        net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, null)
                                        .orElse(null);
                                if (es != null) {
                                    items++;
                                    int slot = i;
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "§7背包[%d] §f%s §7→ %d / %d RF",
                                            slot, st.getHoverName().getString(),
                                            es.getEnergyStored(), es.getMaxEnergyStored())), false);
                                }
                            }
                            if (items == 0) {
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                        "§8背包里没有带能量的物品"), false);
                            }
                            return found + items;
                        })))

                .then(Commands.literal("harness").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("tier", IntegerArgumentType.integer(0, 4))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    int t = IntegerArgumentType.getInteger(ctx, "tier");
                                    EnergyStore.setTier(p, t);
                                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                            "§a背包电源设为 %s级，上限 %d RF",
                                            Config.tierName(t), EnergyStore.capacity(p))), false);
                                    return t;
                                })))

                .then(Commands.literal("set").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("category", StringArgumentType.word())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                                            AmmoCategory c = AmmoCategory.byKey(StringArgumentType.getString(ctx, "category"));
                                            if (c == null) {
                                                ctx.getSource().sendFailure(Component.literal("§c未知类别"));
                                                return 0;
                                            }
                                            int amt = IntegerArgumentType.getInteger(ctx, "amount");
                                            AmmoDataStore.set(p, c, amt);
                                            AmmoNetwork.syncAll(p);
                                            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                                                    "§a%s 设为 %d/%d", c.displayName,
                                                    AmmoDataStore.get(p, c), AmmoDataStore.capacity(p, c))), false);
                                            return amt;
                                        })))));
    }
}