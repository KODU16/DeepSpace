package world.landfall.deepspace.client.hud;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;

import java.util.List;

/** Supplies the standalone helmet HUD with DeepSpace planet data. */
public final class DeepSpaceHudBridge {
    private DeepSpaceHudBridge() {
    }

    public static List<Body> bodies(ResourceKey<Level> galaxy) {
        return PlanetRegistry.getAllPlanets().stream()
                .filter(planet -> galaxy.equals(planet.getGalaxy()))
                .map(DeepSpaceHudBridge::body)
                .toList();
    }

    private static Body body(Planet planet) {
        return new Body(planet.getName(), planet.getCenter(), planet.getModelBounds(),
                planet.isHyperRelay(), planet.getDiscovererName(),
                planet.getSampledBiomes().stream().limit(1).map(Planet.SurfaceSample::id).toList(),
                planet.getSampledFluids().stream().limit(5).map(Planet.SurfaceSample::id).toList(),
                planet.getSampledBlocks().stream().limit(5).map(Planet.SurfaceSample::id).toList(),
                planet.getSurfaceScanCompletedChunks(), planet.getSurfaceScanTotalChunks(),
                planet.getSurfaceScanStatus() == Planet.SurfaceScanStatus.COMPLETE,
                planet.getPlanetTypeBiome());
    }

    public record Body(String name, Vec3 center, AABB bounds, boolean hyperRelay, String discoverer,
                       List<String> biomes, List<String> fluids, List<String> blocks,
                       int scanCompletedChunks, int scanTotalChunks, boolean scanComplete, String planetType) {
    }

}
