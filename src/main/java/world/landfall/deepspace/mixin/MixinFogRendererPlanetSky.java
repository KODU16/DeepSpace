package world.landfall.deepspace.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.client.PlanetSkyColor;

/** Keeps vanilla and Iris-captured fog colors aligned with DeepSpace planet skies. */
@Mixin(value = FogRenderer.class, priority = 1500)
public abstract class MixinFogRendererPlanetSky {
    @Shadow(remap = false)
    private static float fogRed;
    @Shadow(remap = false)
    private static float fogGreen;
    @Shadow(remap = false)
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("RETURN"), remap = false)
    private static void deepspace$usePlanetSkyColor(
            Camera camera,
            float partialTick,
            ClientLevel level,
            int renderDistance,
            float darkenWorldAmount,
            CallbackInfo callback
    ) {
        // Fluids keep their native fog; only open-air planet fog follows the timed atmosphere color.
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        var skyColor = PlanetSkyColor.current(level, partialTick);
        if (skyColor == null) {
            return;
        }
        fogRed = (float) skyColor.x;
        fogGreen = (float) skyColor.y;
        fogBlue = (float) skyColor.z;
        // Iris exposes fogColor from RenderSystem, not from the framebuffer clear color.
        RenderSystem.setShaderFogColor(fogRed, fogGreen, fogBlue);
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }
}
