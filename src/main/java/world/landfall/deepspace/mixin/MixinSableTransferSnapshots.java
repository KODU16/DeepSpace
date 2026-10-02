package world.landfall.deepspace.mixin;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.network.packets.PacketReceiveMode;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.client.SubLevelTransferProbeState;

/** Keeps a delayed source-galaxy snapshot off a replacement plot during transfer. */
@Mixin(value = ClientSableInterpolationState.class, remap = false)
public abstract class MixinSableTransferSnapshots {
    @Shadow private double mostRecentTick;
    @Shadow private boolean receivedFirstUpdate;
    @Shadow private double interpolationTick;
    @Shadow private double estimatedServerTickSpeed;
    @Shadow private boolean stopped;

    @Inject(method = "receiveSnapshot", at = @At("HEAD"), cancellable = true, remap = false)
    private void deepspace$rejectStaleTransferSnapshot(
            ClientSubLevel subLevel, int tick, Pose3dc pose, PacketReceiveMode mode, CallbackInfo callback) {
        if (SubLevelTransferProbeState.rejectStaleSnapshot(subLevel, pose)) {
            callback.cancel();
            return;
        }
        if (SubLevelTransferProbeState.isExpectedDestinationSnapshot(subLevel, pose)) {
            ClientSableInterpolationState interpolation = (ClientSableInterpolationState) (Object) this;
            double pointer = interpolation.getTickPointer();
            // Sable drops snapshots older than its pointer by six ticks; a new galaxy needs its own clock.
            if (!receivedFirstUpdate || pointer - tick > 6.0D
                    || tick - pointer > 6.0D + interpolation.getInterpolationDelay()) {
                mostRecentTick = tick;
                interpolationTick = tick;
                estimatedServerTickSpeed = 1.0D;
                receivedFirstUpdate = true;
                stopped = false;
                SubLevelTransferProbeState.recordClockRebase(subLevel, tick, pointer);
            }
        }
    }
}
