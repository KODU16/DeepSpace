package world.landfall.deepspace.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

/** Uploads a generated image with a bounded mip chain so oblique sampling stays stable. */
final class MipmappedDynamicTexture extends AbstractTexture {
    private static final int MIP_LEVELS = 6;
    private final NativeImage image;

    MipmappedDynamicTexture(NativeImage image) {
        this.image = image;
    }

    @Override
    public void load(ResourceManager resourceManager) throws IOException {
        NativeImage[] levels = MipmapGenerator.generateMipLevels(new NativeImage[]{this.image}, MIP_LEVELS);
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
        this.setFilter(false, true);
    }
}
