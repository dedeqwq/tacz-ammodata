package com.dsh.taczammodata;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 口径权重表 —— **所有「点」和「发」的换算都必须走这里**。
 *
 * ===== 为什么要有这个 =====
 *
 * 以前弹药表里 1 个数 = 1 发子弹，但「1 发 9mm」和「1 发 .50BMG」
 * 明显不是一个东西。加了权重以后：
 *
 *   弹药表存的是「点」（point），每种口径有自己的一份价：
 *     9mm = 1 点、.50AE = 2 点、50BMG / RPG 火箭 = 8 点
 *
 *   开火一次扣「这颗子弹的权重」点；
 *   换弹补 N 发 = 扣 N × 权重 点；
 *
 *   **TaCZ 原生 HUD 显示的备弹数 = 点数 ÷ 权重**（向下取整），
 *   所以玩家看到的还是「还能打几发」，只是贵的子弹占地方。
 *
 * ===== 表放哪 =====
 *
 *   config/tacz_ammodata/ammo_weights.json
 *
 * 第一次启动自动从 jar 里拷一份默认表出来（{@code data/tacz_ammodata/ammo_weights.json}）。
 * 默认表是按当前装的 5 个枪包（157 把枪 / 52 个口径）扫出来的，
 * 按「伤害 + 弹种」定的权重，不是拍脑袋。
 *
 * 改完不用重启：游戏里敲 {@code /ammodata weights reload}。
 * 表里没有的口径一律按 1 点算，并在日志里列出来（方便补表）。
 */
public final class AmmoWeights {

    /** 允许的权重范围：小于 1 会把点数越算越多；上限放宽到 2000 是为了「一发占 120 格」这种特权枪 */
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 2000;
    /** 没登记的口径按这个算 */
    public static final int FALLBACK = 1;

    private static final String DEFAULT_RESOURCE = "/data/tacz_ammodata/ammo_weights.json";
    private static final String CONFIG_SUBDIR = "tacz_ammodata";
    private static final String CONFIG_NAME = "ammo_weights.json";

    /** 按枪覆盖：枪 id -> 该枪每发占几格（不写就按口径走） */
    private static final String OVERRIDE_RESOURCE = "/data/tacz_ammodata/gun_overrides.json";
    private static final String OVERRIDE_NAME = "gun_overrides.json";
    private static final Map<String, Integer> GUN_OVERRIDES = new HashMap<>();

    /** "namespace:path" -> 权重 */
    private static final Map<String, Integer> WEIGHTS = new TreeMap<>();
    /** 已经警告过的口径，避免刷屏 */
    private static final Set<String> WARNED = new HashSet<>();

    private static boolean initialized = false;

    private AmmoWeights() {
    }

    // ==================== 文件位置 ====================

