package com.dsh.taczammodata;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.item.IGun;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 弹药表记账 + 模式切换。
 *
 * ===== 两种「供弹模式」=====
 *
 *  A. TABLE 弹药表模式 —— 枪上写 DummyAmmo，TaCZ 只看表
 *  B. VANILLA 背包模式 —— 删掉 DummyAmmo，TaCZ 自己吃背包子弹
 *
 * ===== 切换规则（主人 2026-11-07 定，**很重要**）=====
 *
 *  表有弹            → TABLE
 *  表是 0 且背包有弹 → VANILLA（自动无缝切）
 *  表是 0 且背包没弹 → TABLE（就停在 0，不切）
 *
 * ===== 为什么要有「模式锁」（v3.1 修频闪）=====
 *
 * v3 是「每 tick 判一次表是 0 还是非 0，就删/写 NBT」。
 * 表在 0 附近来回抖 → TaCZ 一会儿看到 dummy 一会儿看不到
 * → 客户端服务端不同步 → **蓝白频闪 + 假换弹**（主人实测）。
 *
 * v3.1 改成：**模式只在该切的时候切**。
 *   - 记着当前是哪个模式（MODE）
 *   - 只有「表从 0 变非 0」或「表从非 0 变 0 且背包有弹」才动 NBT
 *   - 其余时间一个字节都不碰
 *
 * 这样 NBT 是稳定的，TaCZ 不会看到跳变，频闪和假换弹就没了。
 *
 * ===== 开火记账（加特林）=====
 *
 * 加特林 rpm=1200，tick 跟不上，所以必须用 GunFireEvent（每次击发都触发）。
 * 但开火时**只扣弹药表，不写 dummy** —— dummy 交给 TaCZ 自己扣，
 * 我们只在它快见底时补一次（影子记账，治「二连发→顿」）。
 */
@Mod.EventBusSubscriber(modid = TaczAmmoData.MODID)
public final class ReloadHandler {

    /** 当前每个玩家处于哪个供弹模式 */
    private enum Mode { TABLE, VANILLA }

    /**
     * 每个玩家每把枪（按槽位）处于哪个供弹模式。
     *
     * ⚠️ 2026-11-16 修（C11）：原先是玩家级单值 `Map<UUID, Mode>`，
     * 双持两把枪时——一把表空进 VANILLA、另一把退 TABLE——会互相覆盖。
     * 表现是切枪后要等一拍才校正（影子降账把 dummy 拉回去），不会崩，但账本会抖。
     * 改成和 LAST_MAG / LAST_GUN 一样的二维结构，各槽位各记各的。
     */
    private static final Map<UUID, Map<Integer, Mode>> MODE = new HashMap<>();

    private static Mode modeOf(UUID id, int slot) {
        Map<Integer, Mode> m = MODE.get(id);
        return m == null ? Mode.TABLE : m.getOrDefault(slot, Mode.TABLE);
    }

    private static void putMode(UUID id, int slot, Mode mode) {
        MODE.computeIfAbsent(id, k -> new HashMap<>()).put(slot, mode);
    }

    /** 清掉某人全部槽位的模式（登出 / 表被补满时整体作废） */
    private static void clearModes(UUID id) {
        MODE.remove(id);
    }

    /** 该玩家有没有任意槽位处于「背包模式」（开火事件拿不到槽位时用） */
    private static boolean anySlotVanilla(UUID id) {
        Map<Integer, Mode> m = MODE.get(id);
        if (m == null) {
            return false;
        }
        for (Mode mode : m.values()) {
            if (mode == Mode.VANILLA) {
                return true;
            }
        }
        return false;
    }
    /** 上次看到的「弹夹」数量，用来识别换弹；按「玩家 + 槽位」记 */
    private static final Map<UUID, Map<Integer, Integer>> LAST_MAG = new HashMap<>();
    /**
     * 上次手持的是哪把枪。
     *
     * ⚠️ 必须有这个：LAST_MAG 是「上次看到的弹夹数」，
     * 但它不知道那是哪把枪的弹夹。切枪时会误判：
     *   步枪弹夹 30 → 切手枪（15）→ 切回步枪（30）
     *   → 30 > 15，被当成「换弹补了 15 发」→ 白扣 15 发弹药表。
     * 主人实测「切一遍枪就少一堆子弹」就是这么来的。
     *
     * 有了这个，切枪就重置 LAST_MAG，不判换弹。
     */
    private static final Map<UUID, Map<Integer, net.minecraft.resources.ResourceLocation>> LAST_GUN = new HashMap<>();

