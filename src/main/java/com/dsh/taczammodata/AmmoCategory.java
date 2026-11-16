package com.dsh.taczammodata;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 弹药类别（对应主人要的「容量表」）。
 *
 * TaCZ 自己在 index/guns/*.json 里给每把枪标了 type，正好就是我们要的分类：
 *   pistol / rifle / smg / sniper / mg / shotgun / rpg
 *
 * 每一类有**独立的容量表**，升级链也各自独立
 * （主人原话：「手枪基础备弹只有 30，升级成铁的会有 60，所有的那些不同的枪是不一样的」）。
 */
public enum AmmoCategory {

    PISTOL("pistol", "手枪弹"),
    RIFLE("rifle", "步枪弹"),
    SMG("smg", "冲锋枪弹"),
    SNIPER("sniper", "狙击弹"),
    MG("mg", "机枪弹"),
    SHOTGUN("shotgun", "霰弹"),
    RPG("rpg", "特殊武器弹");

    /** TaCZ gun index 里的 type 值 */
    public final String gunType;
    /** 中文显示名 */
    public final String displayName;

    AmmoCategory(String gunType, String displayName) {
        this.gunType = gunType;
        this.displayName = displayName;
    }

    /** NBT / 配置里用的 key（小写） */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Nullable
    public static AmmoCategory byKey(String key) {
        if (key == null) {
            return null;
        }
        for (AmmoCategory c : values()) {
            if (c.key().equalsIgnoreCase(key) || c.gunType.equalsIgnoreCase(key)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 从枪械物品推出它属于哪一类弹药。
     *
     * @return 认不出来返回 null（比如别的模组塞进来的枪）
     */
    @Nullable
    public static AmmoCategory ofGun(ItemStack gunItem) {
        var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(gunItem);
        if (gun == null) {
            return null;
        }
        ResourceLocation gunId = gun.getGunId(gunItem);
        if (gunId == null) {
            return null;
        }
        Optional<CommonGunIndex> idx = TimelessAPI.getCommonGunIndex(gunId);
        if (idx.isEmpty()) {
            return null;
        }
        String type = idx.get().getType();
        if (type == null) {
            return null;
        }
        for (AmmoCategory c : values()) {
            if (c.gunType.equalsIgnoreCase(type)) {
                return c;
            }
        }
        // 认不出的 type（别的枪包自定义分类）→ 归到 RIFLE，并记日志方便后续适配
        return RIFLE;
    }

    /** 全部类别 -> 便于遍历 */
    public static AmmoCategory[] all() {
        return values();
    }

    /** 遍历所有类别并回调（配置生成用） */
    public interface Visitor {
        void accept(AmmoCategory category, int base, int iron, int gold, int diamond, int netherite);
    }

    public static void forEachCategory(Visitor v) {
        for (AmmoCategory c : values()) {
            v.accept(c,
                    Config.baseCapacity(c),
                    Config.ironCapacity(c),
                    Config.goldCapacity(c),
                    Config.diamondCapacity(c),
                    Config.netheriteCapacity(c));
        }
    }

    /** 调试用：列出所有类别 */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (AmmoCategory c : values()) {
            sb.append(c.key()).append('(').append(c.displayName).append(')');
            sb.append(" base=").append(Config.baseCapacity(c));
            sb.append(" iron=").append(Config.ironCapacity(c));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 供遍历 Map 用（避免外部 import Map） */
    public static Iterable<Map.Entry<ResourceLocation, CommonGunIndex>> allGunIndex() {
        return TimelessAPI.getAllCommonGunIndex();
    }
}