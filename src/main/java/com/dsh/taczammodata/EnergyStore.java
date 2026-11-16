package com.dsh.taczammodata;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * 能量槽存储层（能量武器专用）。
 *
 *   TaczAmmoEnergy
 *     ├─ Tier   ：能量挂带等级（0~4，没这个 key = 还没装挂带）
 *     └─ Energy ：当前电量（RF，上限由等级决定）
 *
 * 和 {@link AmmoDataStore}（7 类实弹）完全分开：能量枪换弹扣这里，不扣弹药表。
 */
public final class EnergyStore {

    public static final String ROOT_KEY = "TaczAmmoEnergy";
    private static final String KEY_ENERGY = "Energy";
    private static final String KEY_TIER = "Tier";
    private static final String KEY_NANO = "NanoSamples";
    private static final String KEY_NANO_OK = "NanoUnlocked";
    private static final String KEY_HINTED = "NanoHinted";

    /** 解锁纳米通道要几个纳米弹药 —— 见 Config.nanoUnlockCount（2026-11-09 提成配置） */

    private EnergyStore() {
    }

    private static CompoundTag root(Player player, boolean create) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(ROOT_KEY)) {
            if (!create) {
                return new CompoundTag();
            }
            persistent.put(ROOT_KEY, new CompoundTag());
        }
        return persistent.getCompound(ROOT_KEY);
    }

    // ==================== 挂带 ====================

    /** 身上有没有装能量挂带 */
    public static boolean isUnlocked(Player player) {
        return root(player, false).contains(KEY_TIER);
    }

    /** 挂带等级 0~4（没装时返回 -1） */
    public static int getTier(Player player) {
        if (!isUnlocked(player)) {
            return -1;
        }
        return Math.max(0, Math.min(root(player, false).getInt(KEY_TIER), 4));
    }

    public static void setTier(Player player, int tier) {
        CompoundTag r = root(player, true);
        r.putInt(KEY_TIER, Math.max(0, Math.min(tier, 4)));
        if (get(player) > capacity(player)) {
            set(player, capacity(player));
        }
    }

    /** 把挂带整个拆掉（删掉等级键 = 回到「没装」） */
    public static void clear(Player player) {
        CompoundTag r = root(player, true);
        r.remove(KEY_TIER);
        r.remove(KEY_ENERGY);
    }

    /**
     * 死亡掉落模式专用：**只清电量，等级 / 纳米状态全保留**（2026-11-16 主人定）。
     *
     * 和弹药表一个规矩：等级是花材料换来的能力，不该跟物品一起掉；
     * 但「电量」是可消耗的存量，掉落模式下乡下清零，和弹药数量一起走。
     *
     * @param tag EnergyStore 根标签（通常是 src.getCompound(ROOT_KEY) 的副本）
     */
    public static void stripEnergy(CompoundTag tag) {
        if (tag != null) {
            tag.remove(KEY_ENERGY);
        }
    }

    /** 电量这个字段在 NBT 里叫什么（给外部读日志 / 判定用） */
    public static String energyKeyName() {
        return KEY_ENERGY;
    }

    /** 死亡规则用：清掉纳米解锁状态和已收集样本（按 death.keepNanoUnlock 控制） */
    public static void stripNano(CompoundTag tag) {
        if (tag != null) {
            tag.remove(KEY_NANO);
            tag.remove(KEY_NANO_OK);
            tag.remove(KEY_HINTED);
        }
    }

    /** 死亡规则用：清掉挂带等级（death.keepTier=false 时走这条，等级归零到「没装」） */
    public static void stripTier(CompoundTag tag) {
        if (tag != null) {
            tag.remove(KEY_TIER);
            tag.remove(KEY_ENERGY);
        }
    }

    /** 电量上限（RF） */
    public static int capacity(Player player) {
        int tier = getTier(player);
        return tier < 0 ? 0 : Config.energyCapacity(tier);
    }

    // ==================== 电量 ====================

    public static int get(Player player) {
        if (!isUnlocked(player)) {
            return 0;
        }
        return Math.max(0, root(player, false).getInt(KEY_ENERGY));
    }

    public static void set(Player player, int rf) {
        int max = capacity(player);
        root(player, true).putInt(KEY_ENERGY, Math.max(0, Math.min(rf, max)));
    }

    /** 加电，返回实际加进去多少 */
    public static int add(Player player, int rf) {
        if (rf <= 0) {
            return 0;
        }
        int before = get(player);
        set(player, before + rf);
        return get(player) - before;
    }

    /** 扣电，不够则一点不扣并返回 false */
    public static boolean consume(Player player, int rf) {
        if (rf <= 0) {
            return true;
        }
        int cur = get(player);
        if (cur < rf) {
            return false;
        }
        set(player, cur - rf);
        return true;
    }

    /** 还能装多少 */
    public static int space(Player player) {
        return Math.max(0, capacity(player) - get(player));
    }

    /** 百分比（0~100），给显示用 */
    public static int percent(Player player) {
        int cap = capacity(player);
        return cap <= 0 ? 0 : (int) Math.round(100.0 * get(player) / cap);
    }

    // ==================== 能量弹药 → 换弹耗电 ====================
    //
    // 表里的数字是主人 2026-11-08 按“电池造价”拍的：
    //   S1/S2/S3 动力电池 1000/2000/5000，S6 反应堆核心 100000（电不够就换不了）
    //   沃芬 T1/T2/T3 高能电池 5000/3500/1500
    //   纳米仓储 6000/1200/4000（走能量池，不占实弹表）

    private static final Map<String, Integer> FUEL_COST = new HashMap<>();

    private static final String COST_RESOURCE = "/data/tacz_ammodata/energy_costs.json";
    private static final String COST_NAME = "energy_costs.json";
    private static boolean costsLoaded = false;

    /** 配置目录里的能量弹药成本表 */
    public static java.nio.file.Path costFile() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("tacz_ammodata").resolve(COST_NAME);
    }

    private static void putDefaults(Map<String, Integer> m) {
        m.put("trisdyna:emx_b1", 100_000);
        m.put("trisdyna:emx_b2", 200_000);
        m.put("trisdyna:emx_b3", 500_000);
        m.put("trisdyna:emx_b6", 10_000_000);
        m.put("fmic:battery1", 500_000);
        m.put("fmic:battery2", 350_000);
        m.put("fmic:battery3", 150_000);
        m.put("fmic:nano_t1", 600_000);
        m.put("fmic:nano_t2", 120_000);
        m.put("fmic:nano_t3", 400_000);
    }

    private static synchronized void ensureCosts() {
        if (!costsLoaded) {
            reloadCosts();
        }
    }

    /**
     * 重读能量弹药成本表。
     *
     * **2026-11-09 提成 json**：以前这张表写死在代码里，
     * 新枪包加了电池弹就得改代码重编译。现在放
     * `config/tacz_ammodata/energy_costs.json`，加一条就认。
     * 表里没有的，回落到 `energy.reloadCost.default`。
     */
    public static synchronized void reloadCosts() {
        FUEL_COST.clear();
        putDefaults(FUEL_COST);
        java.nio.file.Path file = costFile();
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                String json;
                try (java.io.InputStream in = EnergyStore.class.getResourceAsStream(COST_RESOURCE)) {
                    json = in == null ? "{}" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                java.nio.file.Files.writeString(file, json, java.nio.charset.StandardCharsets.UTF_8);
            }
            try (java.io.Reader r = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
                com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(r);
                if (root.isJsonObject()) {
                    for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject().entrySet()) {
                        if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                            FUEL_COST.put(e.getKey(), Math.max(0, e.getValue().getAsInt()));
                        }
                    }
                }
            }
            TaczAmmoData.LOGGER.info("[弹药数据] 能量弹药成本表已载入：{} 条（{}）", FUEL_COST.size(), file);
        } catch (Exception e) {
            TaczAmmoData.LOGGER.error("[弹药数据] 能量成本表读取失败，用内置默认值：{}", file, e);
            FUEL_COST.clear();
            putDefaults(FUEL_COST);
        }
        costsLoaded = true;
    }

    /** 这种能量弹药换一次弹要多少 RF；没登记过就用配置里的默认值 */
    public static int fuelCost(ResourceLocation ammoId) {
        if (ammoId == null) {
            return Config.energyFuelCostDefault;
        }
        ensureCosts();
        return FUEL_COST.getOrDefault(ammoId.toString(), Config.energyFuelCostDefault);
    }

    // ==================== 能量弹药识别 ====================

    /** 这种弹药是不是能量类（电池/纳米/核心）—— 是的话不吃 7 类弹药表 */
    public static boolean isEnergyAmmo(ResourceLocation ammoId) {
        if (ammoId == null) {
            return false;
        }
        ensureCosts();
        return FUEL_COST.containsKey(ammoId.toString());
    }

    /** 是不是纳米弹药（需要先解锁纳米通道） */
    public static boolean isNanoAmmo(ResourceLocation ammoId) {
        return ammoId != null && ammoId.getPath().startsWith("nano");
    }

    // ==================== 纳米通道解锁 ====================

    public static int getNanoSamples(Player player) {
        return Math.max(0, root(player, false).getInt(KEY_NANO));
    }

    public static boolean isNanoUnlocked(Player player) {
        return root(player, false).getBoolean(KEY_NANO_OK);
    }

    /** 塞样本，满了自动解锁；返回是否「这次」解锁了 */
    public static boolean addNanoSample(Player player, int count) {
        CompoundTag r = root(player, true);
        int now = Math.min(Config.nanoUnlockCount, r.getInt(KEY_NANO) + count);
        r.putInt(KEY_NANO, now);
        if (now >= Config.nanoUnlockCount && !r.getBoolean(KEY_NANO_OK)) {
            r.putBoolean(KEY_NANO_OK, true);
            return true;
        }
        return false;
    }

    /** 首次拾取纳米弹药的提示，只弹一次 */
    public static boolean markNanoHinted(Player player) {
        CompoundTag r = root(player, true);
        if (r.getBoolean(KEY_HINTED)) {
            return false;
        }
        r.putBoolean(KEY_HINTED, true);
        return true;
    }
}