    /**
     * 上次看到的「枪上 DummyAmmo 还剩几发」，按「玩家 + 槽位」记。
     *
     * ⚠️ 2026-11-16（U1 根治）：换弹结账**必须看 dummy 的减少量，不能看弹匣的增量**。
     *
     * 证据（TaCZ 1.1.8 反编译 ModernKineticGunItem#defaultReloadFinishing）：
     *   need = maxAmmo - 当前弹匣;
     *   consumed = 从 DummyAmmo 抽走 need 发;    ← 库存实际少了 need
     *   弹匣 += consumed;
     *   然后（非战术换弹 + CLOSED_BOLT / MANUAL_ACTION）：
     *     弹匣 -= 1;  那 1 发进了枪膛。        ← 弹匣净增只有 need-1
     *
     * 我们以前看弹匣从 0 涨到 17 就只扣 17 发，可 dummy 被抽走 18 发
     * → 每换一次弹表里多留 1 发，换两次多 2 发（主人实测现象）。
     *
     * dummy 前后差值 = TaCZ 真正从库存里拿走的发数（含进枪膛那发），天然准确。
     */
    private static final Map<UUID, Map<Integer, Integer>> LAST_DUMMY = new HashMap<>();

    /**
     * 2026-11-16（双持修复）：账本按「玩家 + 槽位」两维记。
     * 主手用 selected（0..8），副手固定 {@link #OFFHAND_SLOT}。
     *
     * 以前是玩家级单值：双持两把**同型号**枪时 gunId 相同，切枪守卫形同虚设，
     * 换弹结算会拿另一把的弹夹数比较 → 表里"多出来"或白扣。
     */
    private static Integer lastMagOf(UUID id, int slot) {
        Map<Integer, Integer> m = LAST_MAG.get(id);
        return m == null ? null : m.get(slot);
    }

    private static void putLastMag(UUID id, int slot, int value) {
        LAST_MAG.computeIfAbsent(id, k -> new HashMap<>()).put(slot, value);
    }

    private static ResourceLocation lastGunOf(UUID id, int slot) {
        Map<Integer, ResourceLocation> m = LAST_GUN.get(id);
        return m == null ? null : m.get(slot);
    }

    private static void putLastGun(UUID id, int slot, ResourceLocation gunId) {
        LAST_GUN.computeIfAbsent(id, k -> new HashMap<>()).put(slot, gunId);
    }

    private static Integer lastDummyOf(UUID id, int slot) {
        Map<Integer, Integer> m = LAST_DUMMY.get(id);
        return m == null ? null : m.get(slot);
    }

    private static void putLastDummy(UUID id, int slot, int value) {
        LAST_DUMMY.computeIfAbsent(id, k -> new HashMap<>()).put(slot, value);
    }

    private static void forgetSlot(UUID id, int slot) {
        Map<Integer, Integer> m = LAST_MAG.get(id);
        if (m != null) {
            m.remove(slot);
        }
        Map<Integer, ResourceLocation> g = LAST_GUN.get(id);
        if (g != null) {
            g.remove(slot);
        }
        Map<Integer, Integer> dm = LAST_DUMMY.get(id);
        if (dm != null) {
            dm.remove(slot);
        }
        Map<Integer, Mode> mm = MODE.get(id);
        if (mm != null) {
            mm.remove(slot);
        }
    }

    private static void forgetPlayer(UUID id) {
        LAST_MAG.remove(id);
        LAST_GUN.remove(id);
        LAST_DUMMY.remove(id);
        LAST_ENERGY_PCT.remove(id);
        LAST_HUD_TICK.remove(id);
    }

    /** 上次播报的电量百分比（2026-11-16：治「每 20 tick 刷屏」） */
    private static final Map<UUID, Integer> LAST_ENERGY_PCT = new HashMap<>();

    /** 2026-11-16（D4）：玩家退出时整份清掉（运行模式 + 三张按槽位的账本） */
    public static void forgetPlayerState(UUID id) {
        clearModes(id);
        forgetPlayer(id);
    }
    /** 影子补弹阈值见 {@link Config#refillThreshold}（2026-11-09 提成配置，整合包可调） */

    private static final String KEY_DUMMY = "DummyAmmo";

    private ReloadHandler() {
    }

