package com.dsh.taczammodata;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块/升级模板系统 —— 「物品栏小框 + 装升级模板」的**数据层**。
 *
 * 主人原话：
 *   「你需要在物品栏单独做出来，一个小框可以点」
 *   「点开它之后可以装升级模板」
 *   「比如说天书科技电能升级兼容一、兼容二、兼容三，来提升它对应武器的可用性」
 *
 * ===== 设计思路（人家按主人"别急着定论"的嘱咐，先做数据层） =====
 *
 * 一个「升级模板」= 一条规则，描述：
 *   ① 它作用于**哪些枪**（靠 tag 匹配，不硬编码模组名 —— 别的模组加个 tag 就能接）
 *   ② 它提升**哪一类的容量上限**（或所有类）
 *   ③ 提升多少（固定值 or 百分比）
 *
 * 玩家身上有若干个**模块槽**，每个槽放一个模板。
 * 最终容量 = 基础容量(等级决定) + 所有模块的加成。
 *
 * ===== 为什么用 tag 而不是模组名 =====
 * 主人说过「整合包作者可能会用上我们的模组」。
 * 硬编码 "tianshu" / "tebl" 这些名字的话，每加一个模组都要改我们的代码。
 * 用 tag（物品标签）的话，别的模组/整合包自己打个 tag 就接上了。
 *
 * ===== 当前状态 =====
 * 这只是**数据层骨架**，还没接 GUI、还没做模板物品。
 * 等主人回来定了具体形态再往上搭。
 */
public final class ModuleSystem {

    /** 玩家持久数据里的 key */
    private static final String MODULES_KEY = "Modules";
    /** 槽位数量（可配置，这里是默认） */
    public static final int SLOT_COUNT = 6;

    private ModuleSystem() {
    }

    // ==================== 模板定义 ====================

    /**
     * 一个升级模板的规则。
     *
     * @param id          模板 id（如 "tacz_ammodata:iron_ammo_pouch"）
     * @param targetTag   作用于哪些枪（物品 tag，如 "tacz:rifle"）；null = 全部枪
     * @param category    提升哪一类容量；null = 全部 7 类
     * @param flatBonus   固定加成（发）
     * @param percentBonus 百分比加成（相对基础容量，0.1 = +10%）
     * @param tierBonus   直接提升弹药袋等级（0 = 不提）
     */
    public record Template(
            ResourceLocation id,
            ResourceLocation targetTag,
            AmmoCategory category,
            int flatBonus,
            float percentBonus,
            int tierBonus
    ) {
        public static Template flat(ResourceLocation id, AmmoCategory cat, int flat) {
            return new Template(id, null, cat, flat, 0f, 0);
        }

        public static Template percent(ResourceLocation id, AmmoCategory cat, float pct) {
            return new Template(id, null, cat, 0, pct, 0);
        }

        /** 序列化到 NBT */
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Id", id.toString());
            if (targetTag != null) {
                t.putString("Tag", targetTag.toString());
            }
            if (category != null) {
                t.putString("Category", category.key());
            }
            t.putInt("Flat", flatBonus);
            t.putFloat("Percent", percentBonus);
            t.putInt("Tier", tierBonus);
            return t;
        }

