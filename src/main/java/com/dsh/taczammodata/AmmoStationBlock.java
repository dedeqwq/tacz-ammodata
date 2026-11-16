package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 弹药台（方块版）—— 右键一键补满。
 * 和物品版共用同一套逻辑。
 *
 * 六面贴图：正面是「取弹口」，所以方块带朝向 —— 放下去时正面朝着玩家。
 */
public class AmmoStationBlock extends Block implements EntityBlock {

    /** 水平朝向（只有 N/E/S/W，不用管上下） */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public AmmoStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面对着放箱子的人
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AmmoStationBlockEntity(pos, state);
    }

    /**
     * 让方块实体每 tick 跑一次「查火」。
     * 客户端不用跑，只有服务端需要（爆炸、发提示都在服务端）。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof AmmoStationBlockEntity station) {
                AmmoStationBlockEntity.tick(lvl, pos, st, station);
            }
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @javax.annotation.Nullable net.minecraft.world.entity.LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // 创造模式放下的台子记一笔：殉爆直接当核弹（威力 100）
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof AmmoStationBlockEntity be) {
            be.setCreativePlaced(placer instanceof Player player && player.isCreative());
        }
    }

    /**
     * 被爆炸波及 —— 这里是**唯一**能在方块消失前拿到它的钩子（2026-11-16 三次整改后定稿）。
     *
     * MC 在 Explosion#finalizeExplosion 里逐个方块调用这个方法，
     * 此时 BlockEntity 还在，参数直接给 BlockPos 和 Explosion。
     *
     * 详细比较（为什么不用 ExplosionEvent.Detonate / Start）见
     * {@link StationHazardHandler} 的类注释。
     */
    @Override
    public void wasExploded(Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
        super.wasExploded(level, pos, explosion);
        if (level.isClientSide()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof AmmoStationBlockEntity be) {
            StationHazardHandler.onStationCaughtInExplosion(level, pos, explosion, be);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }

        // 拿到台子的方块实体 —— 点数制：补表从这里扣，子弹存进这里
        if (!(level.getBlockEntity(pos) instanceof AmmoStationBlockEntity station)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack held = player.getMainHandItem();

        // ===== 手持 TaCZ 子弹右键 → 存进台子（1 发 = 该口径的弹药点）=====
        var ammo = com.tacz.guns.api.item.IAmmo.getIAmmoOrNull(held);
        if (ammo != null) {
            int rounds = held.getCount();
            int weight = AmmoWeights.of(ammo.getAmmoId(held));
            if (rounds <= 0 || weight <= 0) {
                return InteractionResult.SUCCESS;
            }
            int gain = rounds * weight;
            int added = station.addPoints(gain);
            if (added <= 0) {
                player.displayClientMessage(Component.literal(String.format(
                        "§c台子满了（%d / %d 点），子弹没动",
                        station.getPoints(), station.capacity()))
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                return InteractionResult.SUCCESS;
            }
            // 只扣真正存进去的那部分发数，剩下留在手上（别整组蒸发）
            int consumed = added / weight;
            if (consumed <= 0) {
                // 2026-11-17 修（零头白得刷点）：addPoints 已经把 added 写进台子了，
                // 但一发都扣不出来 → 必须把这部分点数退回去，否则反复右键能一点一点刷满。
                station.setPoints(station.getPoints() - added);
                player.displayClientMessage(Component.literal(String.format(
                        "§c台子快满了（%d / %d 点），这一发都塞不下",
                        station.getPoints(), station.capacity()))
                        .withStyle(net.minecraft.ChatFormatting.RED), true);
                return InteractionResult.SUCCESS;
            }
            // ⚠️ 2026-11-16 修（C8 零头白得）：addPoints 收的是「点」，扣枪上的是「发」。
            //   除不尽的那部分点数台子白拿了 —— 比如 RPG 火箭 weight=5、台子只剩 7 点：
            //     added=7 → consumed = 7/5 = 1 发（玩家只掉 1 发 = 5 点）
            //     台子却真收了 7 点 → 凭空多 2 点，反复操作就能刷。
            //   现在把「收下的点」对齐到「实际扣掉发数 × 权重」，零头退回。
            int credited = consumed * weight;
            if (credited != added) {
                station.setPoints(station.getPoints() - (added - credited));
            }
            held.shrink(consumed);
            player.displayClientMessage(Component.literal(String.format(
                    "§a存入 %d 发（+%d 弹药点，台内共 %d / %d）",
                    consumed, credited, station.getPoints(), station.capacity())), true);
            return InteractionResult.SUCCESS;
        }

        // ===== 手持补给包右键 → 整摞灌满 =====
        //
        // 2026-11-09 主人定：弹药箱先做成创造专属（无限供应），生存配方不给。
        // 补给包用空之后就是空壳，靠这里重新灌满。
        // 能叠在一起说明整摞余量一样，所以整摞一起灌是安全的。

        if (held.getItem() instanceof EmergencyPackItem pack) {
            int added = pack.fillFull(held);
            if (added > 0) {
                player.displayClientMessage(Component.literal(String.format(
                        "§a灌满 %d 个补给包（每个 +%d 弹药点）", held.getCount(), added)), true);
            } else if (pack.isFull(held)) {
                player.displayClientMessage(Component.literal("§7已经满了")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            } else {
                player.displayClientMessage(Component.literal("§7铁罐必须把里面的弹药完全用完，才能重新封装")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            }
            return InteractionResult.SUCCESS;
        }

        // ===== 默认：一键补满全部（点数制：从台内扣）=====
        // 2026-11-09 主人要求：创造台对谁都无限 —— 创造模式放的台子一律不扣点，
        // 创造模式玩家操作普通台也不扣。
        boolean infinite = player.isCreative() || station.isCreativePlaced();
        int budget = infinite ? Integer.MAX_VALUE : station.getPoints();
        if (budget <= 0) {
            player.displayClientMessage(Component.literal(
                    "§c台子里没有弹药点了 —— 拿子弹右键存一些进来")
                    .withStyle(net.minecraft.ChatFormatting.RED), true);
            return InteractionResult.SUCCESS;
        }

        int spent = AmmoDataStore.refillAllWithBudget(player, budget);
        if (!infinite) {
            station.setPoints(station.getPoints() - spent);
        }
        AmmoNetwork.syncAll(sp);

        if (Config.logReload) {
            TaczAmmoData.LOGGER.info("[弹药台] 玩家 {} 补满弹药表，消耗 {} 弹药点，台内剩 {}",
                    player.getName().getString(), spent, station.getPoints());
        }
        if (spent > 0) {
            player.displayClientMessage(Component.literal(String.format(
                    "§a已补满（消耗 %d 弹药点，台内剩 %d）", spent, station.getPoints())), true);
        } else {
            player.displayClientMessage(Component.literal("§7弹药表都是满的")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), true);
        }

        return InteractionResult.SUCCESS;
    }
}