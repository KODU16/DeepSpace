package world.landfall.deepspace.planet;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Paradise Probe score is visual color similarity to the Overworld, not block or fluid identity. */
public final class ParadiseRating {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final float GREEN_SHARE = 0.14F;
    private static final float WATER_SHARE = 0.08F;
    private static final float STRONG_GREEN_SHARE = 0.22F;
    private static final float ALIEN_SHARE_LIMIT = 0.28F;

    private ParadiseRating() {
    }

    public static Result evaluate(Planet planet) {
        return evaluate(planet, false);
    }

    /** blueOceanTexture is a measured visual tint, used only when the globe palette is still empty. */
    public static Result evaluate(Planet planet, boolean blueOceanTexture) {
        Planet.ParadiseProfile profile = planet.getParadiseProfile();
        PaletteShares palette = paletteShares(planet.getGeneratedSurfaceColors());
        boolean analog = isOverworldAnalog(planet);
        boolean hasPalette = palette.total() > 0;
        boolean greenLand = analog
                || hasPalette && palette.greenShare() >= GREEN_SHARE
                || !hasPalette && profile.grassSurface();
        boolean water = analog
                || hasPalette && palette.waterShare() >= WATER_SHARE
                || !hasPalette && (blueOceanTexture || profile.ocean());
        boolean blueWater = analog
                || hasPalette && palette.waterShare() >= WATER_SHARE
                || !hasPalette && (blueOceanTexture || profile.blueOcean());
        boolean atmosphere = analog || planet.hasAtmosphere() || profile.atmosphere();
        boolean blueSky = analog || atmosphere && isBlue(planet.getSkyColor()) || profile.blueSky();
        boolean strongGreen = analog
                || hasPalette && palette.greenShare() >= STRONG_GREEN_SHARE
                || !hasPalette && profile.preferredGrassColor();
        boolean earthlike = analog
                || greenLand && water && blueSky && (!hasPalette || palette.alienShare() <= ALIEN_SHARE_LIMIT)
                || !hasPalette && profile.dimensionFilter();
        return evaluate(new Criteria(
                greenLand,
                profile.biomeParticles(),
                analog || profile.hostileMobs(),
                strongGreen,
                earthlike,
                water,
                blueWater,
                atmosphere,
                blueSky
        ));
    }

    public static Result evaluate(Criteria criteria) {
        int score = criteria.grassSurface ? 28 : 0;
        score += criteria.biomeParticles ? 3 : 0;
        score += criteria.hostileMobs ? 3 : 0;
        score += criteria.atmosphere ? 14 : 0;
        score += criteria.atmosphere && criteria.blueSky ? 10 : 0;
        score += criteria.preferredGrassColor ? 6 : 0;
        score += criteria.dimensionFilter ? 10 : 0;
        score += criteria.ocean ? 16 : 0;
        score += criteria.ocean && criteria.blueOcean ? 10 : 0;
        return new Result(Math.min(100, score), grade(score));
    }

    /** 星藤只在自然算法生成的 Infinite S 级星球上成熟，主世界/Tropica 的类地高分不算栖息地。 */
    public static boolean supportsStarBramble(Planet planet) {
        return planet != null
                && isNaturalInfiniteGeneratedWorld(planet)
                && evaluate(planet).grade() == Grade.S;
    }

    /** Authored, datapack, primary-galaxy and analog worlds are never star-bramble habitats. */
    public static boolean isNaturalInfiniteGeneratedWorld(Planet planet) {
        return isNaturalInfiniteGeneratedWorld(
                planet.getDimension().location(),
                planet.getGalaxy().location(),
                planet.getId(),
                planet.isRingWorldEdge(),
                PlanetRegistry.isDatapackPlanet(planet.getId())
        );
    }

    public static boolean isNaturalInfiniteGeneratedWorld(
            ResourceLocation dimension,
            ResourceLocation galaxy,
            String planetId,
            boolean ringWorldEdge,
            boolean datapackPlanet
    ) {
        return isNaturalInfiniteGeneratedWorld(
                dimension.getNamespace(),
                dimension.getPath(),
                galaxy.getNamespace(),
                galaxy.getPath(),
                planetId,
                ringWorldEdge,
                datapackPlanet
        );
    }

