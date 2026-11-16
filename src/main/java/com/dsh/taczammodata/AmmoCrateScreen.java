package com.dsh.taczammodata;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 弹药箱界面绘制（2026-11-14 第三轮）。
 *
 * 布局（220x242）：
 *   标题栏：档位名 / 待分配点数 / [全部补满]
 *   左上两槽：复合弹料（制作） + TaCZ 子弹（按权重折算）
 *   容量条：已占 / 总容量
 *   分类列表：名称 / 存量 / [+50] / [满]
 *   提示行：成功失败都在这里说，不弹聊天栏
 *   底部：玩家背包
 */
public class AmmoCrateScreen extends AbstractContainerScreen<AmmoCrateMenu> {

    private static final int W = 220;
    private static final int H = 242;

    // 配色（军械库风）
    private static final int C_BG = 0xFF1E2226;
    private static final int C_BORDER = 0xFF4A5258;
    private static final int C_TITLE = 0xFF3A4A3A;
    private static final int C_TEXT = 0xFFD8DEE4;
    private static final int C_DIM = 0xFF8A9298;
    private static final int C_BAR_BG = 0xFF1A1E22;

    private static final int C_BTN = 0xFF454D54;
    private static final int C_BTN_HOVER = 0xFF5A646C;
    private static final int C_OK = 0xFF8FE08F;
    private static final int C_WARN = 0xFFFF8A80;

    private int left;
    private int top;

    // 分类行几何（相对面板）
    private static final int ROW_H = 12;
    private static final int ROW_TOP = 58;
    private static final int BTN_ADD_X = 112;
    private static final int BTN_ADD_W = 22;
    private static final int BTN_FULL_X = 138;
    private static final int BTN_FULL_W = 18;

    // 标题栏按钮
    private static final int BTN_ALL_X = 140;
    private static final int BTN_ALL_Y = 4;
    private static final int BTN_ALL_W = 70;
    private static final int BTN_ALL_H = 12;

    // 已装升级的小图标行
    private static final int ICON_Y = 144;
    private static final int ICON_STEP = 18;

    // 提示行
    private static final int NOTE_Y = 161;

    private String note = "";
    private int noteColor = C_DIM;
    private long noteUntil = 0L;

    public AmmoCrateScreen(AmmoCrateMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = W;
        this.imageHeight = H;
        this.inventoryLabelY = H - 80;
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - this.imageWidth) / 2;
        this.top = (this.height - this.imageHeight) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.left = (this.width - this.imageWidth) / 2;
        this.top = (this.height - this.imageHeight) / 2;
        this.renderBackground(g);

        // 主面板
        g.fill(left, top, left + imageWidth, top + imageHeight, C_BG);
        border(g, left, top, imageWidth, imageHeight, C_BORDER);

        // 标题栏：标题 / 待分配 / 全部补满
        g.fill(left + 2, top + 2, left + imageWidth - 2, top + 18, C_TITLE);
        String title = "§e" + menu.tier().displayName + "弹药箱";
        g.drawString(font, title, left + 8, top + 6, C_TEXT, false);
        String pendingText = "§a" + menu.pending() + "§7/" + menu.totalCapacity();
        int px = left + 8 + font.width(title) + 6;
        g.drawString(font, pendingText, px, top + 6, C_TEXT, false);
        double mul = menu.upgradeMultiplier();
        if (mul > 1.0) {
            String mulText = mul == Math.floor(mul) ? String.valueOf((int) mul) : String.valueOf(mul);
            g.drawString(font, "§6×" + mulText,
                    px + font.width(pendingText) + 6, top + 6, C_TEXT, false);
        }
        drawButton(g, "全部补满", left + BTN_ALL_X, top + BTN_ALL_Y,
                BTN_ALL_W, BTN_ALL_H, mouseX, mouseY);

        // 槽位说明
        g.drawString(font, "§7左槽弹料制作 / 右槽子弹折算",
                left + 24, top + 46, C_DIM, false);
        if (menu.hasFuel() && menu.cookProgressPercent() > 0) {
            String cook = "§e制作 " + menu.cookProgressPercent() + "%";
            g.drawString(font, cook, left + 224 - 8 - font.width(cook), top + 46, C_DIM, false);
        }

        // 分类列表（2026-11-14 主人要求：不要容量条，直接上移）
        AmmoCategory[] all = AmmoCategory.values();
        int idx = 0;
        for (AmmoCategory c : all) {
            if (!menu.allows(c)) {
                continue;
            }
            int y = top + ROW_TOP + idx * ROW_H;
            drawCategoryRow(g, c, y, mouseX, mouseY);
            idx++;
        }

        // 已装升级的小图标（2026-11-14 主人要的）
        drawUpgradeIcons(g, mouseX, mouseY);

        // 操作提示（GUI 内，不弹聊天栏）
        String line;
        int color;
        if (Util.getMillis() < noteUntil) {
            line = note;
            color = noteColor;
        } else if (!menu.hasFuel() && menu.pending() <= 0) {
            line = "§7把复合弹料放进左槽，或把 TaCZ 子弹放进右槽";
            color = C_DIM;
        } else {
            line = "§7点 [+" + Config.crateButtonStep + "] / [满] 把待分配点数发到你的弹药表";
            color = C_DIM;
        }
        g.drawString(font, line, left + 8, top + NOTE_Y, color, false);