    // ==================== 开火：只记账 ====================

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.getLogicalSide().isClient()) {
            return;
        }
        if (!(event.getShooter() instanceof Player player)) {
            return;
        }
        ItemStack gun = event.getGunItemStack();
        IGun gunApi = IGun.getIGunOrNull(gun);
        if (gunApi == null) {
            return;
        }
        // 能量枪（fuel）不吃弹药表，充能换弹在 tick 里记账
        if (isEnergyGun(gun)) {
            return;
        }
        AmmoCategory cat = AmmoCategory.ofGun(gun);
        if (cat == null || player.isCreative()) {
            return;
        }
        // 只有背包直读枪（加特林）开火才吃备弹
        if (!gunApi.useInventoryAmmo(gun)) {
            return;
        }
        // 背包模式下手是 TaCZ 自己在吃子弹，我们别插手。
        // 开火事件拿不到槽位，所以取保守判断：任一槽位在 VANILLA 就跳过（宁可少扣一次，
        // 也不要双扣 —— tick 里的影子降账会把表拉回正确值）。
        if (anySlotVanilla(player.getUUID())) {
            return;
        }

        // 一发扣「这颗子弹的权重」点（9mm = 1 点，RPG 火箭 = 8 点）
        int weight = AmmoWeights.ofGun(gun);
        int ammo = AmmoDataStore.get(player, cat);
        if (ammo < weight) {
            return;   // 剩下的点不够打这一发，交给 tick 去切模式
        }
        AmmoDataStore.set(player, cat, ammo - weight);
        syncHudThrottled(player);
    }

    /**
     * 开火路径专用的节流同步（2026-11-16 性能 D5）。
     *
     * 加特林 rpm=1200 ≈ 每秒 20 发，每发都 syncAll 就是每秒 20 个包 × 7 个类别，
     * 多人服一场混战能压出可观的带宽。HUD 本来也不需要每发都刷新 ——
     * 每 5 tick（0.25 秒）最多推一次，视觉上完全看不出差别。
     */
    private static void syncHudThrottled(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        long now = player.tickCount;
        Long prev = LAST_HUD_TICK.get(sp.getUUID());
        if (prev != null && now - prev < 5) {
            return;
        }
        LAST_HUD_TICK.put(sp.getUUID(), now);
        AmmoNetwork.syncAll(sp);
    }

    /** 每个玩家上次推送 HUD 的 tick（D5 节流用） */
    private static final Map<UUID, Long> LAST_HUD_TICK = new HashMap<>();

    // ==================== tick：模式切换 + 换弹记账 + 影子补弹 ====================

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide()) {
            return;
        }

        // 2026-11-16（双持修复）：主手、副手各算一份账。
        // 以前只盯主手，副手换弹时 TaCZ 把 dummy 填满、我们却不扣表 → 表里"多出来"。
        tickOneGun(player, player.getMainHandItem(), player.getInventory().selected);
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            tickOneGun(player, offhand, OFFHAND_SLOT);
        }
    }

    private static final int OFFHAND_SLOT = 40;

    private static void tickOneGun(Player player, ItemStack gun, int slot) {
        UUID id = player.getUUID();
        IGun gunApi = IGun.getIGunOrNull(gun);
        if (gunApi == null) {
            forgetSlot(id, slot);
            return;
        }
        AmmoCategory cat = AmmoCategory.ofGun(gun);
        if (cat == null) {
            forgetSlot(id, slot);
            return;
        }

        // ===== 能量枪走自己的路（电量，不是弹药表）=====
        if (isEnergyGun(gun)) {
            tickEnergyGun(player, gun, slot, gunApi);
            return;
        }

        int data = AmmoDataStore.get(player, cat);
        int weight = AmmoWeights.ofGun(gun);
        // 「表里还有点」= 至少够打一发（不是 > 0，点数可能不够一发）
        boolean hasTable = data >= weight;
        boolean hasInventoryAmmo = hasAmmoInInventory(player, gun);
        Mode current = modeOf(id, slot);

        // ===== 决定该不该切模式（只在变化时动手）=====
        if (hasTable && current == Mode.VANILLA) {
            // 表被补上了 → 切回 TABLE
            gunApi.setDummyAmmoAmount(gun, AmmoWeights.rounds(data, weight));
            gunApi.setMaxDummyAmmoAmount(gun, AmmoWeights.rounds(AmmoDataStore.capacity(player, cat), weight));
            putMode(id, slot, Mode.TABLE);
            if (Config.logReload) {
                TaczAmmoData.LOGGER.info("[弹药数据] {} {} 表已补 → 切回弹药系统",
                        player.getName().getString(), cat.displayName);
            }
        } else if (!hasTable && hasInventoryAmmo && current == Mode.TABLE) {
            // 表空 + 背包有弹 → 交出控制权
            gun.getOrCreateTag().remove(KEY_DUMMY);
            putMode(id, slot, Mode.VANILLA);
            if (Config.logReload) {
                TaczAmmoData.LOGGER.info("[弹药数据] {} {} 表空 → 交还背包子弹",
                        player.getName().getString(), cat.displayName);
            }
        } else if (!hasTable && !hasInventoryAmmo && current == Mode.VANILLA) {
            // 表空 + 背包也没弹 → 退回 TABLE（就停在 0）
            gunApi.setDummyAmmoAmount(gun, 0);
            putMode(id, slot, Mode.TABLE);
            if (Config.logReload) {
                TaczAmmoData.LOGGER.info("[弹药数据] {} {} 背包也空了 → 退回弹药系统（0）",
                        player.getName().getString(), cat.displayName);
            }
        }

        // ===== 背包模式下：什么都不做，TaCZ 自己管 =====
        if (modeOf(id, slot) == Mode.VANILLA) {
            // 但换弹后弹药表不该被扣 —— 这里不记换弹账
            putLastMag(id, slot, gunApi.getCurrentAmmoCount(gun));
            putLastGun(id, slot, gunApi.getGunId(gun));
            putLastDummy(id, slot, gunApi.getDummyAmmoAmount(gun));
            if (player.tickCount % 10 == 0) {
                syncHud(player);
            }
            return;
        }

        // ===== 下面全是 TABLE 模式的事 =====

        // 还没写过 dummy → 首次写入，进入虚拟备弹模式
        if (!gunApi.useDummyAmmo(gun)) {
            gunApi.setDummyAmmoAmount(gun, AmmoWeights.rounds(data, weight));
            gunApi.setMaxDummyAmmoAmount(gun, AmmoWeights.rounds(AmmoDataStore.capacity(player, cat), weight));
            putLastMag(id, slot, gunApi.getCurrentAmmoCount(gun));
            putLastGun(id, slot, gunApi.getGunId(gun));
            putLastDummy(id, slot, gunApi.getDummyAmmoAmount(gun));
            return;
        }

        int onGun = gunApi.getDummyAmmoAmount(gun);
        int mag = gunApi.getCurrentAmmoCount(gun);
        Integer lastMag = lastMagOf(id, slot);
        Integer lastDummy = lastDummyOf(id, slot);

        // ===== 切枪守卫：换了枪就不判换弹 =====
        //
        // 每把枪的弹夹数是各算各的。拿步枪(30)切到手枪(15)再切回来(30)，
        // 若不看枪 ID，就会把「15→30」当成换弹补了 15 发，白扣弹药表。
        ResourceLocation gunId = gunApi.getGunId(gun);
        ResourceLocation lastGun = lastGunOf(id, slot);
        boolean gunSwitched = lastGun == null || !lastGun.equals(gunId);
        if (gunSwitched) {
            putLastGun(id, slot, gunId);
            putLastMag(id, slot, mag);      // 重置基准，不判换弹
            putLastDummy(id, slot, onGun);
            lastMag = null;
            lastDummy = null;
            // 同类但不同口径的枪（9mm 手枪 ↔ .50AE 手枪）权重不同，
            // 旧 dummy 是按上一把枪的权重算出来的「发数」，必须重写，
            // 否则 HUD 会拿 30 发（9mm 的）去配 15 发的上限。
            gunApi.setDummyAmmoAmount(gun, AmmoWeights.rounds(data, weight));
            gunApi.setMaxDummyAmmoAmount(gun,
                    AmmoWeights.rounds(AmmoDataStore.capacity(player, cat), weight));
            if (Config.logReload) {
            }
        }

        // ===== 换弹结算：看「dummy 减少了多少」=====
        //
        // ⚠️ 2026-11-16（U1 根治）：以前靠「弹夹突然变多」判断换弹，会**少扣 1~2 发**。
        //
        // TaCZ 1.1.8 换弹收尾（ModernKineticGunItem#defaultReloadFinishing）：
        //   ① need = 弹匣上限 - 当前弹匣
        //   ② 从 DummyAmmo 抽走 need 发          ← 库存真的少了 need
        //   ③ 弹匣 += 抽到的发数
        //   ④ 非战术换弹 + 闭栓/栓动：弹匣 -= 1，那发进枪膛   ← 弹匣净增只有 need-1
        // 于是「弹匣涨了 17」对应的库存消耗其实是 18，我们只扣 17 → 每换一次漏 1 发。
        //
        // dummy 是 TaCZ 直接改写的，前后差值就是它真正吃掉多少发，不需要猜弹匣、
        // 也不需要管枪膛、弹种、bullet_amount 这些花活。
        //
        // 为什么不能只看 dummy 差：开火时 TaCZ 也会扣 dummy（reduceCurrentAmmoCount 走的是
        // 弹匣不是 dummy，但 useInventoryAmmo 的枪会扣）。我们的 GunFireEvent 已经按发扣了表，
        // 所以这里**只在识别到换弹时**才按 dummy 差结算，其余时间只更新基准。
        boolean freeReload = player.isCreative() || isInfiniteReload(gun);
        int roundsLeft = AmmoWeights.rounds(data, weight);
        boolean reloaded = lastMag != null && mag > lastMag;
        if (!freeReload && reloaded && roundsLeft > 0) {
            // ⚠️ 有 dummy 基准就**只认 dummy 掉量**，不再拿弹夹增量兜底 ——
            // 否则「弹匣分几段涨」的枪会先按 dummy 扣一次、第二段又按弹夹扣一次（重复扣）。
            // 没有基准（首次见到这把枪）才退回弹夹增量。
            int eaten = lastDummy != null
                    ? Math.max(0, lastDummy - onGun)
                    : (mag - lastMag);
            int consumed = Math.min(eaten, roundsLeft);
            int pay = Math.min(AmmoWeights.points(consumed, weight), data);
            if (pay > 0) {
                AmmoDataStore.set(player, cat, data - pay);
            }
            data = AmmoDataStore.get(player, cat);
            if (consumed > 0 && Config.logReload) {
                TaczAmmoData.LOGGER.info("[弹药数据] {} 换弹消耗 {} 发 {}（{} 点实扣 {}，弹夹 {}→{}，dummy {}→{}），剩余 {}/{} 点",
                        player.getName().getString(), consumed, cat.displayName,
                        AmmoWeights.points(consumed, weight), pay, lastMag, mag,
                        lastDummy, onGun,
                        data, AmmoDataStore.capacity(player, cat));
            }
            if (consumed > 0 && Config.showReloadMessage) {
                player.displayClientMessage(reloadMessage(cat, player, gun), true);
            }
        }

        // ===== 影子补弹：dummy 快见底才补，平时不碰 =====
        // dummy 存的是「发」，表里存的是「点」，所以先换算再比
        int roundsNow = AmmoWeights.rounds(data, weight);
        int onGunNow = gunApi.getDummyAmmoAmount(gun);
        if (roundsNow <= 0 && onGunNow > 0) {
            // 2026-11-16 修（B4）：弹药表空了，枪上的 dummy 必须一起清 0。
            // 以前这里只在 roundsNow > 0 才动，残留的几发 dummy 还能换弹 —— 表空了照样白嫖。
            gunApi.setDummyAmmoAmount(gun, 0);
        } else if (roundsNow > 0 && onGunNow > roundsNow) {
            // 2026-11-16（双持修复·补齐）：表被别处扣小了（另一把枪换弹 / 补给包用掉），
            // 这把枪的 dummy 还停在旧值 → 还能换出"多出来的子弹"。影子账以前只补不降。
            gunApi.setDummyAmmoAmount(gun, roundsNow);
            if (Config.logReload) {
            }
        } else if (roundsNow > 0 && (onGunNow <= 0 || onGunNow < roundsNow - Config.refillThreshold)) {
            gunApi.setDummyAmmoAmount(gun, roundsNow);
            if (Config.logReload) {
            }
        }

        int capNow = AmmoWeights.rounds(AmmoDataStore.capacity(player, cat), weight);
        if (!gunApi.hasMaxDummyAmmo(gun) || gunApi.getMaxDummyAmmoAmount(gun) != capNow) {
            gunApi.setMaxDummyAmmoAmount(gun, capNow);
        }
        putLastMag(id, slot, gunApi.getCurrentAmmoCount(gun));
        // 影子补弹可能刚改过 dummy，基准必须取「处理完之后」的值
        putLastDummy(id, slot, gunApi.getDummyAmmoAmount(gun));

        if (player.tickCount % 10 == 0) {
            syncHud(player);
        }
    }

    // ==================== 能量枪（燃料 / 电池弹药）====================
    //
    // 判定**按弹药认**，不按换弹类型：
    //   有的枪（比如 FMIC 的自动霰弹 ch_sg）用电池，但换弹类型是 magazine，
    //   只看 fuel 会漏掉它，结果它跑去吃霰弹表 —— 实测 bug。
    //
    // 两种换弹类型的换算：
    //   fuel     ：1 次换弹 = 充满整匣，扣「这种电池一次的钱」
    //   magazine ：按发算，每发扣 cost ÷ 弹匣容量（凑够一匣正好等于一次的钱）
    //
    // 没装挂带（或纳米没解锁）：删 DummyAmmo，完全交还 TaCZ 原生（吃背包里的电池物品）。

    private static void tickEnergyGun(Player player, ItemStack gun, int slot, IGun gunApi) {
        UUID id = player.getUUID();
        int mag = gunApi.getCurrentAmmoCount(gun);
        ResourceLocation gunId = gunApi.getGunId(gun);
        ResourceLocation lastGun = lastGunOf(id, slot);
        boolean switched = lastGun == null || !lastGun.equals(gunId);

        ResourceLocation ammoId = ammoIdOf(gun);
        boolean nano = EnergyStore.isNanoAmmo(ammoId);
        boolean usable = EnergyStore.isUnlocked(player)
                && (!nano || EnergyStore.isNanoUnlocked(player));

        // ⚠️ 2026-11-16（扫完全部 157 把枪之后补）：`inventory` 类型的能量枪
        //   （s10dmd / s10dmd_ff 用 trisdyna:emx_s10，tb23 用 sfms:arrow117）
        //   走的是 TaCZ 的「直接吃背包里的弹药物品」路径，HUD 对它们根本不显示备弹
        //   （GunHudOverlay:87 `useInventoryAmmo ? "" : ...`）。
        //   我们要是往它们身上写 DummyAmmo，等于跟 TaCZ 抢控制权 —— 交还给它。
        if (usable && gunApi.useInventoryAmmo(gun)) {
            if (gunApi.useDummyAmmo(gun)) {
                gun.getOrCreateTag().remove(KEY_DUMMY);
            }
            forgetSlot(id, slot);
            putLastGun(id, slot, gunId);
            putLastMag(id, slot, mag);
            return;
        }

        if (!usable) {
            if (gunApi.useDummyAmmo(gun)) {
                gun.getOrCreateTag().remove(KEY_DUMMY);
            }
            forgetSlot(id, slot);
            putLastGun(id, slot, gunId);
            putLastMag(id, slot, mag);
            return;
        }

        boolean fuel = isFuelFeed(gun);
        int magSize = Math.max(1, magazineSizeFor(gun));
        int costPerMag = EnergyStore.fuelCost(ammoId);
        // 「换一次弹要多少电」在 fuel 和 magazine 两种类型下含义完全不同：
        //   fuel     ：换一次 = 充整匣，扣 costPerMag（一次）
        //   magazine ：按发扣，每发 costPerMag/magSize（凑够一匣正好等于一次的钱）
        int rfPerRound = fuel ? costPerMag : Math.max(1, Math.round((float) costPerMag / magSize));
        int energy = EnergyStore.get(player);
        int affordable = energy / Math.max(1, rfPerRound);
        boolean enough = affordable > 0;

        // 没电 + 允许退回 + 背包里真有弹药 → 交还 TaCZ 原生
        // （否则会出现「没电也能免费换弹」，实测 bug）
        if (!enough && Config.energyFallbackToAmmo && hasAmmoInInventory(player, gun)) {
            if (gunApi.useDummyAmmo(gun)) {
                gun.getOrCreateTag().remove(KEY_DUMMY);
            }
            putLastGun(id, slot, gunId);
            putLastMag(id, slot, mag);
            return;
        }


        // 电量不够且弹匣没满 → 40 tick 提醒一次，别让玩家干按
        if (!enough && mag < magSize && player.tickCount % 40 == 0 && player instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal(String.format(
                    "§c电量不足：需要 %d RF（当前 %d）",
                    costPerMag, EnergyStore.get(sp))), true);
        }

        // 2026-11-16 修（B3）：以前每 20 tick 刷一条，手持能量枪时一直刷屏。
        // 改成每 200 tick（10 秒）看一次，而且**百分比变了才发**。
        // 2026-11-16（主人定）：默认关掉（energy.chatStatus=false），电量看 TaCZ HUD 就够了。
        if (Config.energyChatStatus
                && player.tickCount % 200 == 0 && player instanceof ServerPlayer spHud) {
            int pct = EnergyStore.percent(spHud);
            Integer lastPct = LAST_ENERGY_PCT.get(id);
            if (lastPct == null || lastPct != pct) {
                LAST_ENERGY_PCT.put(id, pct);
                int times = costPerMag <= 0 ? 0 : EnergyStore.get(spHud) / costPerMag;
                spHud.displayClientMessage(Component.literal(String.format(
                        "§b电量 %d%%§7 · %d / %d RF · 还能换 %d 次",
                        pct, EnergyStore.get(spHud), EnergyStore.capacity(spHud), times)), true);
            }
        }

        // 2026-11-16（B2 三次修，主人提的思路）：HUD 显示「**还剩几颗电池**」。
        //
        // 背景：TaCZ 的 GunHudOverlay:153-154 把 DummyAmmo 原样显示，不做换算；
        // 而它自己有 min(ammoCount, 9999) 的硬上限（:65）。
        //
        // 之前写「还能打多少发」的问题：
        //   · 数字巨大（cms92x 下界级 32000），超过 9999 被截断，比例条失真
        //   · rc / rc_ar 这种一发一枚下界合金的炮，基础档算出来是 0 ——
        //     明明身上有电、能打一枪，HUD 却显示 0，看着像坏了
        //
        // 主人定的新口径：**一次换弹 = 一颗电池 = costPerMag 的电**。
        // 所以「还剩几颗电池」= 电量 / costPerMag，语义直观、数字小、永不溢出。
        //
        // 弹匣数照旧由 TaCZ 自己画在左边（它读 getCurrentAmmoCount），
        // 我们不碰，所以玩家看到的还是「弹匣 / 电池」两段，各管各的。
        int hudAmount;
        int hudMax;
        if (Config.energyTaczHud) {
            long batteries;
            if (costPerMag <= 0) {
                // 不耗电的枪（理论上不该有），给个 9999 避免除零
                batteries = 9999L;
            } else {
                batteries = energy / Math.max(1, costPerMag);
            }
            // 一颗都换不了、但确实还有一点电 → 至少显示 1（"还能打最后一枪"）
            if (batteries <= 0 && energy > 0) {
                batteries = 1;
            }
            hudAmount = (int) Math.max(0, Math.min(batteries, 9999L));
            hudMax = Math.max(hudAmount, 1);
        } else {
            hudAmount = energy > 0 ? 1 : 0;
            hudMax = 1;
        }
        if (!gunApi.useDummyAmmo(gun) || gunApi.getDummyAmmoAmount(gun) != hudAmount) {
            gunApi.setDummyAmmoAmount(gun, hudAmount);
        }
        if (!gunApi.hasMaxDummyAmmo(gun) || gunApi.getMaxDummyAmmoAmount(gun) != hudMax) {
            gunApi.setMaxDummyAmmoAmount(gun, hudMax);
        }
        // 调试：能量枪 HUD 换算全过程（每 200 tick 记一次，别刷屏）
        if (Config.logReload && player.tickCount % 200 == 0) {
        }

        if (!switched) {
            Integer last = lastMagOf(id, slot);
            if (last != null && mag > last) {
                int loaded = mag - last;
                // fuel 型：一个电池 = 一整匣，只收一次钱；magazine 型才按发算
                int rf = fuel ? costPerMag : loaded * Math.max(1, rfPerRound);
                // 2026-11-16 修（B5）：以前 consume 失败只是不提示，弹却已经换上了 ——
                // 电量不够也能白换一匣。现在：没付成功就把这次多出来的弹回滚。
                if (EnergyStore.consume(player, rf) || player.isCreative()) {
                    if (player instanceof ServerPlayer sp && Config.showReloadMessage) {
                        sp.displayClientMessage(Component.literal(String.format(
                                        "§b充能换弹 §f-%d RF §7（%d / %d）",
                                        rf, EnergyStore.get(sp), EnergyStore.capacity(sp))), true);
                    }
                    if (Config.logReload) {
                        TaczAmmoData.LOGGER.info("[弹药数据] {} 能量换弹 {} 发，消耗 {} RF，剩余 {}/{}",
                                player.getName().getString(), loaded, rf,
                                EnergyStore.get(player), EnergyStore.capacity(player));
                    }
                } else {
                    gunApi.setCurrentAmmoCount(gun, last);
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(Component.literal("§c电量不足，换弹失败")
                                .withStyle(net.minecraft.ChatFormatting.RED), true);
                    }
                }
            }
        }

        putLastGun(id, slot, gunId);
        putLastMag(id, slot, mag);
    }

    /** 枪用的能量弹药 id（口径） */
    private static ResourceLocation ammoIdOf(ItemStack gun) {
        IGun api = IGun.getIGunOrNull(gun);
        if (api == null) {
            return null;
        }
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(api.getGunId(gun))
                .map(idx -> idx.getGunData().getAmmoId())
                .orElse(null);
    }

    /** 是不是能量枪：弹药是电池/纳米/核心，或者换弹类型是 fuel */
    static boolean isEnergyGun(ItemStack gun) {
        if (isFuelFeed(gun)) {
            return true;
        }
        return EnergyStore.isEnergyAmmo(ammoIdOf(gun));
    }

    /** 换弹类型是不是 fuel（消耗 1 个物品充满整匣） */
    private static boolean isFuelFeed(ItemStack gun) {
        IGun api = IGun.getIGunOrNull(gun);
        if (api == null || api.getGunId(gun) == null) {
            return false;
        }
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(api.getGunId(gun))
                .map(idx -> idx.getGunData().getReloadData().getType()
                        == com.tacz.guns.resource.pojo.data.gun.FeedType.FUEL)
                .orElse(false);
    }

    /** 弹匣容量（**带扩容配件**）—— HUD 上限和每发扣电都要用它算，才能和玩家看到的一致 */
    private static int magazineSizeFor(ItemStack gun) {
        int n = GunHelper.getMagazineSize(gun);
        if (n > 0) {
            return n;
        }
        // 兜底：读 index 里的基础值
        return magazineSize(gun);
    }

    /** 弹匣容量（基础值，不含配件） */
    private static int magazineSize(ItemStack gun) {
        IGun api = IGun.getIGunOrNull(gun);
        if (api == null || api.getGunId(gun) == null) {
            return 1;
        }
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(api.getGunId(gun))
                .map(idx -> idx.getGunData().getAmmoAmount())
                .orElse(1);
    }

    // ==================== 把账本同步到枪上 ====================

    /**
     * 把弹药表的当前值抄回快捷栏所有枪的 DummyAmmo。
     *
     * **为什么需要这个**（主人实测「补满后弹夹少 5 发」）：
     *   补给包只改了 AmmoDataStore 的账本。但 TaCZ 换弹时读的是**枪上的 DummyAmmo**
     *   来决定能抽多少子弹。账本补到 120、枪上还停在 45 的话，
     *   换弹就只能抽 45 发 → 弹夹填不满 → 玩家看到「少了 5 发」。
     *
     * 只在补给包用完后调用（低频操作），不影响平时的影子记账。
     */
    public static void syncGunsToTable(ServerPlayer player) {
        UUID id = player.getUUID();
        // 账本有数了，之前的「背包模式」作废（所有槽位一起），重新交给我们管
        clearModes(id);
        // 基准全部作废，下一 tick 重新建立，避免切换瞬间误判成换弹
        forgetPlayer(id);

        for (int slot = 0; slot < 9; slot++) {
            syncOneGun(player, player.getInventory().getItem(slot));
        }
        syncOneGun(player, player.getOffhandItem());
    }

    private static void syncOneGun(ServerPlayer player, ItemStack gun) {
        IGun gunApi = IGun.getIGunOrNull(gun);
        if (gunApi == null) {
            return;
        }
        AmmoCategory cat = AmmoCategory.ofGun(gun);
        if (cat == null) {
            return;
        }
        int data = AmmoDataStore.get(player, cat);
        int weight = AmmoWeights.ofGun(gun);
        // 点数不够打一发 = 等于没弹，别把 0 发写上去让 HUD 显示得怪怪的
        if (data < weight) {
            return;
        }
        gunApi.setDummyAmmoAmount(gun, AmmoWeights.rounds(data, weight));
        gunApi.setMaxDummyAmmoAmount(gun,
                AmmoWeights.rounds(AmmoDataStore.capacity(player, cat), weight));
    }

    // ==================== 背包里有没有这把枪能用的子弹 ====================

    /**
     * 这把枪是不是「无限备弹」类型（枪包里 reload.isInfinite()）。
     * 这种枪换弹不消耗任何东西，所以我们也不能扣弹药表。
     */
    private static boolean isInfiniteReload(ItemStack gun) {
        IGun gunApi = IGun.getIGunOrNull(gun);
        if (gunApi == null) {
            return false;
        }
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunApi.getGunId(gun))
                .map(idx -> idx.getGunData().getReloadData().isInfinite())
                .orElse(false);
    }

    /**
     * 扫玩家背包，看有没有这把枪能用的子弹 / 弹药盒。
     *
     * 用的是 TaCZ 自己的 IAmmo / IAmmoBox 判断，口径绝对准。
     */
    private static boolean hasAmmoInInventory(Player player, ItemStack gun) {
        return player.getCapability(ForgeCapabilities.ITEM_HANDLER, null).map(cap -> {
            for (int i = 0; i < cap.getSlots(); i++) {
                ItemStack s = cap.getStackInSlot(i);
                if (s.isEmpty()) {
                    continue;
                }
                var iAmmo = com.tacz.guns.api.item.IAmmo.getIAmmoOrNull(s);
                if (iAmmo != null && iAmmo.isAmmoOfGun(gun, s)) {
                    return true;
                }
                if (s.getItem() instanceof com.tacz.guns.api.item.IAmmoBox iAmmoBox
                        && iAmmoBox.isAmmoBoxOfGun(gun, s)) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    private static void syncHud(Player player) {
        if (player instanceof ServerPlayer sp) {
            AmmoNetwork.syncAll(sp);
        }
    }

    private static Component reloadMessage(AmmoCategory cat, Player player, ItemStack gun) {
        int weight = AmmoWeights.ofGun(gun);
        int points = AmmoDataStore.get(player, cat);
        int cap = AmmoDataStore.capacity(player, cat);
        return Component.literal(
                "§e" + cat.displayName + "§f "
                        + AmmoWeights.rounds(points, weight) + " §7/ " + AmmoWeights.rounds(cap, weight) + " 发"
                        + " §8(" + points + "/" + cap + " 点)");
    }
}
