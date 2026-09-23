package world.landfall.deepspace.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/** Rejects generated noise settings containing a flat high-altitude terrain cap. */
final class InfinityTerrainValidation {
    private static final int HIGH_TERRAIN_Y = 256;

    private InfinityTerrainValidation() {
    }

    static boolean hasHighPlateau(MinecraftServer server, JsonObject settings, Object dimension) {
        JsonElement router = settings.get("noise_router");
        return router != null && containsHighFlatGradient(router);
    }

    private static boolean containsHighFlatGradient(JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) {
                if (containsHighFlatGradient(child)) {
                    return true;
                }
            }
            return false;
        }
        if (!value.isJsonObject()) {
            return false;
        }
        JsonObject object = value.getAsJsonObject();
        if ("minecraft:y_clamped_gradient".equals(stringValue(object, "type"))
                && reachesHighAltitude(object)
                && object.has("from_value")
                && object.has("to_value")
                && object.get("from_value").getAsDouble() == object.get("to_value").getAsDouble()) {
            return true;
        }
        for (var entry : object.entrySet()) {
            if (containsHighFlatGradient(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean reachesHighAltitude(JsonObject object) {
        return altitude(object, "from_y") >= HIGH_TERRAIN_Y
                || altitude(object, "to_y") >= HIGH_TERRAIN_Y;
    }

    private static int altitude(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsInt() : Integer.MIN_VALUE;
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
