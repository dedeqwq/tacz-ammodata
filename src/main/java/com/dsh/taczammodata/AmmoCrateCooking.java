package com.dsh.taczammodata;

import com.tacz.guns.api.item.IAmmo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 弹药箱的「制作 / 转换」数据（2026-11-14 第三轮）。
 *
 * ===== 两个输入槽 =====
 *
 * 左槽（fuel）：复合弹料 → 按 COOK_TICKS 制作成待分配点数（每个 200 点）
 * 右槽（ammo）：TaCZ 子弹   → **立即**按口径权重表折算成待分配点数
 *
 * 两者最终都进 pending，玩家在界面里点 [+50] / [满] 分配到具体类别。
 * 待分配点数上限 = 本档允许类别的容量之和。
 *
 * 右键不再接收任何物品；所有放入都只能通过界面。
 */
public class AmmoCrateCooking {

    // 存档键用的是字面量 "Fuel" / "Ammo" / "Pending"（和旧存档兼容，别改）

    // 制作时长 / 单次产出 / 高效加成 / 幸运概率全部走 Config.crate*（2026-11-14 配置化）


    private final AmmoCrateData data;

    private ItemStack fuel = ItemStack.EMPTY;
    private ItemStack ammoInput = ItemStack.EMPTY;
    private int pending = 0;
    private int cookProgress = 0;

    public AmmoCrateCooking(AmmoCrateData data) {
        this.data = data;
    }

    // ==================== 输入槽 ====================

    public ItemStack fuel() {
        return fuel;
    }

    public void setFuel(ItemStack stack) {
        this.fuel = stack == null ? ItemStack.EMPTY : stack;
        if (this.fuel.isEmpty()) {
            this.cookProgress = 0;
        }
    }

    public ItemStack ammoInput() {
        return ammoInput;
    }

    public void setAmmoInput(ItemStack stack) {
        this.ammoInput = stack == null ? ItemStack.EMPTY : stack;
    }

    /** 玩家往左槽里放东西（只能放弹料） */
    public boolean canInsert(ItemStack stack) {
        return stack.getItem() instanceof AmmoMaterialItem;
    }

    // ==================== 燃料制作 ====================


    /** 当前一次制作需要的 tick（快速制作减半，读配置） */
    public int cookTicks() {
        return data.hasSpeed() ? Config.crateSpeedCookTicks : Config.crateCookTicks;
    }

    /** 当前一个弹料的产出（高效产出加成，读配置） */
    public int outputPoints() {
        int base = Config.cratePointsPerMaterial;
        return data.hasEfficiency()
                ? base * (100 + Config.crateEfficiencyBonusPct) / 100
                : base;
    }

    public int cookProgressPercent() {
        int t = cookTicks();
        return t <= 0 ? 0 : Math.min(100, cookProgress * 100 / t);
    }

    /**
     * 每 tick 调一次（server）。
     *
     * @return 这一 tick 有没有变化（变了才需要同步给客户端）
     */
    public boolean tick(net.minecraft.util.RandomSource random) {
        if (fuel.isEmpty() || !canInsert(fuel)) {
            cookProgress = 0;
            return false;
        }
        // 剩余容量不够放下一次产出就停手（高效产出会抬高单次产出）
        int output = outputPoints();
        if (data.genericCapacity() - pending < output) {
            cookProgress = 0;
            return false;
        }
        cookProgress++;
        if (cookProgress >= cookTicks()) {
            cookProgress = 0;
            // 幸运：有概率不消耗弹料，等于白赚一次产出
            boolean luckySave = data.hasLucky()
                    && random.nextFloat() * 100.0F < Config.crateLuckyChancePct;
            if (!luckySave) {
                fuel.shrink(1);
                if (fuel.isEmpty()) {
                    fuel = ItemStack.EMPTY;
                }
            }
            pending += output;
            return true;
        }
        return cookProgress % 10 == 0;   // 每 10 tick 同步一次进度
    }

    // ==================== 子弹转换 ====================

    /**
     * 把右槽里的 TaCZ 子弹按口径权重表折算成待分配点数。
     * 立即完成，不排队：放进多少转多少（受剩余容量限制）。
     *
     * @return 这一 tick 转出了多少点（0 = 没子弹 / 放的不是子弹 / 点数满了）
     */
    public int convertAmmo() {
        if (ammoInput.isEmpty()) {
            return 0;
        }
        IAmmo ammo = IAmmo.getIAmmoOrNull(ammoInput);
        if (ammo == null) {
            return 0;
        }
        int room = data.genericCapacity() - pending;
        if (room <= 0) {
            return 0;
        }
        ResourceLocation ammoId = ammo.getAmmoId(ammoInput);
        int weight = Math.max(1, AmmoWeights.of(ammoId));
        // 高效产出加成：用「整组 × 百分比」算，别按单发截断
        // （9mm 权重 1，按单发 1×130/100 会截成 1，等于没加成）
        int bonusPct = data.hasEfficiency() ? Config.crateEfficiencyBonusPct : 0;
        long perRoundX100 = (long) weight * (100 + bonusPct);
        int maxRounds = (int) Math.min(Integer.MAX_VALUE, (long) room * 100 / perRoundX100);
        int rounds = Math.min(ammoInput.getCount(), maxRounds);
        if (rounds <= 0) {
            return 0;
        }
        int added = (int) ((long) rounds * perRoundX100 / 100);
        pending += added;
        ammoInput.shrink(rounds);
        if (ammoInput.isEmpty()) {
            ammoInput = ItemStack.EMPTY;
        }
        return added;
    }