        // 玩家背包 + 物品（槽位背景在 renderBg 里画）
        super.render(g, mouseX, mouseY, partial);
        this.renderTooltip(g, mouseX, mouseY);
    }

    /** 已装升级的小图标（只画装了的，鼠标悬停看详情） */
    private void drawUpgradeIcons(GuiGraphics g, int mx, int my) {
        int x = left + 8;
        for (CrateUpgradeType t : CrateUpgradeType.values()) {
            int lv = menu.upgradeLevel(t);
            if (lv <= 0) {
                continue;
            }
            ItemStack icon = iconFor(t, lv);
            g.renderItem(icon, x, top + ICON_Y);
            if (mx >= x && mx < x + 16 && my >= top + ICON_Y && my < top + ICON_Y + 16) {
                g.renderTooltip(font, Component.literal(
                        "§f" + t.displayName + " Lv." + lv + "§7 — " + t.effectText), mx, my);
            }
            x += ICON_STEP;
        }
    }

    private ItemStack iconFor(CrateUpgradeType t, int lv) {
        return switch (t) {
            case CAPACITY -> new ItemStack(switch (lv) {
                case 1 -> TaczAmmoData.CRATE_UPGRADE_IRON.get();
                case 2 -> TaczAmmoData.CRATE_UPGRADE_GOLD.get();
                case 3 -> TaczAmmoData.CRATE_UPGRADE_DIAMOND.get();
                default -> TaczAmmoData.CRATE_UPGRADE_NETHERITE.get();
            });
            case EFFICIENCY -> new ItemStack(TaczAmmoData.CRATE_UPGRADE_EFFICIENCY.get());
            case LUCKY -> new ItemStack(TaczAmmoData.CRATE_UPGRADE_LUCKY.get());
            case SPEED -> new ItemStack(TaczAmmoData.CRATE_UPGRADE_SPEED.get());

        };
    }

    private void drawCategoryRow(GuiGraphics g, AmmoCategory c, int y, int mx, int my) {
        int x = left + 8;
        int stored = menu.playerAmount(c);
        int cap = menu.playerCapacity(c);

        g.drawString(font, "§f" + c.displayName, x, y + 2, C_TEXT, false);
        g.drawString(font, "§7" + stored + "/" + cap, x + 56, y + 2, C_DIM, false);

        // 2026-11-14：按钮字面跟着配置走，别再硬写 "+50"
        drawButton(g, "+" + Config.crateButtonStep, x + BTN_ADD_X, y, BTN_ADD_W, ROW_H, mx, my);
        drawButton(g, "满", x + BTN_FULL_X, y, BTN_FULL_W, ROW_H, mx, my);
    }

    private void drawButton(GuiGraphics g, String label, int x, int y, int w, int h,
                            int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? C_BTN_HOVER : C_BTN);
        border(g, x, y, w, h, C_BORDER);
        int tw = font.width(label);
        g.drawString(font, label, x + (w - tw) / 2, y + (h - 8) / 2, C_TEXT, false);
    }

    private void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hit(mx, my, left + BTN_ALL_X, top + BTN_ALL_Y, BTN_ALL_W, BTN_ALL_H)) {
                if (menu.pending() <= 0) {
                    setNote("没有待分配点数：往左槽放弹料 / 右槽放子弹", C_WARN);
                } else {
                    setNote("已提交：全部补满", C_OK);
                    sendButton(AmmoCrateMenu.BTN_ALL);
                }
                return true;
            }

            int rowY = top + ROW_TOP;
            AmmoCategory[] all = AmmoCategory.values();
            int idx = 0;
            for (AmmoCategory c : all) {
                if (!menu.allows(c)) {
                    continue;
                }
                int y = rowY + idx * ROW_H;
                int x = left + 8;
                int room = Math.max(0, menu.playerCapacity(c) - menu.playerAmount(c));
                int pending = menu.pending();

                if (hit(mx, my, x + BTN_ADD_X, y, BTN_ADD_W, ROW_H)) {
                    if (pending <= 0) {
                        setNote("没有待分配点数：往左槽放弹料 / 右槽放子弹", C_WARN);
                    } else if (room <= 0) {
                        setNote(c.displayName + " 已经满了", C_WARN);
                    } else {
                        int will = Math.min(Math.min(Config.crateButtonStep, pending), room);
                        setNote(c.displayName + " +" + will + " 点", C_OK);
                        // 用类别序号当按钮 id，别再拿过滤后的行号
                        sendButton(c.ordinal());
                    }
                    return true;
                }
                if (hit(mx, my, x + BTN_FULL_X, y, BTN_FULL_W, ROW_H)) {
                    if (pending <= 0) {
                        setNote("没有待分配点数：往左槽放弹料 / 右槽放子弹", C_WARN);
                    } else if (room <= 0) {
                        setNote(c.displayName + " 已经满了", C_WARN);
                    } else {
                        int will = Math.min(pending, room);
                        setNote(c.displayName + " +" + will + " 点", C_OK);
                        sendButton(AmmoCrateMenu.BTN_FILL_OFFSET + c.ordinal());
                    }
                    return true;
                }
                idx++;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void setNote(String text, int color) {
        this.note = text;
        this.noteColor = color;
        this.noteUntil = Util.getMillis() + 3000L;
    }

    private boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(
                    this.menu.containerId, id);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int mx, int my) {
        for (Slot slot : this.menu.slots) {
            int sx = left + slot.x - 1;
            int sy = top + slot.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, C_BAR_BG);
            border(g, sx, sy, 18, 18, C_BORDER);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        // 标题和标签已经手绘，不要再画默认的
    }
}