package world.landfall.deepspace.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import world.landfall.deepspace.render.NightSkyPlanetRenderer;
import world.landfall.deepspace.render.SpaceSolarLighting;
import world.landfall.deepspace.render.SunRenderer;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererSun {
    /**
     * Keeps the chunk-independent solar point light alive while DeepSpace is rendered.
     */
    @Inject(method = "renderLevel", at = @At("HEAD"), remap = false)
    private void deepspace$maintainSpaceSunPointLight(
            DeltaTracker partialTicks,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        SpaceSolarLighting.update(Minecraft.getInstance().level, camera.getPosition());
    }

    /**
     * Replaces only the vanilla sun mesh; the DeepSpace model remains the sole visible sun.
     */
    @WrapOperation(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"
            ),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/renderer/LevelRenderer;SUN_LOCATION:Lnet/minecraft/resources/ResourceLocation;"
                    ),
                    to = @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"
                    )
            ),
            allow = 1
    )
    private void deepspace$replaceVanillaSun(MeshData meshData, Operation<Void> original) {
        if (SunRenderer.shouldReplaceVanillaSun()) {
            meshData.close();
            return;
        }
        original.call(meshData);
    }

    /**
     * Removes the vanilla moon on managed planet surfaces while preserving the phase clock used by night layouts.
     */
    @WrapOperation(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"
            ),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"
                    )
            ),
            allow = 1
    )
    private void deepspace$removeManagedMoon(MeshData meshData, Operation<Void> original) {
        if (NightSkyPlanetRenderer.shouldRemoveVanillaMoon()) {
            meshData.close();
            return;
        }
        original.call(meshData);
    }
}