    // ==================== 待分配点数 ====================

    public int pending() {
        return pending;
    }

    /**
     * 箱子里「有没有东西值得存档」。
     *
     * ⚠️ 2026-11-14 修：以前拆箱只查 {@link AmmoCrateData#isEmpty()}，
     * 而那是老版「分类存量 + 通用池」，新逻辑全在 pending / 两个输入槽里 ——
     * 结果玩家灌了一堆弹料还没分配就拆箱子，BlockEntityTag 不写，pending 全丢。
     */
    public boolean hasContent() {
        return pending > 0 || !fuel.isEmpty() || !ammoInput.isEmpty();
    }

    public void setPending(int value) {
        this.pending = Math.max(0, value);
    }


    /**
     * 把箱子里旧版的「分类存量」全部并进待分配点数。
     * 界面改版后分类行显示玩家弹药表，箱子自己的分类存量已不再使用；
     * 老存档里右键塞进去的子弹不能凭空消失，读档时迁移成 pending 让玩家重新分配。
     *
     * @return 迁移了多少点
     */
    public int absorbStored() {
        int total = 0;
        for (AmmoCategory c : AmmoCategory.values()) {
            int have = data.get(c);
            if (have <= 0) {
                continue;
            }
            int room = data.genericCapacity() - pending;
            if (room <= 0) {
                break;
            }
            int take = Math.min(have, room);
            if (take <= 0) {
                continue;
            }
            int moved = data.withdraw(c, take);
            pending += moved;
            total += moved;
        }
        return total;
    }


    // ==================== 存档 ====================

    public void save(CompoundTag tag) {
        CompoundTag root = new CompoundTag();
        if (!fuel.isEmpty()) {
            CompoundTag f = new CompoundTag();
            fuel.save(f);
            root.put("Fuel", f);
        }
        if (!ammoInput.isEmpty()) {
            CompoundTag a = new CompoundTag();
            ammoInput.save(a);
            root.put("Ammo", a);
        }
        root.putInt("Pending", pending);
        root.putInt("Progress", cookProgress);
        tag.put("Cooking", root);
    }

    /**
     * 从 NBT 恢复。
     *
     * ⚠️ 2026-11-14 修两个真 bug：
     *  ① `pending` 读进来就被 `absorbStored()` 里的 `genericCapacity() - pending` 卡 —
     *     但更严重的是**不夹上限**，万一配置调小了容量，pending 会超过总容量，
     *     界面显示 "1500/1000" 且所有按钮都发不出去（room 先算出来是负的）。
     *  ② 这一段原来是「把老 generic 并进 pending、再把老分类存量并进 pending」，
     *     两处都直接 `pending +=`，累加后可能超过上限，同上。
     */
    public void load(CompoundTag tag) {
        if (!tag.contains("Cooking")) {
            // 老存档兼容：把旧的 generic 池并进来
            pending = data.takeGeneric();
        } else {
            CompoundTag root = tag.getCompound("Cooking");
            if (root.contains("Fuel")) {
                fuel = ItemStack.of(root.getCompound("Fuel"));
            }
            if (root.contains("Ammo")) {
                ammoInput = ItemStack.of(root.getCompound("Ammo"));
            }
            pending = Math.max(0, root.getInt("Pending"));
            cookProgress = Math.max(0, root.getInt("Progress"));
            // 以前右键弹料灌进 generic 的点数，读档时并进待分配，别让玩家白丢
            int legacy = data.takeGeneric();
            if (legacy > 0) {
                pending += legacy;
            }
            // 旧版右键存进箱子的分类存量，也一并不进待分配
            absorbStored();
        }
        // 统一夹一次上限：配置可能比存档时小
        int cap = data.genericCapacity();
        if (pending > cap) {
            // ⚠️ 2026-11-16 修（C9 静默丢弃）：配置改小后超出的 pending 会被夹掉，
            //   以前一声不响地吞掉玩家东西。现在至少留一条 warn，方便排查"点数不见了"。
            TaczAmmoData.LOGGER.warn("[弹药箱] 待分配点数 {} 超过当前容量 {}（配置改小？），超出的 {} 点被截断",
                    pending, cap, pending - cap);
            pending = cap;
        }
    }

    }