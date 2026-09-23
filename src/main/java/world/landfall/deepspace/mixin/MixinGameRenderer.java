package world.landfall.deepspace.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.planet.PlanetRegistry;

@Mixin(value = GameRenderer.class)
@SuppressWarnings("MixinObfuscation")
public abstract class MixinGameRenderer {
    private static final float DEFAULT_NEAR_PLANE = 0.05F;
    private static final double RING_OBJ_LOCAL_RADIUS = 42.0;
    private static final double RING_OBJ_SLAB_HEIGHT = 7.0;
    private static final double RING_FAR_PLANE_MARGIN = 256.0;

    @Shadow(remap = false) private float zoom = 1.0F;
    @Shadow(remap = false) private float zoomX;
    @Shadow(remap = false) private float zoomY;
    @Final @Shadow(remap = false) Minecraft minecraft;
    @Inject(at = @At(value = "HEAD"), method = "getProjectionMatrix", remap = false, cancellable = true)
    protected void getProjectionMatrix(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        Matrix4f matrix4f = new Matrix4f();
        if (this.zoom != 1.0F) {
            matrix4f.translate(this.zoomX, -this.zoomY, 0.0F);
            matrix4f.scale(this.zoom, this.zoom, 1.0F);
        }

        cir.setReturnValue(matrix4f.perspective(
                (float)(fov * (float) (Math.PI / 180.0)),
                (float)this.minecraft.getWindow().getWidth() / (float)this.minecraft.getWindow().getHeight(),
                deepspace$requiredNearPlane(),
                deepspace$requiredFarPlane()
        ));
        cir.cancel();
    }

    /** Keeps ring-world rendering on the vanilla near plane; depth ordering already handles occlusion. */
    private float deepspace$requiredNearPlane() {
        return DEFAULT_NEAR_PLANE;
    }

    /** 保证投影远平面完整包住所有已提交的环世界边缘，避免模型被视野平面截断。 */
    private float deepspace$requiredFarPlane() {
        float requiredFar = this.getDepthFar() * 16.0F;
        if (this.minecraft.level == null) {
            return requiredFar;
        }
        var galaxy = PlanetRegistry.getGalaxyByDimension(this.minecraft.level.dimension());
        if (galaxy == null) {
            // Surface dimensions inherit their ring system's celestial bounds.
            var planet = PlanetRegistry.getPlanetByDimension(this.minecraft.level.dimension());
            if (planet != null) {
                galaxy = PlanetRegistry.getGalaxyByDimension(planet.getGalaxy());
            }
        }
        if (galaxy == null) {
            return requiredFar;
        }

        var cameraPosition = this.minecraft.gameRenderer.getMainCamera().getPosition();
        for (var planet : PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension())) {
            if (!planet.isRingWorldEdge()) {
                continue;
            }
            var size = planet.getBoundingBoxMax().subtract(planet.getBoundingBoxMin());
            double scale = size.y / RING_OBJ_SLAB_HEIGHT;
            double objRadius = scale * RING_OBJ_LOCAL_RADIUS;
            double surfaceRadius = size.length() * 0.5;
            double edgeRadius = Math.max(objRadius, surfaceRadius);
            double farEdge = cameraPosition.distanceTo(planet.getCenter())
                    + edgeRadius
                    + RING_FAR_PLANE_MARGIN;
            requiredFar = Math.max(requiredFar, (float) farEdge);
        }
        return requiredFar;
    }

    @Shadow(remap = false) public abstract float getDepthFar();
}
