package world.landfall.deepspace.server;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.network.SeamlessTransitionPacket;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Brackets respawn packets with an explicit client-side seamless-transition state.
 */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class SeamlessTransitionSignal {
    private static final Set<UUID> ACTIVE_PLAYERS = new HashSet<>();

    private SeamlessTransitionSignal() {
    }

    public static void begin(ServerPlayer player) {
        ACTIVE_PLAYERS.add(player.getUUID());
        PacketDistributor.sendToPlayer(player, new SeamlessTransitionPacket(true));
    }

    @SubscribeEvent
    public static void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && ACTIVE_PLAYERS.remove(player.getUUID())) {
            PacketDistributor.sendToPlayer(player, new SeamlessTransitionPacket(false));
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE_PLAYERS.remove(event.getEntity().getUUID());
    }
}
