package world.landfall.deepspace.network;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.server.HyperRelayJumpManager;
import world.landfall.deepspace.server.SubLevelEvents;

/** Control-seat K key: asks the server to start a hyper-relay jump for the player's current sub-level. */
public record HyperRelayJumpRequestPacket() implements CustomPacketPayload {
    public static final Type<HyperRelayJumpRequestPacket> TYPE =
            new Type<>(Deepspace.path("hyper_relay_jump_request"));
    public static final StreamCodec<FriendlyByteBuf, HyperRelayJumpRequestPacket> STREAM_CODEC =
            StreamCodec.unit(new HyperRelayJumpRequestPacket());

    public static void handle(HyperRelayJumpRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            SubLevel subLevel = SubLevelEvents.findTrackedSubLevelInRidingGraph(player);
            if (subLevel == null || player.serverLevel() == null) {
                // Reply to invalid requests instead of leaving the client with a stale preparation indicator.
                PacketDistributor.sendToPlayer(player,
                        new HyperRelayJumpStatusPacket(false, Double.POSITIVE_INFINITY, 0));
                LogUtils.getLogger().info("[DEEPSPACE-JUMP] phase=REJECTED reason=no_tracked_sublevel player={}",
                        player.getUUID());
                return;
            }
            HyperRelayJumpManager.tryStartJump(player.getServer(), player.serverLevel(), subLevel);
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
