package world.landfall.deepspace.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;

/** Loads a standalone texture with a bounded mip chain for stable distant sampling. */
final class MipmappedTexture extends AbstractTexture {
    private static final int MIP_LEVELS = 6;
    private final ResourceLocation location;

    MipmappedTexture(ResourceLocation location) {
        this.location = location;
    }

    @Override
    public void load(ResourceManager resourceManager) throws IOException {
        NativeImage baseImage;
        try (InputStream stream = resourceManager.getResourceOrThrow(this.location).open()) {
            baseImage = NativeImage.read(stream);
        }
        NativeImage[] levels = MipmapGenerator.generateMipLevels(
                new NativeImage[]{baseImage}, MIP_LEVELS
        );
        if (RenderSystem.isOnRenderThreadOrInit()) {
            upload(levels);
        } else {
            RenderSystem.recordRenderCall(() -> upload(levels));
        }
    }

    private void upload(NativeImage[] levels) {
        TextureUtil.prepareImage(this.getId(), levels.length - 1,
                levels[0].getWidth(), levels[0].getHeight());
        for (int level = 0; level < levels.length; level++) {
            NativeImage image = levels[level];
            image.upload(level, 0, 0, 0, 0,
                    image.getWidth(), image.getHeight(), true, false, true, true);
        }
        // Preserve close texels while mip interpolation stabilizes distant sampling.
        this.setFilter(false, true);
    }
}
