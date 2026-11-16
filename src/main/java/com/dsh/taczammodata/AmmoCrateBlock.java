package com.dsh.taczammodata;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 生存弹药箱（2026-11-14 第三轮）。
 *
 * 交互只剩两条：
 *   空手右键           → 打开界面（存入 / 分配全在界面里）
 *   潜行 + 空手右键    → 快速把待分配点数灌满（不开界面）
 *
 * 手持任何物品右键一律 PASS —— 不再支持"右键把子弹/弹料塞进箱子"，
 * 因为那条路径绕过界面，玩家看不到容量和待分配，体验是断的。
 *
 * 三档共用这一个类，`tier` 决定收哪些类别：
 *   初级 = 手枪 / 冲锋
 *   中级 = + 步枪 / 霰弹
 *   高级 = 全部
 */
public class AmmoCrateBlock extends Block implements EntityBlock {

    /**
     * 多方块的位置标记（2026-11-14）。
     *
     * 中级（1×2）：
     *   FRONT → 玩家放的那格，带 BlockEntity
     *   BACK  → 朝 facing 方向的延伸格
     *
     * 大型（2×2）：
     *   NW / NE / SW / SE → 相对基座的四个象限，只有 NW 带 BlockEntity
     *
     * 单格箱子（初级/高级）永远只有 FRONT。
     */
    public enum Part implements StringRepresentable {
        FRONT("front"), BACK("back"),
        NW("nw"), NE("ne"), SW("sw"), SE("se");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART =
            EnumProperty.create("part", Part.class);

