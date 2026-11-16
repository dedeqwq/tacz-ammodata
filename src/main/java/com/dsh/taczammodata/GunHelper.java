package com.dsh.taczammodata;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.util.AttachmentDataUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 枪械/弹药相关的小工具。
 */
public final class GunHelper {

    private GunHelper() {
    }

    /** 取出枪对应的 GunData（拿口径、容量用） */
    @Nullable
    public static GunData getGunData(ItemStack gunItem) {
        IGun gun = IGun.getIGunOrNull(gunItem);
        if (gun == null) {
            return null;
        }
        ResourceLocation gunId = gun.getGunId(gunItem);
        if (gunId == null) {
            return null;
        }
        Optional<CommonGunIndex> index = TimelessAPI.getCommonGunIndex(gunId);
        return index.map(CommonGunIndex::getGunData).orElse(null);
    }

    /** 枪使用的口径（ammoId） */
    @Nullable
    public static ResourceLocation getAmmoId(ItemStack gunItem) {
        GunData data = getGunData(gunItem);
        return data == null ? null : data.getAmmoId();
    }

    /**
     * 这把枪一次装填需要多少发（考虑扩容弹匣配件）。
     * 这就是「换弹要扣多少弹药数据」。
     */
    public static int getMagazineSize(ItemStack gunItem) {
        GunData data = getGunData(gunItem);
        if (data == null) {
            return 0;
        }
        try {
            return AttachmentDataUtils.getAmmoCountWithAttachment(gunItem, data);
        } catch (Throwable t) {
            // 万一 API 签名有差异，退回基础容量，别让换弹直接崩掉
            return data.getAmmoAmount();
        }
    }

    /** 当前枪里还有多少发 */
    public static int getCurrentAmmo(ItemStack gunItem) {
        IGun gun = IGun.getIGunOrNull(gunItem);
        return gun == null ? 0 : gun.getCurrentAmmoCount(gunItem);
    }

    /** 直接设置枪内弹药数 */
    public static void setCurrentAmmo(ItemStack gunItem, int count) {
        IGun gun = IGun.getIGunOrNull(gunItem);
        if (gun != null) {
            gun.setCurrentAmmoCount(gunItem, Math.max(0, count));
        }
    }

    /** 枪是否装了扩容弹匣（HUD 展示用） */
    public static boolean hasExtendedMag(ItemStack gunItem) {
        IGun gun = IGun.getIGunOrNull(gunItem);
        if (gun == null) {
            return false;
        }
        ResourceLocation id = gun.getAttachmentId(gunItem, AttachmentType.EXTENDED_MAG);
        return id != null && !id.equals(com.tacz.guns.api.DefaultAssets.EMPTY_ATTACHMENT_ID);
    }

    /** 是不是玩家本人（服务端判定，避免动到别的实体） */
    public static boolean isPlayer(LivingEntity entity) {
        return entity instanceof Player;
    }
}