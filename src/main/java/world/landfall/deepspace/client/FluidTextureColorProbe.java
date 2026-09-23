package world.landfall.deepspace.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import world.landfall.deepspace.planet.ParadiseRating;
import world.landfall.deepspace.planet.Planet;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Measures sampled ocean-fluid textures once and caches whether their tinted average is blue. */
final class FluidTextureColorProbe {
    private static final Map<ResourceLocation, Boolean> BLUE_CACHE = new HashMap<>();

    private FluidTextureColorProbe() {
    }

    static boolean hasBlueOceanTexture(Planet planet) {
        boolean measured = false;
        for (Planet.SurfaceSample sample : planet.getSampledFluids()) {
            ResourceLocation id = ResourceLocation.tryParse(sample.id());
            if (id == null || id.getPath().equals("empty")) {
                continue;
            }
            measured = true;
            if (BLUE_CACHE.computeIfAbsent(id, FluidTextureColorProbe::measureFluid)) {
                return true;
            }
        }
        return !measured && planet.getParadiseProfile().blueOcean();
    }

    private static boolean measureFluid(ResourceLocation fluidId) {
        Fluid fluid = BuiltInRegistries.FLUID.get(fluidId);
        if (fluid == null) {
            return false;
        }
        IClientFluidTypeExtensions properties = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation sprite = properties.getStillTexture();
        if (sprite == null) {
            return false;
        }
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                sprite.getNamespace(), "textures/" + sprite.getPath() + ".png"
        );
        try (var input = Minecraft.getInstance().getResourceManager().open(texture);
             NativeImage image = NativeImage.read(input)) {
            long red = 0L;
            long green = 0L;
            long blue = 0L;
            long count = 0L;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    if ((abgr >>> 24 & 0xFF) < 16) {
                        continue;
                    }
                    red += abgr & 0xFF;
                    green += abgr >>> 8 & 0xFF;
                    blue += abgr >>> 16 & 0xFF;
                    count++;
                }
            }
            if (count == 0L) {
                return false;
            }
            int tint = properties.getTintColor();
            int average = ((int) (red / count) * (tint >>> 16 & 0xFF) / 255) << 16
                    | ((int) (green / count) * (tint >>> 8 & 0xFF) / 255) << 8
                    | (int) (blue / count) * (tint & 0xFF) / 255;
            return ParadiseRating.isBlue(average);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }
}
