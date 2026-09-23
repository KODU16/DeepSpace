package world.landfall.deepspace.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import world.landfall.deepspace.Deepspace;

/**
 * Handles network packet registration and management
 */
@SuppressWarnings("removal") // NeoForge currently requires the deprecated MOD bus selector here.
@EventBusSubscriber(modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {
    
    /**
     * Registers network packets during the mod initialization phase.
     *
     * @param event The payload registration event
     */
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2");
        
        // Register planet sync packet
        registrar.playToClient(
            PlanetSyncPacket.TYPE,
            PlanetSyncPacket.STREAM_CODEC,
            PlanetSyncPacket::handle
        );
        registrar.playToClient(
                SkyTransitionPacket.TYPE,
                SkyTransitionPacket.STREAM_CODEC,
                SkyTransitionPacket::handle
        );
        registrar.playToClient(
                SeamlessTransitionPacket.TYPE,
                SeamlessTransitionPacket.STREAM_CODEC,
                SeamlessTransitionPacket::handle
        );
        registrar.playToClient(
                GalaxyArrivalPacket.TYPE,
                GalaxyArrivalPacket.STREAM_CODEC,
                GalaxyArrivalPacket::handle
        );
        registrar.playToClient(
                StarMapOpenPacket.TYPE,
                StarMapOpenPacket.STREAM_CODEC,
                StarMapOpenPacket::handle
        );
        registrar.playToClient(
                SubLevelTransferProbePacket.TYPE,
                SubLevelTransferProbePacket.STREAM_CODEC,
                SubLevelTransferProbePacket::handle
        );
        registrar.playToServer(
                SubLevelTransferProbeReplyPacket.TYPE,
                SubLevelTransferProbeReplyPacket.STREAM_CODEC,
                SubLevelTransferProbeReplyPacket::handle
        );
        registrar.playToServer(
                JetpackPacket.RocketForward.TYPE,
                JetpackPacket.RocketForward.STREAM_CODEC,
                JetpackPacket.RocketForward::handle
        );
        registrar.playToServer(
                JetpackPacket.BeginFlying.TYPE,
                JetpackPacket.BeginFlying.STREAM_CODEC,
                JetpackPacket.BeginFlying::handle
        );
        registrar.playToServer(
                HyperRelayJumpRequestPacket.TYPE,
                HyperRelayJumpRequestPacket.STREAM_CODEC,
                HyperRelayJumpRequestPacket::handle
        );
        registrar.playToClient(
                HyperRelayJumpStatusPacket.TYPE,
                HyperRelayJumpStatusPacket.STREAM_CODEC,
                HyperRelayJumpStatusPacket::handle
        );
    }
} 
