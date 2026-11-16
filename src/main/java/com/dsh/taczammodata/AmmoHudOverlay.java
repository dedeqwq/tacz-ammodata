package com.dsh.taczammodata;

import com.tacz.guns.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 自制弹药 HUD —— **默认关闭，保留备用**。
 *
 * ===== 为什么默认关掉（主人的决定，人家赞成） =====
 * TaCZ 原生 HUD 显示的「备弹数」**本来就是本模组写进 DummyAmmo 的值**
 * （GunHudOverlay.java:189 `cacheInventoryAmmoCount = iGun.getDummyAmmoAmount(stack)`），
 * 所以功能上一点没少，还省得两个 HUD 打架、也不用为它适配所有枪包。
 *
 * 想启用：config 里把 hud.enabled 改成 true。
 *
 * ===== 为什么不关掉 TaCZ 那个 =====
 * TaCZ 有配置开关（RenderConfig.GUN_HUD_ENABLE / tacz-client.toml 的 GunHUDEnable），
 * 但靠改配置文件对小白不友好，靠 mixin 又会跟 TaCZ 版本绑死
 * （主人原话：「TaCZ 那个版本特别多，改了之后版本就会爆炸」）。
 * 所以本模组**不动 TaCZ 任何东西**。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AmmoHudOverlay implements IGuiOverlay {

    public static final AmmoHudOverlay INSTANCE = new AmmoHudOverlay();

    private AmmoHudOverlay() {
    }

    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent event) {
        // ⚠️ 2026-11-14 改：原来用 registerAbove("tacz:tac_gun_hud_overlay")，
        //    而 Forge 的 register() 里对「参照物没注册过」是直接 checkState 抛异常的 ——
        //    只要 TaCZ 的 ClientSetupEvent 比我们晚跑（mod 加载顺序一变就会），
        //    客户端启动阶段就崩。改成 registerAboveAll 不依赖任何别的模组的注册顺序。
        event.registerAboveAll("ammo_data_hud", INSTANCE);
    }

    @Override
    public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
                       float partialTick, int screenWidth, int screenHeight) {
        if (!Config.hudEnabled) {
            return;   // 默认就是这里返回（关闭状态）
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        if (Config.hideWhenGuiOpen && mc.screen != null) {
            return;
        }
        if (!AmmoNetwork.ClientAmmoCache.hasData()) {
            return;
        }

        // 当前手持枪属于哪一类
        AmmoCategory held = null;
        ItemStack main = player.getMainHandItem();
        if (IGun.getIGunOrNull(main) != null) {
            held = AmmoCategory.ofGun(main);
        }

        AmmoCategory[] cats = Config.showAllCategories
                ? AmmoCategory.values()
                : (held != null ? new AmmoCategory[]{held} : new AmmoCategory[0]);
        if (cats.length == 0) {
            return;
        }

        Font font = mc.font;
        String[] lines = new String[cats.length];
        int w = 0;
        for (int i = 0; i < cats.length; i++) {
            int amt = AmmoNetwork.ClientAmmoCache.amount(cats[i]);
            int cap = AmmoNetwork.ClientAmmoCache.capacity(cats[i]);
            lines[i] = cats[i].displayName + "  " + amt + " / " + cap;
            w = Math.max(w, font.width(lines[i]));
        }

        int lineH = font.lineHeight + 1;
        int h = cats.length * lineH + 4;
        int pad = Config.hudPadding;
        int barW = 40;

        int x;
        int y;
        switch (Config.hudPosition) {
            case TOP_LEFT -> {
                x = pad;
                y = pad;
            }
            case TOP_RIGHT -> {
                x = screenWidth - (w + barW + 10) - pad;
                y = pad;
            }
            case BOTTOM_LEFT -> {
                x = pad;
                y = screenHeight - h - pad;
            }
            default -> {
                x = screenWidth - (w + barW + 10) - pad;
                y = screenHeight - h - pad;
            }
        }

        int boxW = w + barW + 10;
        graphics.fill(x - 3, y - 3, x + boxW, y + h - 1, 0x80000000);

        for (int i = 0; i < cats.length; i++) {
            AmmoCategory c = cats[i];
            int ly = y + 2 + i * lineH;
            int amt = AmmoNetwork.ClientAmmoCache.amount(c);
            int cap = AmmoNetwork.ClientAmmoCache.capacity(c);

            boolean active = (c == held);
            int color = active ? Config.colorText : (Config.colorDim & 0xFFFFFF);

            graphics.drawString(font, lines[i], x, ly, color, true);

            if (Config.showBar && cap > 0) {
                int bx = x + w + 6;
                int by = ly + 3;
                graphics.fill(bx, by, bx + barW, by + 4, 0xFF303030);
                int filled = (int) (barW * Math.min(1.0f, amt / (float) cap));
                int barColor = active ? Config.colorData : (Config.colorDim & 0xFFFFFF);
                graphics.fill(bx, by, bx + Math.max(0, filled), by + 4, barColor);
            }
        }
    }
}