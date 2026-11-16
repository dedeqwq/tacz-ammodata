package com.dsh.taczammodata;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端-服务端同步。
 *
 * 弹药容量表存在服务端的玩家 PersistentData 里，客户端读不到，
 * 所以每次变化都要把 7 类的「当前量/上限/等级」推给客户端，HUD 才有东西画。
 */
public final class AmmoNetwork {

    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TaczAmmoData.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static int nextId = 0;

    private AmmoNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++,
                SyncMsg.class,
                SyncMsg::encode,
                SyncMsg::decode,
                SyncMsg::handle);
    }

    /** 把该玩家全部 7 类容量表推给客户端 */
    public static void syncAll(ServerPlayer player) {
        Map<AmmoCategory, int[]> snap = AmmoDataStore.snapshot(player);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncMsg(snap));
    }

    // ==================== 消息 ====================

    public static class SyncMsg {
        /** category -> [当前量, 上限, 等级] */
        public final Map<AmmoCategory, int[]> data;

        public SyncMsg(Map<AmmoCategory, int[]> data) {
            this.data = data;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(AmmoCategory.values().length);
            for (AmmoCategory c : AmmoCategory.values()) {
                int[] v = data.get(c);
                if (v == null) {
                    v = new int[]{0, 0, 0};
                }
                buf.writeUtf(c.key());
                buf.writeVarInt(v[0]);
                buf.writeVarInt(v[1]);
                buf.writeByte(v[2]);
            }
        }

        public static SyncMsg decode(FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            Map<AmmoCategory, int[]> map = new EnumMap<>(AmmoCategory.class);
            for (int i = 0; i < n; i++) {
                String key = buf.readUtf();
                int amount = buf.readVarInt();
                int cap = buf.readVarInt();
                int tier = buf.readByte();
                AmmoCategory c = AmmoCategory.byKey(key);
                if (c != null) {
                    map.put(c, new int[]{amount, cap, tier});
                }
            }
            return new SyncMsg(map);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context context = ctx.get();
            context.enqueueWork(() -> {
                if (context.getDirection() == NetworkDirection.PLAY_TO_CLIENT) {
                    ClientAmmoCache.update(data);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /**
     * 客户端缓存。HUD 只从这里读，不碰服务端数据。
     */
    public static final class ClientAmmoCache {
        private static final Map<AmmoCategory, int[]> CACHE = new EnumMap<>(AmmoCategory.class);

        private ClientAmmoCache() {
        }

        public static void update(Map<AmmoCategory, int[]> incoming) {
            CACHE.putAll(incoming);
        }

        /** [当前量, 上限, 等级]，没数据返回 {0,0,0} */
        public static int[] get(AmmoCategory c) {
            int[] v = CACHE.get(c);
            return v == null ? new int[]{0, 0, 0} : v;
        }

        public static int amount(AmmoCategory c) {
            return get(c)[0];
        }

        public static int capacity(AmmoCategory c) {
            return get(c)[1];
        }

        public static int tier(AmmoCategory c) {
            return get(c)[2];
        }

        public static boolean hasData() {
            return !CACHE.isEmpty();
        }

        public static void clear() {
            CACHE.clear();
        }
    }
}