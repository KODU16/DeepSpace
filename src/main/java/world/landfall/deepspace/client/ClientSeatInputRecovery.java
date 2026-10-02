package world.landfall.deepspace.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.network.SubLevelTransferCompletePacket;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.UUID;

/** Binds VSIE seat input to the mount confirmed by the completed transfer handshake. */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class ClientSeatInputRecovery {
    private static final String MOUNT_CLASS = "com.kodu16.vsie.content.controlseat.entity.ControlSeatMountEntity";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Method boundBlockPos;
    private static Method handleInput;
    private static Method getClientData;
    private static Method clearSeatBinding;
    private static Method bindSeat;
    private static Field warpPreparing;
    private static Field pendingWarpTeleport;
    private static UUID currentTransferId;
    private static String destinationDimension;
    private static UUID pendingMountId;
    private static UUID activeMountId;

    private ClientSeatInputRecovery() {
    }

    /** A new transfer invalidates the previous seat binding before the dimension changes. */
    public static void beginTransfer(UUID transferId) {
        currentTransferId = transferId;
        destinationDimension = null;
        pendingMountId = null;
        activeMountId = null;
    }

    /** The server names the exact replacement mount only after riding restoration succeeds. */
    public static void completeTransfer(SubLevelTransferCompletePacket packet) {
        if (!packet.transferId().equals(currentTransferId)) {
            return;
        }
        destinationDimension = packet.destinationDimension();
        pendingMountId = packet.mountId();
        activeMountId = null;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            activeMountId = null;
            return;
        }
        Entity vehicle = player.getVehicle();
        boolean destinationReady = destinationDimension != null
                && destinationDimension.equals(player.level().dimension().location().toString());
        if (activeMountId == null && destinationReady && vehicle != null
                && MOUNT_CLASS.equals(vehicle.getClass().getName())
                && (pendingMountId == null || pendingMountId.equals(vehicle.getUUID()))) {
            try {
                if (!resolveMethods(player)) {
                    return;
                }
                BlockPos seatPos = (BlockPos) boundBlockPos.invoke(vehicle);
                if (seatPos == null) {
                    return;
                }
                Object data = getClientData.invoke(null, player);
                boolean wasPreparing = warpPreparing.getBoolean(data);
                boolean hadPendingTeleport = pendingWarpTeleport.getBoolean(data);
                // The new mount is authoritative; discard VSIE's timed grace and old warp controls.
                clearSeatBinding.invoke(data);
                bindSeat.invoke(data, seatPos, vehicle.getUUID());
                String phase = pendingMountId == null ? "RIDING_BOUND" : "HANDSHAKE_BOUND";
                activeMountId = vehicle.getUUID();
                pendingMountId = null;
                LOGGER.info("[DEEPSPACE-SEAT-INPUT] phase={} transfer={} mount={} dimension={} oldWarpPreparing={} oldPendingTeleport={}",
                        phase, currentTransferId, activeMountId, destinationDimension,
                        wasPreparing, hadPendingTeleport);
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn("Could not rebind VSIE input to the transferred seat", exception);
                return;
            }
        }
        if (activeMountId == null) {
            return;
        }
        if (!destinationDimension.equals(player.level().dimension().location().toString())
                || player.getVehicle() == null
                || !activeMountId.equals(player.getVehicle().getUUID())) {
            LOGGER.info("[DEEPSPACE-SEAT-INPUT] phase=UNBOUND transfer={} mount={}", currentTransferId, activeMountId);
            activeMountId = null;
            return;
        }
        try {
            if (!resolveMethods(player)) {
                return;
            }
            BlockPos seatPos = (BlockPos) boundBlockPos.invoke(player.getVehicle());
            if (seatPos != null) {
                // The handshake owns this transfer's input route until the player leaves its exact mount.
                handleInput.invoke(null, player, seatPos);
            }
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("Could not forward input to the transferred VSIE seat", exception);
            activeMountId = null;
        }
    }

    public static String bindingState() {
        return activeMountId != null ? "active" : pendingMountId != null ? "awaiting_mount" : "inactive";
    }

    private static boolean resolveMethods(LocalPlayer player) {
        if (handleInput != null && bindSeat != null) {
            return true;
        }
        try {
            boundBlockPos = player.getVehicle().getClass().getMethod("getBoundBlockPos");
            Class<?> handler = Class.forName("com.kodu16.vsie.content.controlseat.client.Input.ClientMouseHandler");
            handleInput = handler.getMethod("handle", LocalPlayer.class, BlockPos.class);
            Class<?> manager = Class.forName("com.kodu16.vsie.content.controlseat.client.Input.ClientDataManager");
            Class<?> data = Class.forName("com.kodu16.vsie.content.controlseat.client.ControlSeatClientData");
            getClientData = manager.getMethod("getClientData", Player.class);
            clearSeatBinding = data.getMethod("clearSeatBinding");
            bindSeat = data.getMethod("bindSeat", BlockPos.class, UUID.class);
            warpPreparing = data.getField("isWarpPreparing");
            pendingWarpTeleport = data.getField("hasPendingWarpTeleport");
            return true;
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("VSIE seat input forwarding is unavailable", exception);
            activeMountId = null;
            return false;
        }
    }
}