    /** 朝向（两格箱子才用；单格/大型固定 north） */
    public static final DirectionProperty FACING = DirectionProperty.create("facing",
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

    public final AmmoCrateData.Tier tier;

    public AmmoCrateBlock(Properties properties, AmmoCrateData.Tier tier) {
        super(properties);
        this.tier = tier;
        registerDefaultState(this.stateDefinition.any()
                .setValue(PART, Part.FRONT)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, FACING);
    }

    /** 这档箱子是不是两格（1×2） */
    public boolean isDouble() {
        return tier == AmmoCrateData.Tier.INTERMEDIATE;
    }

    /** 这档箱子是不是 2×2 大方块（2026-11-14 主人定：高级就是 2×2） */
    public boolean isQuad() {
        return tier == AmmoCrateData.Tier.ADVANCED;
    }

    /** 宽轴：从正面法线顺时针转 90°，与 blockstate 的 y 旋转一致（N→E→S→W） */
    public static Direction widthAxis(Direction facing) {
        return facing.getClockWise();
    }

    /** 后半方块在世界里的位置（横排在宽轴正方向） */
    public static BlockPos backPos(BlockPos front, Direction facing) {
        return front.relative(widthAxis(facing));
    }

    /** 从任意一格反推基座（带 BlockEntity 的那格；2×2 基座在宽轴低端 + 下层） */
    public BlockPos basePos(BlockPos pos, BlockState state) {
        Direction w = widthAxis(state.getValue(FACING));
        if (isQuad()) {
            return switch (state.getValue(PART)) {
                case NW -> pos.below();
                case SE -> pos.relative(w.getOpposite());
                case NE -> pos.relative(w.getOpposite()).below();
                default -> pos;   // SW = 基座
            };
        }
        if (isDouble() && state.getValue(PART) == Part.BACK) {
            return pos.relative(w.getOpposite());
        }
        return pos;
    }

    /** 这个方块占的所有格子（基座 + 延伸），延伸方向一律取宽轴 */
    private List<BlockPos> cellPositions(BlockPos base, BlockState state) {
        Direction w = widthAxis(state.getValue(FACING));
        List<BlockPos> cells = new ArrayList<>();
        cells.add(base);
        if (isQuad()) {
            // 立起来的 2×2 面：宽 2（沿宽轴）、高 2（上）、厚 1
            cells.add(base.above());
            cells.add(base.relative(w));
            cells.add(base.relative(w).above());
        } else if (isDouble()) {
            // 1×2 横排：两格沿宽轴并排，厚度仍是 1
            cells.add(base.relative(w));
        }
        return cells;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState();
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        // 正面永远朝着玩家：玩家面朝方向的相反方向
        Direction facing = ctx.getHorizontalDirection().getOpposite();

        if (isQuad()) {
            // 2×2：点击的那格当基座，检查其余三格能不能放
            BlockState placed = state.setValue(PART, Part.SW).setValue(FACING, facing);
            for (BlockPos p : cellPositions(pos, placed)) {
                if (p.equals(pos)) {
                    continue;
                }
                if (!level.isEmptyBlock(p) || !level.getFluidState(p).isEmpty()) {
                    return null;   // 有障碍，放不下
                }
            }
            return placed;
        }

        if (isDouble()) {
            // 1×2 横排：第二格在宽轴正方向
            BlockPos back = pos.relative(widthAxis(facing));
            if (!level.isEmptyBlock(back) || !level.getFluidState(back).isEmpty()) {
                return null;   // 旁边没地方，放不下
            }
        }
        // 单格（初级）也要跟着玩家转 —— 之前漏了这一步，导致正面永远朝北
        state = state.setValue(FACING, facing);
        return state;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        Direction facing = state.getValue(FACING);
        Direction w = widthAxis(facing);
        if (isQuad()) {
            // 立起来的 2×2：基座在宽轴低端 + 下层，往上、往宽轴正方向各长一格
            BlockState s = defaultBlockState().setValue(FACING, facing);
            level.setBlock(pos.above(), s.setValue(PART, Part.NW), Block.UPDATE_ALL);
            level.setBlock(pos.relative(w), s.setValue(PART, Part.SE), Block.UPDATE_ALL);
            level.setBlock(pos.relative(w).above(), s.setValue(PART, Part.NE), Block.UPDATE_ALL);
            return;
        }
        if (!isDouble()) {
            return;
        }
        BlockPos back = backPos(pos, facing);
        BlockState backState = defaultBlockState()
                .setValue(PART, Part.BACK)
                .setValue(FACING, facing);
        level.setBlock(back, backState, Block.UPDATE_ALL);
    }

    /** 2026-11-16（A1/C4）：同组连带清理的重入保护 */
    private static final ThreadLocal<Boolean> CLEANING = ThreadLocal.withInitial(() -> false);

    /**
     * 2026-11-16 修（A1/C4）：任意一格被移除时（爆炸、被替换、其它模组拆方块），
     * 把同组其它格一并清掉，避免留下孤儿方块：
     *   中级炸掉一格 → 剩一格打不开；高级炸掉基座 → 其余三格还飘着。
     *
     * 玩家自己拆走 playerWillDestroy（带掉落 + 抑制掉落），这里只兜底非玩家路径。
     * movedByPiston=true 跳过：活塞推动属于"搬运"不是"销毁"，顺手清会把整组抹掉。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !movedByPiston && !state.is(newState.getBlock())
                && (isQuad() || isDouble()) && !CLEANING.get()) {
            CLEANING.set(true);
            try {
                BlockPos base = basePos(pos, state);
                for (BlockPos other : cellPositions(base, state)) {
                    if (!other.equals(pos) && level.getBlockState(other).is(this)) {
                        level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            } finally {
                CLEANING.set(false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos base = basePos(pos, state);

            if (!player.isCreative()) {
                ItemStack drop = new ItemStack(this);
                if (level.getBlockEntity(base) instanceof AmmoCrateBlockEntity crate
                        && (!crate.data().isEmpty() || crate.cooking().hasContent())) {
                    CompoundTag beTag = crate.saveWithoutMetadata();
                    if (!beTag.isEmpty()) {
                        drop.getOrCreateTag().put("BlockEntityTag", beTag);
                    }
                }
                Block.popResource(level, pos, drop);
            }

            // 1×2 / 2×2：其余格子一起删（不各自掉落）
            for (BlockPos other : cellPositions(base, state)) {
                if (!other.equals(pos) && level.getBlockState(other).getBlock() == this) {
                    level.setBlock(other, Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_CLIENTS);
                }
            }
            level.removeBlock(pos, false);
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 被爆炸波及 → 二次殉爆（2026-11-16 新增，和弹药台一个规矩）。
     *
     * 三个弹药箱现在都易燃易爆了，和文案里的热源警告对得上。
     * 威力按箱内存量算（见 {@link AmmoCrateBlockEntity#explosionPower()}），
     * 所以空箱只是普通爆炸，塞满的箱子才炸得狠。
     *
     * 只在**基座格**触发：中级/高级有多个格子，MC 会对每一格都调一次，
     * 从基座统一炸一次就够，其余格会被那次爆炸带走。
     */
    @Override
    public void wasExploded(Level level, BlockPos pos, net.minecraft.world.level.Explosion explosion) {
        super.wasExploded(level, pos, explosion);
        if (level.isClientSide()) {
            return;
        }
        // ⚠️ 2026-11-16（崩溃修复，实测 TNT 炸高级箱直接崩）：
        //   wasExploded 被调用的时候，这个位置的方块**已经是空气**了 ——
        //   再 level.getBlockState(pos) 拿到的是 Block{minecraft:air}，
        //   对它读 state.getValue(FACING) 会抛
        //     IllegalArgumentException: Cannot get property facing as it does not exist in Block{minecraft:air}
        //   所以这里**绝对不能**用 level.getBlockState(pos) 反推 basePos。
        //
        //   新的判断方式：多格箱子里**只有基座格带 BlockEntity**（见 newBlockEntity），
        //   所以"能拿到 AmmoCrateBlockEntity"就是基座格；非基座格直接跳过，
        //   由基座那一次殉爆把其余格子一起带走。
        if (!(level.getBlockEntity(pos) instanceof AmmoCrateBlockEntity crate)) {
            return;
        }
        HazardUtil.chainDetonate(level, pos, crate.explosionPower());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        BlockPos base = basePos(pos, state);
        if (!base.equals(pos)) {
            return this.useFront(level.getBlockState(base), level, base, player, hand, hit);
        }
        return this.useFront(state, level, pos, player, hand, hit);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        Part part = state.getValue(PART);
        if (isQuad()) {
            return part == Part.SW ? new AmmoCrateBlockEntity(pos, state) : null;
        }
        if (part == Part.BACK) {
            return null;
        }
        return new AmmoCrateBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof AmmoCrateBlockEntity crate) {
                AmmoCrateBlockEntity.serverTick(lvl, pos, st, crate);
            }
        };
    }