    public static Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(CONFIG_NAME);
    }

    public static Path overrideFile() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR).resolve(OVERRIDE_NAME);
    }

    // ==================== 载入 ====================

    public static synchronized void ensureLoaded() {
        if (!initialized) {
            reload();
        }
    }

    /**
     * 重新读表。文件不存在就先写一份默认的出来。
     *
     * 读失败不抛异常 —— 权重挂了不该让整个模组不能用，退回「全部 1 点」。
     */
    public static synchronized void reload() {
        WEIGHTS.clear();
        GUN_OVERRIDES.clear();
        WARNED.clear();
        Path file = configFile();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                writeDefault(file, DEFAULT_RESOURCE, "{}");
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                parse(reader, file.toString(), WEIGHTS);
            }
            TaczAmmoData.LOGGER.info("[弹药数据] 口径权重表已载入：{} 个口径（{}）", WEIGHTS.size(), file);
        } catch (Exception e) {
            TaczAmmoData.LOGGER.error("[弹药数据] 口径权重表读取失败，本次全部按 {} 点算：{}",
                    FALLBACK, file, e);
            WEIGHTS.clear();
        }

        // 按枪覆盖（可选，不给就空表）
        Path ov = overrideFile();
        try {
            if (!Files.exists(ov)) {
                Files.createDirectories(ov.getParent());
                writeDefault(ov, OVERRIDE_RESOURCE, "{}");
            }
            try (Reader reader = Files.newBufferedReader(ov, StandardCharsets.UTF_8)) {
                parse(reader, ov.toString(), GUN_OVERRIDES);
            }
            if (!GUN_OVERRIDES.isEmpty()) {
                TaczAmmoData.LOGGER.info("[弹药数据] 按枪权重覆盖已载入：{} 把（{}）", GUN_OVERRIDES.size(), ov);
            }
        } catch (Exception e) {
            TaczAmmoData.LOGGER.error("[弹药数据] 按枪覆盖表读取失败，本次全部按口径走：{}", ov, e);
            GUN_OVERRIDES.clear();
        }
        initialized = true;
    }

    private static void parse(Reader reader, String source, Map<String, Integer> target) {
        JsonElement root = JsonParser.parseReader(reader);
        if (!root.isJsonObject()) {
            TaczAmmoData.LOGGER.error("[弹药数据] 权重表根节点不是对象：{}", source);
            return;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                TaczAmmoData.LOGGER.warn("[弹药数据] 权重表条目 {} 不是数字，已跳过", e.getKey());
                continue;
            }
            int w = v.getAsInt();
            int clamped = Math.max(MIN_WEIGHT, Math.min(w, MAX_WEIGHT));
            if (clamped != w) {
                TaczAmmoData.LOGGER.warn("[弹药数据] 权重表 {} = {} 超出范围，按 {} 算", e.getKey(), w, clamped);
            }
            target.put(e.getKey(), clamped);
        }
    }

    private static void writeDefault(Path file, String resource, String fallbackJson) throws Exception {
        String json;
        try (InputStream in = AmmoWeights.class.getResourceAsStream(resource)) {
            json = in == null ? fallbackJson : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 顺手校验一遍，别把坏文件写出去
        try (Reader r = new StringReader(json)) {
            JsonParser.parseReader(r);
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
        TaczAmmoData.LOGGER.info("[弹药数据] 已生成默认文件：{}", file);
    }

    // ==================== 查询 ====================

    /** 这种口径一发值多少点（没登记 = 配置里的 tuning.weightsFallback） */
    public static int of(@Nullable ResourceLocation ammoId) {
        if (ammoId == null) {
            return Config.weightsFallback;
        }
        ensureLoaded();
        String key = ammoId.toString();
        Integer w = WEIGHTS.get(key);
        if (w != null) {
            return w;
        }
        if (WARNED.add(key)) {
            TaczAmmoData.LOGGER.info("[弹药数据] 口径 {} 没在权重表里，按 {} 点算（要改就编辑 {}）",
                    key, Config.weightsFallback, configFile().getFileName());
        }
        return Config.weightsFallback;
    }

    /**
     * 这把枪用的弹药一发值多少点。
     *
     * **优先查「按枪覆盖」表**（gun_overrides.json）：
     * 有些枪天生特殊（比如一发一颗下界合金的「竞争者 ich」），
     * 不该跟同口径的枪一个待遇，就给它单独开个口子。
     */
    public static int ofGun(net.minecraft.world.item.ItemStack gun) {
        var api = com.tacz.guns.api.item.IGun.getIGunOrNull(gun);
        if (api != null) {
            ResourceLocation gid = api.getGunId(gun);
            if (gid != null) {
                ensureLoaded();
                Integer override = GUN_OVERRIDES.get(gid.toString());
                if (override != null) {
                    return override;
                }
            }
        }
        return of(GunHelper.getAmmoId(gun));
    }

    /** 这把枪有没有单独的覆盖（调试用） */
    public static boolean hasGunOverride(net.minecraft.world.item.ItemStack gun) {
        var api = com.tacz.guns.api.item.IGun.getIGunOrNull(gun);
        if (api == null) {
            return false;
        }
        ResourceLocation gid = api.getGunId(gun);
        if (gid == null) {
            return false;
        }
        ensureLoaded();
        return GUN_OVERRIDES.containsKey(gid.toString());
    }

    /** 点数 → 实际发数（TaCZ HUD 要的就是这个） */
    public static int rounds(int points, int weight) {
        if (weight <= 0) {
            return Math.max(0, points);
        }
        return Math.max(0, points / weight);
    }

    /** 发数 → 点数（装填、补给包给弹的时候用） */
    public static int points(int rounds, int weight) {
        return Math.max(0, rounds) * Math.max(MIN_WEIGHT, weight);
    }

    /** 调试：整张表 */
    public static String dump() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        sb.append("§e口径权重表 §7（").append(configFile()).append("）\n");
        for (Map.Entry<String, Integer> e : WEIGHTS.entrySet()) {
            sb.append("§f").append(e.getKey()).append(" §7= §a").append(e.getValue()).append(" 点/发\n");
        }
        return sb.toString();
    }

    /** 表里有多少条（命令里报数用） */
    public static int size() {
        ensureLoaded();
        return WEIGHTS.size();
    }
}
