package world.landfall.deepspace.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks ephemeral chunk storage to DeepSpace galaxy dimensions only. */
public final class SpaceChunkPersistenceContractTest {
    private SpaceChunkPersistenceContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String mixin = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/mixin/MixinGalaxyChunkMap.java"
        ));
        require(mixin.contains("GalaxyDimensions.isGalaxy(this.level.dimension())"),
                "Chunk persistence must be disabled only for DeepSpace galaxy dimensions");
        require(mixin.contains("@Mixin(ChunkMap.class)"),
                "The optimization must target vanilla region chunks rather than Sable sub-level storage");
        require(mixin.contains("chunk.setUnsaved(false)"),
                "Ephemeral chunks must be marked clean before the save is cancelled");
        require(mixin.contains("method = \"readChunk")
                        && mixin.contains("CompletableFuture.completedFuture(Optional.empty())"),
                "Galaxy chunk reads must report no disk data while retaining vanilla scheduling");
        require(!mixin.contains("method = \"scheduleChunkLoad"),
                "Galaxy chunk optimization must not bypass ChunkMap's main-thread completion boundary");
        require(mixin.contains("method = \"skipPlayer")
                        && mixin.contains("callback.setReturnValue(true)"),
                "Galaxy players must remain outside vanilla view-distance chunk tickets");
        require(mixin.contains("method = \"isChunkTracked")
                        && mixin.contains("this.level.getForcedChunks().contains(ChunkPos.asLong(x, z))"),
                "Explicitly forced VSIE/RPL chunks must remain eligible for entity network tracking");

        String serverConfig = Files.readString(Path.of("src/main/resources/deepspace.mixins.server.json"));
        String clientConfig = Files.readString(Path.of("src/main/resources/deepspace.mixins.json"));
        require(serverConfig.contains("MixinGalaxyChunkMap"),
                "Dedicated servers must install ephemeral galaxy chunks");
        require(clientConfig.contains("MixinGalaxyChunkMap"),
                "Integrated servers must install ephemeral galaxy chunks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