    /**
     * 真正的交互（前半专用）。
     *
     * 2026-11-14 主人定：右键别设太死。除了**枪**、**方块**，其他东西
     * （镐子、斧头、材料、食物、补给包……）右键也直接开界面/灌包。
     */
    private InteractionResult useFront(BlockState state, Level level, BlockPos pos, Player player,
                                       InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getMainHandItem();
        if (!canOpenWith(held)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!(level.getBlockEntity(pos) instanceof AmmoCrateBlockEntity crate)) {
            return InteractionResult.PASS;
        }

        // ===== 补给包：右键把箱子待分配点数灌进包里（不开界面）=====
        if (held.getItem() instanceof EmergencyPackItem pack) {
            // ⚠️ 一摞包共享同一份 NBT：必须拆一件出来单独灌，否则一次灌整摞 = 刷点
            ItemStack target = held;
            boolean split = held.getCount() > 1;
            if (split) {
                target = held.copy();
                target.setCount(1);
            }
            int pending = crate.cooking().pending();
            int added = pack.fill(target, pending);
            if (added > 0) {
                crate.cooking().setPending(pending - added);
                crate.setChanged();
                if (split) {
                    held.shrink(1);
                    if (!player.getInventory().add(target)) {
                        player.drop(target, false);
                    }
                }
                if (player instanceof ServerPlayer sp) {
                    sp.displayClientMessage(Component.literal(
                                    "§a灌入 " + added + " 弹药点（包里共 " + pack.remaining(target) + "）")
                            .withStyle(net.minecraft.ChatFormatting.GREEN), true);
                }
            } else if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(Component.literal(pack.isFull(target)
                                ? "§7这个包已经满了"
                                : "§7没有可灌的点数，或这个包必须先用空")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            }
            return InteractionResult.SUCCESS;
        }

        // 老存档里箱子自己的分类存量先并进待分配，不然这些子弹永远发不出去
        crate.cooking().absorbStored();

        // ===== 潜行 + 空手 → 把待分配点数全部发到玩家弹药表（不开界面）=====
        if (player.isShiftKeyDown()) {
            int poured = 0;
            for (AmmoCategory c : AmmoCategory.values()) {
                if (!crate.data().tier().allows(c)) {
                    continue;
                }
                int pending = crate.cooking().pending();
                if (pending <= 0) {
                    break;
                }
                int room = AmmoDataStore.capacity(player, c) - AmmoDataStore.get(player, c);
                if (room <= 0) {
                    continue;
                }
                int use = Math.min(pending, room);
                int added = AmmoDataStore.add(player, c, use);
                crate.cooking().setPending(pending - added);
                poured += added;
            }
            if (poured > 0) {
                crate.setChanged();
            }
            if (player instanceof ServerPlayer sp) {
                if (poured > 0) {
                    ReloadHandler.syncGunsToTable(sp);
                    AmmoNetwork.syncAll(sp);
                    sp.displayClientMessage(Component.literal(
                                    "§a快速补充：" + poured + " 点已分配")
                            .withStyle(net.minecraft.ChatFormatting.GREEN), true);
                } else {
                    sp.displayClientMessage(Component.literal(
                                    "§7没有待分配的点数（打开界面放弹料 / 子弹）")
                            .withStyle(net.minecraft.ChatFormatting.GRAY), true);
                }
            }
            return InteractionResult.SUCCESS;
        }

        // ===== 右键 → 打开界面（手持工具/材料也可以）=====
        if (player instanceof ServerPlayer sp) {
            // 开界面前先同步一次玩家弹药表，保证界面里的「当前/上限」不是旧缓存
            AmmoNetwork.syncAll(sp);
            // ⚠️ 必须走 Forge 的 openScreen 并带上 pos，原版 openMenu 不会写附加数据，
            //    客户端 IContainerFactory.create 里的 buf 会是 null，界面直接崩。
            net.minecraftforge.network.NetworkHooks.openScreen(sp,
                    new net.minecraft.world.SimpleMenuProvider(
                            (id, inv, p) -> new AmmoCrateMenu(id, inv, crate, pos),
                            Component.literal(crate.data().tier().displayName + "弹药箱")),
                    buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeVarInt(crate.data().tier().ordinal());
                    });
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 手持这个物品时，右键箱子还要不要按"开界面"处理。
     *
     * 2026-11-14 主人定：只有枪和方块例外，其他都放开。
     *   - 枪：右键是瞄准/射击，不能抢
     *   - 方块：右键是放置，不能抢
     *   - 其余（镐子、斧头、食物、材料……）→ 开界面
     */
    private static boolean canOpenWith(ItemStack held) {
        if (held.isEmpty()) {
            return true;
        }
        if (held.getItem() instanceof com.tacz.guns.api.item.IGun) {
            return false;
        }
        if (held.getItem() instanceof net.minecraft.world.item.BlockItem) {
            return false;
        }
        return true;
    }
}