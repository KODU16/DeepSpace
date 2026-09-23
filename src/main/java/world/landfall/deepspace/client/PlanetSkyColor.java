package world.landfall.deepspace.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import world.landfall.deepspace.planet.GalaxyDimensions;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Resolves the sky color for the planet currently observed by the client. */
public final class PlanetSkyColor {
    private PlanetSkyColor() {
    }

    @Nullable
    public static Vec3 current() {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            return null;
        }
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        return current(level, partialTick);
    }

    @Nullable
    public static Vec3 current(ClientLevel level, float partialTick) {
        // Galaxy-space skies remain owned by their dedicated renderers.
        if (GalaxyDimensions.isGalaxy(level.dimension())) {
            return null;
        }
        Planet planet = PlanetRegistry.getPlanetByDimension(level.dimension());
        if (planet == null || !planet.hasAtmosphere()) {
            return null;
        }
        // The configured atmosphere is authoritative; do not replace it with vanilla daylight blue.
        return Vec3.fromRGB24(planet.getSkyColor());
    }
}
