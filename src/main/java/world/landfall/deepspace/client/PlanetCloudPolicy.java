package world.landfall.deepspace.client;

import net.minecraft.client.Minecraft;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Shares vacuum cloud suppression between vanilla geometry and procedural shader clouds. */
public final class PlanetCloudPolicy {
    private PlanetCloudPolicy() {
    }

    public static boolean suppressClouds() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        Planet planet = PlanetRegistry.getPlanetByDimension(level.dimension());
        return PlanetRegistry.getGalaxyByDimension(level.dimension()) != null
                || planet != null && !planet.hasAtmosphere();
    }
}
