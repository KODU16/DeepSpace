package world.landfall.deepspace.planet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads additive planet definitions from data/<namespace>/deepspace/planets/*.json.
 */
@EventBusSubscriber(modid = Deepspace.MODID)
public final class PlanetDataReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "deepspace/planets";

    private PlanetDataReloadListener() {
    }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new Listener());
    }

    private static final class Listener extends SimpleJsonResourceReloadListener {
        private Listener() {
            super(GSON, DIRECTORY);
        }

        @Override
        protected void apply(
                Map<ResourceLocation, JsonElement> resources,
                ResourceManager resourceManager,
                ProfilerFiller profiler
        ) {
            Map<ResourceLocation, Planet> loadedPlanets = new LinkedHashMap<>();
            resources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> Planet.CODEC.parse(JsonOps.INSTANCE, withoutTextureGenerationDetail(entry.getValue()))
                            .resultOrPartial(error -> LOGGER.error(
                                    "Failed to decode data-pack planet {}: {}",
                                    entry.getKey(),
                                    error
                            ))
                            .map(planet -> planet.withTextureGenerationDetail(
                                    textureGenerationDetail(entry.getKey(), entry.getValue())
                            ))
                            .ifPresent(planet -> loadedPlanets.put(entry.getKey(), planet)));
            PlanetRegistry.replaceDatapackPlanets(loadedPlanets);
            LOGGER.info("Loaded {} planets from data packs", loadedPlanets.size());
        }

        /** Reads the optional extension separately because Planet's legacy codec already has sixteen fields. */
        private static Planet.TextureGenerationDetail textureGenerationDetail(
                ResourceLocation resource,
                JsonElement definition
        ) {
            if (!definition.isJsonObject()) {
                return Planet.TextureGenerationDetail.SURFACE;
            }
            JsonElement value = definition.getAsJsonObject().get("textureGenerationDetail");
            if (value == null || !value.isJsonPrimitive()) {
                return Planet.TextureGenerationDetail.SURFACE;
            }
            try {
                return Planet.TextureGenerationDetail.valueOf(value.getAsString().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                LOGGER.error("Invalid textureGenerationDetail for data-pack planet {}: {}", resource, value);
                return Planet.TextureGenerationDetail.SURFACE;
            }
        }

        /** Removes the listener-owned extension before passing the unchanged legacy schema to its codec. */
        private static JsonElement withoutTextureGenerationDetail(JsonElement definition) {
            if (!definition.isJsonObject()) {
                return definition;
            }
            JsonObject copy = definition.getAsJsonObject().deepCopy();
            copy.remove("textureGenerationDetail");
            return copy;
        }
    }
}