        /** 从 NBT 读 */
        public static Template load(CompoundTag t) {
            ResourceLocation id = ResourceLocation.tryParse(t.getString("Id"));
            if (id == null) {
                return null;
            }
            ResourceLocation tag = t.contains("Tag") ? ResourceLocation.tryParse(t.getString("Tag")) : null;
            AmmoCategory cat = t.contains("Category") ? AmmoCategory.byKey(t.getString("Category")) : null;
            return new Template(id, tag, cat,
                    t.getInt("Flat"), t.getFloat("Percent"), t.getInt("Tier"));
        }
    }

    // ==================== 玩家槽位存取 ====================

    private static CompoundTag root(Player player, boolean create) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(MODULES_KEY)) {
            if (!create) {
                return new CompoundTag();
            }
            persistent.put(MODULES_KEY, new CompoundTag());
        }
        return persistent.getCompound(MODULES_KEY);
    }

    /** 读某个槽位的模板（空槽返回 null） */
    public static Template getSlot(Player player, int slot) {
        CompoundTag r = root(player, false);
        String key = "Slot" + slot;
        if (!r.contains(key)) {
            return null;
        }
        return Template.load(r.getCompound(key));
    }

    /** 写入某个槽位 */
    public static void setSlot(Player player, int slot, Template template) {
        CompoundTag r = root(player, true);
        String key = "Slot" + slot;
        if (template == null) {
            r.remove(key);
        } else {
            r.put(key, template.save());
        }
        // 模块变了，容量缓存必须作废（不然装/拆模板后容量不生效）
        forgetCapacityCache(player.getUUID());
    }

    /** 全部已装配的模板 */
    public static List<Template> installed(Player player) {
        List<Template> out = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            Template t = getSlot(player, i);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    // ==================== 容量加成计算 ====================

    /**
     * 算出某类弹药的**最终容量**（基础 + 所有模块加成）。
     *
     * 这是整个模块系统的出口 —— 别的地方要容量都该走这里。
     *
     * ⚠️ 2026-11-16 性能（D1）：这个方法每次调用要读 6 个槽的 NBT（installed() 里
     *   getSlot → root → getPersistentData 各一次），而它被 tick、GUI 每帧、HUD 反复调。
     *   模块槽是个几乎不变的东西，所以加一层「玩家 + 类别」的短缓存：
     *   模块没变就直接返回上次算好的值。
     *
     *   缓存失效点：玩家登出（forget）、模块槽变化（setSlot 主动清）、配置重载（bake 后 clearAll）。
     *   没做定时过期 —— 因为上面三个点已经覆盖了所有会变的情况。
     */
    private static final Map<String, Integer> CAP_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 缓存 key：玩家 UUID + 类别 + 等级（等级变了容量必变，直接换 key 最省事） */
    private static String capKey(Player player, AmmoCategory cat, int tier) {
        return player.getUUID() + "|" + cat.key() + "|" + tier;
    }

    public static void clearCapacityCache() {
        CAP_CACHE.clear();
    }

    public static void forgetCapacityCache(java.util.UUID id) {
        String prefix = id.toString() + "|";
        CAP_CACHE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public static int effectiveCapacity(Player player, AmmoCategory cat) {
        int tier = AmmoDataStore.getTier(player, cat);
        String key = capKey(player, cat, tier);
        Integer cached = CAP_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int computed = computeCapacity(player, cat, tier);
        CAP_CACHE.put(key, computed);
        return computed;
    }

    private static int computeCapacity(Player player, AmmoCategory cat, int tier) {
        // 基准必须是「不含模块加成」的裸容量，否则会无限递归
        int base = Config.capacityFor(cat, tier);
        int flat = 0;
        float pct = 0f;
        int tierBonus = 0;

        for (Template t : installed(player)) {
            // 这个模板适不适用当前类别？
            if (t.category() != null && t.category() != cat) {
                continue;
            }
            flat += t.flatBonus();
            pct += t.percentBonus();
            tierBonus = Math.max(tierBonus, t.tierBonus());
        }

        int result = base;

        // 等级加成：直接用更高一级的容量
        if (tierBonus > 0) {
            int newTier = Math.min(tier + tierBonus, 5);
            result = Math.max(result, Config.capacityFor(cat, newTier));
        }

        // 固定 + 百分比
        result += flat;
        if (pct > 0f) {
            result += Math.round(result * pct);
        }

        return Math.max(1, result);
    }

    /**
     * 某个模板是否作用于某把枪。
     *
     * 靠物品 tag 匹配（不硬编码模组名）。targetTag 为 null 表示"全部枪"。
     */
    public static boolean appliesToGun(Template t, net.minecraft.world.item.ItemStack gunItem) {
        if (t.targetTag() == null) {
            return true;
        }
        return gunItem.is(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ITEM, t.targetTag()));
    }

    /** 调试用：dump 玩家装了什么 */
    public static Map<String, String> dump(Player player) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            Template t = getSlot(player, i);
            out.put("slot" + i, t == null ? "(空)"
                    : t.id() + " [" + (t.category() == null ? "全部" : t.category().key())
                    + " +" + t.flatBonus() + " +" + Math.round(t.percentBonus() * 100) + "%]");
        }
        return out;
    }
}