package world.landfall.deepspace.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.network.HyperRelayJumpRequestPacket;
import world.landfall.deepspace.network.HyperRelayJumpStatusPacket;

/**
 * Client half of the hyper-relay jump. Holds the server-synced state and exposes it via reflection
 * so VSIE's control-seat HUD can render the Hyper button and countdown without a hard dependency.
 * The K key and button rendering live in VSIE (see DeepSpaceHudBridge / ControlSeatWorldHudRenderer).
 */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class HyperRelayJumpClientState {
    private static volatile boolean inRange;
    private static volatile double distance = Double.POSITIVE_INFINITY;
    private static volatile int countdownTicks;
    private static ClientLevel statusLevel;

    private HyperRelayJumpClientState() {
    }

    public static void acceptStatus(HyperRelayJumpStatusPacket packet) {
        statusLevel = Minecraft.getInstance().level;
        inRange = packet.inRange();
        distance = packet.distance();
        countdownTicks = packet.countdownTicks();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // Preparation acknowledgements belong to one world and must not survive logout or a dimension change.
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().level != statusLevel) {
            statusLevel = Minecraft.getInstance().level;
            inRange = false;
            distance = Double.POSITIVE_INFINITY;
            countdownTicks = 0;
            return;
        }
        if (countdownTicks > 0) {
            countdownTicks--;
        }
    }

    /** Called by VSIE when the seated pilot presses K. */
    public static void requestJump() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        PacketDistributor.sendToServer(new HyperRelayJumpRequestPacket());
    }

    public static boolean isInRange() {
        return inRange;
    }

    public static double getDistance() {
        return distance;
    }

    public static int getCountdownTicks() {
        return countdownTicks;
    }
}