    /** Pure identifier form used by lightweight contract tests and data-only callers. */
    public static boolean isNaturalInfiniteGeneratedWorld(
            String dimensionNamespace,
            String dimensionPath,
            String galaxyNamespace,
            String galaxyPath,
            String planetId,
            boolean ringWorldEdge,
            boolean datapackPlanet
    ) {
        boolean overworldAnalog = "minecraft".equals(dimensionNamespace)
                && "overworld".equals(dimensionPath)
                || "tropicraft".equals(dimensionNamespace) && dimensionPath.contains("tropic")
                || "tropica".equalsIgnoreCase(planetId);
        if (datapackPlanet || ringWorldEdge || overworldAnalog) {
            return false;
        }
        // Only Infinite Dimensions algorithm worlds use infinity:generated_<seed>.
        if (!"infinity".equals(dimensionNamespace) || !dimensionPath.matches("generated_[0-9]+")) {
            return false;
        }
        // The starting galaxy may host generated ring sections; those are still authored habitats.
        if ("deepspace".equals(galaxyNamespace) && "space".equals(galaxyPath)) {
            return false;
        }
        return true;
    }

    /** Reference worlds whose authored or tropical palettes match the Overworld look. */
    public static boolean isOverworldAnalog(Planet planet) {
        return isOverworldAnalog(planet.getDimension().location(), planet.getId());
    }

    public static boolean isOverworldAnalog(ResourceLocation dimension, String planetId) {
        if (dimension.equals(OVERWORLD)) {
            return true;
        }
        if ("tropicraft".equals(dimension.getNamespace()) && dimension.getPath().contains("tropic")) {
            return true;
        }
        return "tropica".equalsIgnoreCase(planetId);
    }

    public static PaletteShares paletteShares(List<Planet.SurfaceColor> colors) {
        long green = 0L;
        long water = 0L;
        long alien = 0L;
        long total = 0L;
        for (Planet.SurfaceColor color : colors) {
            int weight = color.weight();
            total += weight;
            int rgb = color.rgb();
            if (isAlienColor(rgb)) {
                alien += weight;
            } else if (isWaterColor(rgb)) {
                water += weight;
            } else if (isVegetationColor(rgb)) {
                green += weight;
            }
        }
        if (total <= 0L) {
            return new PaletteShares(0.0F, 0.0F, 0.0F, 0);
        }
        return new PaletteShares(green / (float) total, water / (float) total, alien / (float) total, total);
    }

    /** Plant-like greens on the globe; cyan is counted as water instead. */
    public static boolean isVegetationColor(int rgb) {
        int red = rgb >>> 16 & 0xFF;
        int green = rgb >>> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return green >= 55 && green >= red && green >= blue * 0.92F && green >= red * 1.05F;
    }

    /** Blue or tropical teal as it appears on the globe, regardless of the underlying fluid. */
    public static boolean isWaterColor(int rgb) {
        int red = rgb >>> 16 & 0xFF;
        int green = rgb >>> 8 & 0xFF;
        int blue = rgb & 0xFF;
        int chroma = Math.max(green, blue);
        if (chroma < 80 || red * 1.12F > chroma) {
            return false;
        }
        if (blue >= 96 && red >= 96 && green < blue * 0.72F && green < red * 0.72F) {
            return false;
        }
        return blue >= green * 0.88F;
    }

    public static boolean isAlienColor(int rgb) {
        int red = rgb >>> 16 & 0xFF;
        int green = rgb >>> 8 & 0xFF;
        int blue = rgb & 0xFF;
        boolean magenta = red >= 96 && blue >= 96 && green < red * 0.72F && green < blue * 0.72F;
        boolean nether = red >= 110 && green < red * 0.55F && blue < red * 0.45F;
        boolean end = blue >= 110 && red >= 70 && green < blue * 0.55F && green < red * 0.85F;
        return magenta || nether || end;
    }

    /** Green vegetation on the globe; not biome grass_color metadata. */
    public static boolean isPreferredGrassColor(int rgb) {
        return isVegetationColor(rgb);
    }

    public static boolean isBlue(int rgb) {
        int red = rgb >>> 16 & 0xFF;
        int green = rgb >>> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return blue >= 96 && blue >= red * 1.12 && blue >= green * 1.04;
    }

    public static Grade grade(int score) {
        if (score >= 90) return Grade.S;
        if (score >= 80) return Grade.A;
        if (score >= 70) return Grade.B;
        if (score >= 60) return Grade.C;
        if (score >= 50) return Grade.D;
        return Grade.E;
    }

    public record PaletteShares(float greenShare, float waterShare, float alienShare, long total) {
    }

    public record Result(int score, Grade grade) {
    }

    public record Criteria(
            boolean grassSurface,
            boolean biomeParticles,
            boolean hostileMobs,
            boolean preferredGrassColor,
            boolean dimensionFilter,
            boolean ocean,
            boolean blueOcean,
            boolean atmosphere,
            boolean blueSky
    ) {
    }

    public enum Grade {
        S(0xFFFFB52E),
        A(0xFFC879FF),
        B(0xFFFFE45C),
        C(0xFF66D979),
        D(0xFFFF8F8F),
        E(0xFFE53935);

        private final int color;

        Grade(int color) {
            this.color = color;
        }

        public int color() {
            return color;
        }
    }
}
