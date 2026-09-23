package world.landfall.deepspace.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.planet.GalaxyDimensions;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Keeps generated galaxy chunks memory-only while preserving planet and Sable sub-level storage. */
@Mixin(ChunkMap.class)
public abstract class MixinGalaxyChunkMap {
    @Shadow(remap = false)
    @Final
    private ServerLevel level;

    @Inject(
            method = "readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void deepspace$skipGalaxyChunkRead(
            ChunkPos chunkPos,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback
    ) {
        if (!GalaxyDimensions.isGalaxy(this.level.dimension())) {
            return;
        }
        // Report no disk data, then let vanilla create the empty chunk on its main-thread continuation.
        callback.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
    }

    @Inject(
            method = "skipPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void deepspace$disableGalaxyViewDistanceTickets(
            ServerPlayer player,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!GalaxyDimensions.isGalaxy(this.level.dimension())) {
            return;
        }
        // 星系只靠 Sable 强制票加载船体，不再打开原版视距，否则进出太空会生成/卸载整圈空区块。
        callback.setReturnValue(true);
    }

    @Inject(
            method = "isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void deepspace$trackEntitiesInForcedGalaxyChunks(
            ServerPlayer player,
            int x,
            int z,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!GalaxyDimensions.isGalaxy(this.level.dimension())) {
            return;
        }
        // 强制加载的船体区块仍需实体同步，但不依赖临时视距窗口。
        if (this.level.getForcedChunks().contains(ChunkPos.asLong(x, z))) {
            callback.setReturnValue(true);
        }
    }

    @Inject(
            method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void deepspace$skipGalaxyChunkSave(
            ChunkAccess chunk,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!GalaxyDimensions.isGalaxy(this.level.dimension())) {
            return;
        }
        // Clear the dirty flag so periodic and shutdown flushes do not retry an intentionally ephemeral chunk.
        chunk.setUnsaved(false);
        callback.setReturnValue(false);
    }
}
