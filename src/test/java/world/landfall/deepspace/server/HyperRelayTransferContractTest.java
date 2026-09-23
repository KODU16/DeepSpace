package world.landfall.deepspace.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks Hyper Relay transfers to Sable structures and client-safe seat restoration. */
public final class HyperRelayTransferContractTest {
    private HyperRelayTransferContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String teleport = read("src/main/java/world/landfall/deepspace/planet/PlanetTeleportHandler.java");
        require(teleport.contains("if (closestPlanet.isWormhole())")
                        && teleport.indexOf("if (closestPlanet.isWormhole())")
                        < teleport.indexOf("Vec3 wormholeLanding"),
                "standalone players must leave the Hyper Relay path before planning a landing");

        String events = read("src/main/java/world/landfall/deepspace/server/SubLevelEvents.java");
        require(events.contains("boolean mayRestoreRiding = destinationHullReady(container, pending);")
                        && events.contains("complete = trackingRestored && ridingRestored && playersRestored;")
                        && events.contains("phase=RESTORE_RIDING_WITHOUT_CLIENT")
                        && events.contains("phase=REPLACEMENT_POSE_ALIGNED")
                        && events.contains("clampReplacementPlotBounds(plot, jump.snapshot(), sourceId);")
                        && events.contains("copy.logicalPose().position().set(destination);"),
                "seat restoration must remount on a valid hull without waiting for a frozen client");
        require(events.contains("physics.getPipeline().wakeUp(copy);")
                        && events.contains("physics.setPaused(false);"),
                "replacement Sable bodies must be active after a cross-dimension transfer");
        require(events.contains("boolean clientReady")
                        && events.contains("new ClientboundTeleportEntityPacket(player)")
                        && events.contains("complete = clientsReady && trackingRestored && ridingRestored && playersRestored;"),
                "passenger packets must wait for client tracking and re-anchor the destination player position");
        require(events.contains("rebindTransferredEntity(passenger, replacementSubLevel);")
                        && events.contains("rebindTransferredEntity(vehicle, replacementSubLevel);")
                        && events.contains("EntitySubLevelUtil.setOldPosNoMovement(player);")
                        && events.contains("new ClientboundSetPassengersPacket(vehicle)")
                        && events.indexOf("player.teleportTo(")
                        < events.indexOf("passenger.startRiding(vehicle, true)"),
                "restored riders and replacement seats must use the destination Sable transform baseline");
        require(events.contains("setBoundBlockPos")
                        && events.contains("writeVsieControlSeatBoundPos(vehicle, replacementTarget);"),
                "recreated VSIE seats must use the destination plot binding after a cross-dimension transfer");
        require(events.contains("destinationLevel.getBlockEntity(target.boundBlockPos())")
                        && events.contains("\"confirmExternalPassengerRestore\",\n                                net.minecraft.world.entity.player.Player.class,\n                                vehicle.getClass()")
                        && events.contains("getMethod(\"confirmExternalPassengerRestore\", Entity.class)"),
                "VSIE passenger confirmation must target the destination control-seat block entity first");

        String mount = read("C:/Program Files (x86)/360/360PT/mc/modding/vsie/src/main/java/com/kodu16/vsie/content/controlseat/entity/ControlSeatMountEntity.java");
        require(mount.contains("public boolean confirmExternalPassengerRestore(Entity passenger)"),
                "VSIE control-seat mounts must expose the Entity-typed restore hook");
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file));
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
