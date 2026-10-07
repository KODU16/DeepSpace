package world.landfall.deepspace.render;

import foundry.veil.api.client.render.MatrixStack;
import foundry.veil.api.event.VeilRenderLevelStageEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.RingWorldDimensions;
import world.landfall.deepspace.planet.Sun;

import java.util.List;

/**
 * Projects the complete four-segment ring into a surface sky without changing its shared
 * space geometry. The local segment fills gaps beyond loaded terrain, while the remote
 * segments continue from both horizons and meet behind the sun.
 */
public final class RingWorldSkyRenderer {
    // The planet-surface sky keeps the authored ring size regardless of the saved space multiplier.
    private static final float SKY_RADIUS_FRACTION = 0.85F;
    private static final float SKY_LATERAL_PERSPECTIVE_FRACTION = 0.70F;

    private RingWorldSkyRenderer() {
    }

    /** Exposes the sky-ring lateral compression so the surface star can match its visible width. */
    public static float skyLateralPerspectiveFraction() {
        return SKY_LATERAL_PERSPECTIVE_FRACTION;
    }

    public static void init() {
        // One opaque pass resolves overlap among every healthy and broken ring bone.
        SpaceRenderSystem.registerRenderer(
                RingWorldSkyRenderer::render,
                SpaceRenderSystem.BACKGROUND_STAGE
        );
    }

    public static void render(
            VeilRenderLevelStageEvent.Stage stage,
            LevelRenderer levelRenderer,
            net.minecraft.client.renderer.MultiBufferSource.BufferSource bufferSource,
            MatrixStack matrixStack,
            Matrix4fc frustumMatrix,
            Matrix4fc projectionMatrix,
            int renderTick,
            DeltaTracker partialTicks,
            Camera camera,
            Frustum frustum
    ) {
        var instance = Minecraft.getInstance();
        if (instance.level == null || stage != SpaceRenderSystem.BACKGROUND_STAGE) {
            return;
        }
        Planet observer = PlanetRegistry.getPlanetByDimension(instance.level.dimension());
        if (observer == null || !observer.isRingWorldEdge()) {
            return;
        }
        Galaxy galaxy = PlanetRegistry.getGalaxyByDimension(observer.getGalaxy());
        if (galaxy == null) {
            return;
        }
        Vec3 starCenter = galaxy.sun().getUnscaledCenter();
        int observerSection = RingWorldDimensions.nearestSectionIndex(
                observer.getUnscaledCenter().x, observer.getUnscaledCenter().z, starCenter.x, starCenter.z
        );
        Vec3 observerCenter = observer.getUnscaledCenter();
        Vec3 observerHalfSize = observer.getUnscaledBoundingBoxMax().subtract(observer.getUnscaledBoundingBoxMin()).scale(0.5D);
        Sun sun = galaxy.sun();
        List<Planet> ringEdges = PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                .filter(Planet::isRingWorldEdge)
                .toList();
        if (ringEdges.isEmpty() && galaxy.brokenRingSections() == 0) {
            return;
        }

        Vec3 physicalSunDirection = sun.getUnscaledCenter().subtract(observerCenter).normalize();
        Vector3f physicalSun = physicalSunDirection.toVector3f();
        // Use the exact same native/BSL celestial direction as the visible host star.
        float[] celestial = SunRenderer.ringWorldCelestialDirection();
        Vector3f celestialSun = new Vector3f(celestial[0], celestial[1], celestial[2]);
        Quaternionf physicalToSky = new Quaternionf().rotationTo(physicalSun, celestialSun);

        double inwardSurfaceDistance = RingWorldRenderGeometry.inwardSurfaceDistance(
                physicalSunDirection.x,
                physicalSunDirection.y,
                physicalSunDirection.z,
                observerHalfSize.x,
                observerHalfSize.y,
                observerHalfSize.z
        );
        Vec3 observerOrigin = observerCenter.add(physicalSunDirection.scale(
                RingWorldRenderGeometry.surfaceSkyObserverDistance(inwardSurfaceDistance, camera.getPosition().y)
        ));
        double farthestCorner = farthestRemoteCorner(observerOrigin, galaxy, observerSection);
        if (farthestCorner <= 1.0E-3D) {
            return;
        }
        float celestialDistance = celestialRenderDistance();
        float projectionScale = celestialDistance * SKY_RADIUS_FRACTION / (float) farthestCorner;
        float lateralProjectionScale = projectionScale * SKY_LATERAL_PERSPECTIVE_FRACTION;
        // Build from the same camera rotation matrix as vanilla sky, avoiding a screen-space event stack.
        PoseStack skyPoseStack = new PoseStack();
        skyPoseStack.mulPose(new Matrix4f(frustumMatrix));
        RingWorldRenderer.renderSkySegments(
                skyPoseStack,
                bufferSource,
                galaxy,
                observerOrigin,
                ringEdges,
                observerSection,
                physicalToSky,
                physicalSun,
                lateralProjectionScale,
                projectionScale
        );
    }

    /** Measures the full remote span so anisotropic sky scaling keeps shared endpoints aligned. */
    private static double farthestRemoteCorner(Vec3 origin, Galaxy galaxy, int observerSection) {
        double farthest = 0.0D;
        Vec3 starCenter = galaxy.sun().getUnscaledCenter();
        for (int index = 0; index < RingWorldDimensions.SECTION_COUNT; index++) {
            if (index == observerSection) {
                continue;
            }
            RingWorldDimensions.Bounds bounds = RingWorldDimensions.sectionBounds(index);
            Vec3 min = new Vec3(
                    starCenter.x + bounds.minX(),
                    starCenter.y + bounds.minY(),
                    starCenter.z + bounds.minZ()
            );
            Vec3 max = new Vec3(
                    starCenter.x + bounds.maxX(),
                    starCenter.y + bounds.maxY(),
                    starCenter.z + bounds.maxZ()
            );
            for (double x : new double[]{min.x, max.x}) {
                for (double y : new double[]{min.y, max.y}) {
                    for (double z : new double[]{min.z, max.z}) {
                        farthest = Math.max(farthest, origin.distanceTo(new Vec3(x, y, z)));
                    }
                }
            }
        }
        return farthest;
    }

    private static float celestialRenderDistance() {
        var gameRenderer = Minecraft.getInstance().gameRenderer;
        return gameRenderer == null ? 100.0F : Math.max(100.0F, gameRenderer.getDepthFar() * 0.9F);
    }
}
