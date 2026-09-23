package world.landfall.deepspace.render.shapes;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import foundry.veil.api.client.color.Color;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.*;
import org.slf4j.Logger;
import world.landfall.deepspace.render.CelestialRenderDiagnostics;
import world.landfall.deepspace.render.SunRenderMath;

import java.lang.Math;
import java.util.LinkedList;

public class Cube implements DeepSpaceRenderable {
    private static final Logger LOGGER = LogUtils.getLogger();
    // BSL treats maximum block light as warm torch light; celestial meshes need neutral sky light only.
    private static final int CELESTIAL_SKY_LIGHT = LightTexture.pack(0, 15);
    private LinkedList<Triangle> TRIANGLES = new LinkedList<>();
    public final Vector3f center;
    private final boolean weirdNormals;
    private final boolean unwrapped;
    public final float radius;

    private final boolean[][] unwrappedMap = {
            {false, true, false, false},
            {true , true, true , true},
            {false, true, false, false},
    };


    public Cube(Vector3f _corner1, Vector3f _corner2, float scale, boolean weirdNormals, boolean unwrapped) {
        this.weirdNormals = weirdNormals;
        this.unwrapped = unwrapped;
        var corner1 = new Vector3f(
                _corner1.x,
                _corner1.y,
                _corner1.z
        );
        var corner2 = new Vector3f(
                _corner2.x,
                _corner2.y,
                _corner2.z
        );
        //corner1.mul(2);
        //corner2.mul(2);
        Vector3f center = new Vector3f(corner1).add(corner2).div(2);
        this.center = center;
        Vector3f diff = new Vector3f(corner1).sub(corner2).div(2);
        this.radius = Math.abs(diff.x);
        Quaternionf[] rotations = new Quaternionf[] {
                new Quaternionf().rotateLocalX(-(float)Math.PI/2),
                new Quaternionf(),
                new Quaternionf().rotateLocalY((float)Math.PI/2),
                new Quaternionf().rotateLocalY((float)Math.PI),
                new Quaternionf().rotateLocalY((float)Math.PI*1.5f),
                new Quaternionf().rotateLocalX((float)Math.PI/2),
        };
        diff.mul(scale);



        int faceind = 0;
        for (var x : rotations) {
            int xInd = -1, yInd = -1;
            int counter = 0;
            for (int i = 0; i < unwrappedMap.length; i++) {
                for (int j = 0; j < unwrappedMap[i].length; j++) {
                    if (unwrappedMap[i][j]) {
                        if (counter++ == faceind) {
                            xInd = i;
                            yInd = j;
                        }
                    }
                }
            }



            float[][] UVs = unwrapped && xInd > -1 ?
                    new float[][] {
                            {
                                    xInd * .25f, xInd * .25f + .25f
                            },
                            {
                                    yInd * .25f, yInd * .25f + .25f
                            },
                    }:
                    new float[][] {{0, 1}, {0, 1}};
            Vector3f[] vertexes = new Vector3f[] {
                    new Vector3f(diff.x, diff.y, diff.z),
                    new Vector3f(diff.x, -diff.y, diff.z),
                    new Vector3f(-diff.x, diff.y, diff.z),
                    new Vector3f(diff.x, -diff.y, diff.z),
                    new Vector3f(-diff.x, diff.y, diff.z),
                    new Vector3f(-diff.x, -diff.y, diff.z)
            };
            var triangle1 = new Triangle(
                    new Vector3f[] {
                            vertexes[0].rotate(x).add(center),
                            vertexes[1].rotate(x).add(center),
                            vertexes[2].rotate(x).add(center)
                    },
                    new Vector2f[] {
                            new Vector2f(UVs[0][0], UVs[1][0]),
                            new Vector2f(UVs[0][0], UVs[1][1]),
                            new Vector2f(UVs[0][1], UVs[1][0])
                    },
                    new Vector3f[] {
                            new Vector3f(0, 0, -1).rotate(x),
                            new Vector3f(0, 0, -1).rotate(x),
                            new Vector3f(0, 0, -1).rotate(x)
                    }
            );
            var triangle2 = new Triangle(
                    new Vector3f[] {
                            vertexes[4].rotate(x).add(center),
                            vertexes[3].rotate(x).add(center),
                            vertexes[5].rotate(x).add(center)
                    },
                    new Vector2f[] {
                            new Vector2f(UVs[0][1], UVs[1][0]),
                            new Vector2f(UVs[0][0], UVs[1][1]),
                            new Vector2f(UVs[0][1], UVs[1][1])
                    },
                    new Vector3f[] {
                            new Vector3f(0, 0, -1).rotate(x),
                            new Vector3f(0, 0, -1).rotate(x),
                            new Vector3f(0, 0, -1).rotate(x)
                }
            );
            TRIANGLES.add(triangle1);
            TRIANGLES.add(triangle2);
            faceind++;
        }
    }
    public Cube(Vector3f _corner1, Vector3f _corner2, float scale, boolean weirdNormals) {
        this(_corner1, _corner2, scale, weirdNormals, false);


    }
    @Override
    public void render(PoseStack stack, VertexConsumer consumer, Vector3fc dimensions, Quaternionf rotation) {
        renderTriangles(stack, consumer, dimensions, rotation, 0, TRIANGLES.size(), Color.WHITE.argb(), false, null);
    }

    /**
     * Renders the cube exterior with outward-facing winding and normals.
     */
    public void renderOutward(PoseStack stack, VertexConsumer consumer, Vector3fc dimensions, Quaternionf rotation) {
        renderTriangles(stack, consumer, dimensions, rotation, 0, TRIANGLES.size(), Color.WHITE.argb(), true, null);
    }

    /**
     * Applies only a restrained per-face brightness change for distant Iris planets.
     */
    public void renderSolarTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            Vector3fc lightDirection
    ) {
        renderTriangles(
                stack, consumer, dimensions, rotation,
                0, TRIANGLES.size(), Color.WHITE.argb(), false, lightDirection
        );
    }

    /**
     * Renders a solar-tinted cube exterior for culled planet surfaces.
     */
    public void renderOutwardSolarTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            Vector3fc lightDirection
    ) {
        renderTriangles(
                stack, consumer, dimensions, rotation,
                0, TRIANGLES.size(), Color.WHITE.argb(), true, lightDirection
        );
    }

    /**
     * Uses radial normals and per-vertex light so the Iris terminator crosses cube faces smoothly.
     */
    public void renderOutwardSolarGradient(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            Vector3fc lightDirection
    ) {
        renderTriangles(
                stack, consumer, dimensions, rotation,
                0, TRIANGLES.size(), Color.WHITE.argb(), true, lightDirection, true
        );
    }

    /**
     * Stores tint and alpha in each vertex so shader packs cannot discard decoration transparency.
     */
    public void renderTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb
    ) {
        renderTriangles(stack, consumer, dimensions, rotation, 0, TRIANGLES.size(), argb, false, null);
    }

    /**
     * Stores the configured tint while applying a lightweight solar fallback for Iris.
     */
    public void renderTintedSolar(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb,
            Vector3fc lightDirection
    ) {
        for (int face = 0; face < 6; face++) {
            int firstTriangle = face * 2;
            int faceColor = atmosphereSolarTint(
                    argb,
                    TRIANGLES.get(firstTriangle).normals[0],
                    rotation,
                    lightDirection
            );
            renderTriangles(
                    stack, consumer, dimensions, rotation,
                    firstTriangle, firstTriangle + 2, faceColor, false, null
            );
        }
    }

    /**
     * Draws only a fading border for Iris, whose replacement shader cannot run the analytical rim effect.
     */
    public void renderAtmosphereHalo(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb,
            Vector3fc lightDirection
    ) {
        renderAtmosphereHalo(stack, consumer, dimensions, rotation, argb, lightDirection, false);
    }

    /**
     * Emits the same silhouette using the BLOCK format required by Iris's beacon bloom program.
     */
    public void renderAtmosphereBloomHalo(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb,
            Vector3fc lightDirection,
            float atmosphereScale,
            String planetId
    ) {
        renderExteriorAtmosphereFade(
                stack,
                consumer,
                dimensions,
                rotation,
                argb,
                lightDirection,
                atmosphereScale,
                planetId
        );
    }

    /**
     * Starts at the planet surface and fades across the larger atmosphere shell toward space.
     */
    private void renderExteriorAtmosphereFade(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb,
            Vector3fc lightDirection,
            float atmosphereScale,
            String planetId
    ) {
        if (atmosphereScale <= 1.0F) {
            return;
        }

        Vector3f cameraPosition = new Vector3f(dimensions).negate();
        Vector3f[][] faces = new Vector3f[6][4];
        Vector3f[] normals = new Vector3f[6];
        boolean[] frontFacing = new boolean[6];
        for (int face = 0; face < 6; face++) {
            Triangle first = TRIANGLES.get(face * 2);
            Triangle second = TRIANGLES.get(face * 2 + 1);
            faces[face] = new Vector3f[]{
                    new Vector3f(first.vertexes[0]),
                    new Vector3f(first.vertexes[1]),
                    new Vector3f(second.vertexes[2]),
                    new Vector3f(first.vertexes[2])
            };
            Vector3f faceCenter = average(faces[face]);
            normals[face] = new Vector3f(first.normals[0]).rotate(rotation).normalize();
            Vector3f toCamera = cameraPosition.sub(faceCenter, new Vector3f()).rotate(rotation).normalize();
            frontFacing[face] = normals[face].dot(toCamera) > 0.0F;
        }

        float surfaceRatio = 1.0F / atmosphereScale;
        // Keep a dim value at the former boundary, then spend the added outer band fading it to zero.
        float existingShellFraction = 0.60F;
        float farAbsoluteScale = 1.0F + (atmosphereScale - 1.0F) / existingShellFraction;
        float farOuterRatio = farAbsoluteScale / atmosphereScale;
        for (int face = 0; face < faces.length; face++) {
            if (!frontFacing[face]) {
                continue;
            }
            Vector3f[] outer = faces[face];
            Vector3f normal = normals[face];
            for (int edge = 0; edge < outer.length; edge++) {
                if (!isSilhouetteEdge(face, edge, faces, frontFacing)) {
                    continue;
                }
                int next = (edge + 1) % outer.length;
                Vector3f surfaceStart = scaleFromCenter(outer[edge], surfaceRatio);
                Vector3f surfaceEnd = scaleFromCenter(outer[next], surfaceRatio);
                Vector3f middleStart = new Vector3f(surfaceStart).lerp(outer[edge], 0.45F);
                Vector3f middleEnd = new Vector3f(surfaceEnd).lerp(outer[next], 0.45F);
                Vector3f farOuterStart = scaleFromCenter(outer[edge], farOuterRatio);
                Vector3f farOuterEnd = scaleFromCenter(outer[next], farOuterRatio);

                int surfaceStartColor = atmosphereSurfaceColor(planetId, argb, surfaceStart, rotation, lightDirection, 0.0F);
                int surfaceEndColor = atmosphereSurfaceColor(planetId, argb, surfaceEnd, rotation, lightDirection, 0.0F);
                int middleStartColor = atmosphereSurfaceColor(planetId, argb, surfaceStart, rotation, lightDirection, 0.27F);
                int middleEndColor = atmosphereSurfaceColor(planetId, argb, surfaceEnd, rotation, lightDirection, 0.27F);
                int outerStartColor = atmosphereSurfaceColor(planetId, argb, surfaceStart, rotation, lightDirection, 0.60F);
                int outerEndColor = atmosphereSurfaceColor(planetId, argb, surfaceEnd, rotation, lightDirection, 0.60F);
                int transparentColor = withAlpha(argb, 0);

                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, false,
                        outer[edge], outerStartColor, outer[next], outerEndColor,
                        middleStart, middleStartColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, false,
                        outer[next], outerEndColor, middleEnd, middleEndColor,
                        middleStart, middleStartColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, false,
                        middleStart, middleStartColor, middleEnd, middleEndColor,
                        surfaceStart, surfaceStartColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, false,
                        middleEnd, middleEndColor, surfaceEnd, surfaceEndColor,
                        surfaceStart, surfaceStartColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, false,
                        farOuterStart, transparentColor, farOuterEnd, transparentColor,
                        outer[edge], outerStartColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, false,
                        farOuterEnd, transparentColor, outer[next], outerEndColor,
                        outer[edge], outerStartColor);
            }
        }
    }

    private Vector3f scaleFromCenter(Vector3fc point, float scale) {
        return new Vector3f(point).sub(center).mul(scale).add(center);
    }

    private int atmosphereSurfaceColor(
            String planetId,
            int argb,
            Vector3fc surfacePoint,
            Quaternionf rotation,
            Vector3fc lightDirection,
            float outwardFraction
    ) {
        Vector3f radialNormal = new Vector3f(surfacePoint).sub(center).normalize().rotate(rotation);
        float lightDotNormal = radialNormal.dot(lightDirection);
        float surfaceBrightness = SunRenderMath.planetSurfaceBrightness(lightDotNormal);
        float emissiveBrightness = SunRenderMath.atmosphereEmissiveBrightness(lightDotNormal);
        int vertexColor = SunRenderMath.atmosphereVertexColor(argb, lightDotNormal, outwardFraction);
        CelestialRenderDiagnostics.recordAtmosphereLighting(
                planetId,
                lightDotNormal,
                surfaceBrightness,
                emissiveBrightness,
                outwardFraction,
                argb,
                vertexColor,
                lightDirection
        );
        return vertexColor;
    }

    private void renderAtmosphereHalo(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb,
            Vector3fc lightDirection,
            boolean blockFormat
    ) {
        Vector3f cameraPosition = new Vector3f(dimensions).negate();
        Vector3f[][] faces = new Vector3f[6][4];
        Vector3f[] normals = new Vector3f[6];
        boolean[] frontFacing = new boolean[6];

        for (int face = 0; face < 6; face++) {
            Triangle first = TRIANGLES.get(face * 2);
            Triangle second = TRIANGLES.get(face * 2 + 1);
            faces[face] = new Vector3f[]{
                    new Vector3f(first.vertexes[0]),
                    new Vector3f(first.vertexes[1]),
                    new Vector3f(second.vertexes[2]),
                    new Vector3f(first.vertexes[2])
            };
            Vector3f faceCenter = average(faces[face]);
            normals[face] = new Vector3f(first.normals[0]).rotate(rotation).normalize();
            Vector3f toCamera = cameraPosition.sub(faceCenter, new Vector3f()).rotate(rotation).normalize();
            frontFacing[face] = normals[face].dot(toCamera) > 0.0F;
        }

        for (int face = 0; face < 6; face++) {
            if (!frontFacing[face]) {
                continue;
            }
            Triangle first = TRIANGLES.get(face * 2);
            Vector3f[] outer = faces[face];
            Vector3f faceCenter = average(outer);
            Vector3f normal = normals[face];
            Vector3f toCamera = cameraPosition.sub(faceCenter, new Vector3f()).rotate(rotation).normalize();
            float rim = 1.0F - Math.abs(normal.dot(toCamera));
            float diffuse = Math.max(0.0F, normal.dot(lightDirection));
            float rimFactor = (float) Math.pow(Math.max(0.0F, rim), 1.5F);
            int outerAlpha = Math.round((argb >>> 24)
                    * (0.35F + rimFactor * 0.65F)
                    * (0.55F + diffuse * 0.45F));
            // Iris/BSL lights the emissive halo; CPU lighting only shapes its opacity.
            int faceColor = scaleRgb(argb, blockFormat ? 1.0F : 0.72F);
            int peakColor = withAlpha(faceColor, outerAlpha);
            int transparentColor = withAlpha(faceColor, 0);

            Vector3f[] peak = new Vector3f[outer.length];
            Vector3f[] inner = new Vector3f[outer.length];
            for (int index = 0; index < outer.length; index++) {
                peak[index] = new Vector3f(outer[index]).lerp(faceCenter, 0.035F);
                inner[index] = new Vector3f(outer[index]).lerp(faceCenter, 0.10F);
            }
            for (int edge = 0; edge < outer.length; edge++) {
                if (!isSilhouetteEdge(face, edge, faces, frontFacing)) {
                    continue;
                }
                int next = (edge + 1) % outer.length;
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, blockFormat,
                        outer[edge], transparentColor, outer[next], transparentColor, peak[edge], peakColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, blockFormat,
                        outer[next], transparentColor, peak[next], peakColor, peak[edge], peakColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, blockFormat,
                        peak[edge], peakColor, peak[next], peakColor, inner[edge], transparentColor);
                emitHaloTriangle(stack, consumer, dimensions, rotation, normal, blockFormat,
                        peak[next], peakColor, inner[next], transparentColor, inner[edge], transparentColor);
            }
        }
    }

    private static Vector3f average(Vector3f[] vertices) {
        Vector3f center = new Vector3f();
        for (Vector3f vertex : vertices) {
            center.add(vertex);
        }
        return center.div(vertices.length);
    }

    private static boolean isSilhouetteEdge(
            int face,
            int edge,
            Vector3f[][] faces,
            boolean[] frontFacing
    ) {
        Vector3f start = faces[face][edge];
        Vector3f end = faces[face][(edge + 1) % 4];
        for (int otherFace = 0; otherFace < faces.length; otherFace++) {
            if (otherFace == face) {
                continue;
            }
            for (int otherEdge = 0; otherEdge < 4; otherEdge++) {
                Vector3f otherStart = faces[otherFace][otherEdge];
                Vector3f otherEnd = faces[otherFace][(otherEdge + 1) % 4];
                if (sameEdge(start, end, otherStart, otherEnd)) {
                    return !frontFacing[otherFace];
                }
            }
        }
        return true;
    }

    private static boolean sameEdge(Vector3fc firstStart, Vector3fc firstEnd, Vector3fc secondStart, Vector3fc secondEnd) {
        return samePoint(firstStart, secondStart) && samePoint(firstEnd, secondEnd)
                || samePoint(firstStart, secondEnd) && samePoint(firstEnd, secondStart);
    }

    private static boolean samePoint(Vector3fc first, Vector3fc second) {
        return first.distanceSquared(second) < 0.01F;
    }

    private static void emitHaloTriangle(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            Vector3fc normal,
            boolean blockFormat,
            Vector3fc first,
            int firstColor,
            Vector3fc second,
            int secondColor,
            Vector3fc third,
            int thirdColor
    ) {
        emitHaloVertex(stack, consumer, dimensions, rotation, normal, blockFormat, first, firstColor);
        emitHaloVertex(stack, consumer, dimensions, rotation, normal, blockFormat, second, secondColor);
        emitHaloVertex(stack, consumer, dimensions, rotation, normal, blockFormat, third, thirdColor);
    }

    private static void emitHaloVertex(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            Vector3fc normal,
            boolean blockFormat,
            Vector3fc source,
            int color
    ) {
        Vector3f vertex = new Vector3f(source).add(dimensions).rotate(rotation);
        Vector3f transformedNormal = new Vector3f(normal);
        // Halo vertices share the same world-to-camera transform as the planet surface.
        stack.last().pose().transformPosition(vertex);
        stack.last().normal().transform(transformedNormal).normalize();
        if (blockFormat) {
            consumer.addVertex(vertex.x, vertex.y, vertex.z)
                    .setColor(color)
                    .setUv(0.0F, 0.0F)
                    .setLight(CELESTIAL_SKY_LIGHT)
                    .setNormal(transformedNormal.x, transformedNormal.y, transformedNormal.z);
        } else {
            consumer.addVertex(vertex.x, vertex.y, vertex.z,
                    color, 0.0F, 0.0F,
                    OverlayTexture.NO_OVERLAY, CELESTIAL_SKY_LIGHT,
                    transformedNormal.x, transformedNormal.y, transformedNormal.z);
        }
    }

    private static int withAlpha(int argb, int alpha) {
        return Math.max(0, Math.min(255, alpha)) << 24 | argb & 0x00FFFFFF;
    }

    private static int scaleRgb(int argb, float scale) {
        int red = Math.round(((argb >>> 16) & 0xFF) * scale);
        int green = Math.round(((argb >>> 8) & 0xFF) * scale);
        int blue = Math.round((argb & 0xFF) * scale);
        return argb & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * Uses the stored exterior winding so culling keeps the near faces visible.
     */
    public void renderOutwardTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb
    ) {
        renderTriangles(stack, consumer, dimensions, rotation, 0, TRIANGLES.size(), argb, true, null);
    }

    /** Renders an outward-tinted cube at full light without changing its geometry scale. */
    public void renderOutwardTintedFullBright(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb
    ) {
        renderTrianglesFullBright(
                stack, consumer, dimensions, rotation, 0, TRIANGLES.size(), argb, true, 1.0F
        );
    }

    /** Emits one full-bright exterior face so distant planets can bind six different surface tiles. */
    public void renderFaceOutwardTintedFullBright(
            PoseStack stack, VertexConsumer consumer, Vector3fc dimensions, Quaternionf rotation,
            int faceIndex, int argb
    ) {
        validateFaceIndex(faceIndex);
        int firstTriangle = faceIndex * 2;
        renderTrianglesFullBright(stack, consumer, dimensions, rotation,
                firstTriangle, firstTriangle + 2, argb, true, 1.0F);
    }

    /** Like renderOutwardTinted, but uses full bright light so Iris/BSL cannot dim celestial meshes. */
    public void renderOutwardTintedFullBright(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb,
            float geometryScale
    ) {
        renderTrianglesFullBright(
                stack, consumer, dimensions, rotation, 0, TRIANGLES.size(), argb, true, geometryScale
        );
    }

    private void renderTrianglesFullBright(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int firstTriangle,
            int endTriangle,
            int argb,
            boolean outward,
            float geometryScale
    ) {
        for (int triangleIndex = firstTriangle; triangleIndex < endTriangle; triangleIndex++) {
            Triangle triangle = TRIANGLES.get(triangleIndex);
            for (int i = 0; i < 3; i++) {
                int vertexIndex = outward ? CubeExteriorWinding.vertexIndex(i) : i;
                Vector3f oldVertex = triangle.vertexes[vertexIndex];
                Vector3f vertex = new Vector3f(oldVertex.x, oldVertex.y, oldVertex.z);
                Vector3f normal = new Vector3f(triangle.normals[vertexIndex]);
                vertex.sub(this.center).mul(geometryScale).add(this.center);
                vertex.add(dimensions);
                vertex.rotate(rotation);
                if (outward && !weirdNormals) {
                    normal.mul(CubeExteriorWinding.flatNormalSign());
                }
                // Full-bright celestial vertices still need the caller's camera-space pose.
                stack.last().pose().transformPosition(vertex);
                stack.last().normal().transform(normal).normalize();
                Vector2f uv = triangle.UV[vertexIndex];
                consumer.addVertex(vertex.x, vertex.y, vertex.z,
                        argb,
                        uv.x, uv.y,
                        OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT,
                        normal.x, normal.y, normal.z
                );
            }
        }
    }

    /** Renders one exterior face with the entity format used by textured celestial GUI draws. */
    public void renderGuiFaceOutward(
            PoseStack stack,
            VertexConsumer consumer,
            Quaternionf rotation,
            int faceIndex,
            int argb
    ) {
        if (faceIndex < 0 || faceIndex >= 6) {
            throw new IllegalArgumentException("Cube face index must be between 0 and 5");
        }
        Matrix4f pose = stack.last().pose();
        int firstTriangle = faceIndex * 2;
        for (int triangleIndex = firstTriangle; triangleIndex < firstTriangle + 2; triangleIndex++) {
            Triangle triangle = TRIANGLES.get(triangleIndex);
            for (int i = 0; i < 3; i++) {
                int vertexIndex = CubeExteriorWinding.vertexIndex(i);
                Vector3f vertex = new Vector3f(triangle.vertexes[vertexIndex]).rotate(rotation);
                Vector3f normal = new Vector3f(triangle.normals[vertexIndex])
                        .mul(CubeExteriorWinding.flatNormalSign())
                        .rotate(rotation);
                pose.transformPosition(vertex);
                stack.last().normal().transform(normal).normalize();
                Vector2f uv = triangle.UV[vertexIndex];
                consumer.addVertex(vertex.x, vertex.y, vertex.z)
                        .setColor(argb)
                        .setUv(uv.x, uv.y)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(normal.x, normal.y, normal.z);
            }
        }
    }

    /**
     * Renders one face in down, north, west, south, east, up order.
     */
    public void renderFace(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int faceIndex
    ) {
        if (faceIndex < 0 || faceIndex >= 6) {
            throw new IllegalArgumentException("Cube face index must be between 0 and 5");
        }
        int firstTriangle = faceIndex * 2;
        renderTriangles(
                stack,
                consumer,
                dimensions,
                rotation,
                firstTriangle,
                firstTriangle + 2,
                Color.WHITE.argb(),
                false,
                null
        );
    }

    /**
     * Renders one outward-facing cube face.
     */
    public void renderFaceOutward(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int faceIndex
    ) {
        validateFaceIndex(faceIndex);
        int firstTriangle = faceIndex * 2;
        renderTriangles(
                stack,
                consumer,
                dimensions,
                rotation,
                firstTriangle,
                firstTriangle + 2,
                Color.WHITE.argb(),
                true,
                null
        );
    }

    /**
     * Renders one Iris planet face with a subtle, texture-preserving solar tint.
     */
    public void renderFaceSolarTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int faceIndex,
            Vector3fc lightDirection
    ) {
        if (faceIndex < 0 || faceIndex >= 6) {
            throw new IllegalArgumentException("Cube face index must be between 0 and 5");
        }
        int firstTriangle = faceIndex * 2;
        renderTriangles(
                stack, consumer, dimensions, rotation,
                firstTriangle, firstTriangle + 2, Color.WHITE.argb(), false, lightDirection
        );
    }

    /**
     * Renders one solar-tinted outward face for an Iris planet.
     */
    public void renderFaceOutwardSolarTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int faceIndex,
            Vector3fc lightDirection
    ) {
        validateFaceIndex(faceIndex);
        int firstTriangle = faceIndex * 2;
        renderTriangles(
                stack, consumer, dimensions, rotation,
                firstTriangle, firstTriangle + 2, Color.WHITE.argb(), true, lightDirection
        );
    }

    /**
     * Renders one Iris face with the same radial day-night gradient as adjacent faces.
     */
    public void renderFaceOutwardSolarGradient(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int faceIndex,
            Vector3fc lightDirection
    ) {
        validateFaceIndex(faceIndex);
        int firstTriangle = faceIndex * 2;
        renderTriangles(
                stack, consumer, dimensions, rotation,
                firstTriangle, firstTriangle + 2, Color.WHITE.argb(), true, lightDirection, true
        );
    }

    private static void validateFaceIndex(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= 6) {
            throw new IllegalArgumentException("Cube face index must be between 0 and 5");
        }
    }

    private void renderTriangles(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int firstTriangle,
            int endTriangle,
            int argb,
            boolean outward,
            Vector3fc lightDirection
    ) {
        renderTriangles(
                stack, consumer, dimensions, rotation,
                firstTriangle, endTriangle, argb, outward, lightDirection, false
        );
    }

    private void renderTriangles(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int firstTriangle,
            int endTriangle,
            int argb,
            boolean outward,
            Vector3fc lightDirection,
            boolean solarGradient
    ) {
        for (int triangleIndex = firstTriangle; triangleIndex < endTriangle; triangleIndex++) {
            var triangle = TRIANGLES.get(triangleIndex);
            int triangleArgb = lightDirection == null || solarGradient
                    ? argb
                    : solarTint(argb, triangle.normals[0], rotation, lightDirection, outward);
            for (int i = 0; i < 3; i++) {
                int vertexIndex = outward ? CubeExteriorWinding.vertexIndex(i) : i;
                var oldVertex = triangle.vertexes[vertexIndex];
                var vertex = new Vector3f(oldVertex.x, oldVertex.y, oldVertex.z);
                Vector3f normal;
                if (weirdNormals || solarGradient) {
                    // Radial normals must be computed before the camera translation is applied.
                    normal = new Vector3f(oldVertex).sub(center).normalize().rotate(rotation);
                } else {
                    normal = new Vector3f(triangle.normals[vertexIndex]);
                }
                vertex.add(dimensions);
                vertex.rotate(rotation);

                var UV = triangle.UV[vertexIndex];
                if (outward && !weirdNormals && !solarGradient) {
                    normal.mul(CubeExteriorWinding.flatNormalSign());
                }
                int vertexArgb = solarGradient
                        ? solarGradientTint(argb, normal, lightDirection)
                        : triangleArgb;
                // Apply the caller's world-to-camera transform before the buffered draw is submitted.
                stack.last().pose().transformPosition(vertex);
                stack.last().normal().transform(normal).normalize();
                // Sky-only light keeps celestial textures bright without BSL's yellow block-light contribution.
                consumer.addVertex(vertex.x, vertex.y, vertex.z,
                        vertexArgb,
                        UV.x, UV.y,
                        OverlayTexture.NO_OVERLAY, CELESTIAL_SKY_LIGHT,
                        normal.x, normal.y, normal.z
                );
            }
        }
    }

    private static int solarGradientTint(int argb, Vector3fc normal, Vector3fc lightDirection) {
        float brightness = solarGradientBrightness(normal.dot(lightDirection));
        int alpha = argb >>> 24;
        int red = Math.round(((argb >>> 16) & 0xFF) * brightness);
        int green = Math.round(((argb >>> 8) & 0xFF) * brightness);
        int blue = Math.round((argb & 0xFF) * brightness);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static float solarGradientBrightness(float diffuse) {
        return SunRenderMath.planetSurfaceBrightness(diffuse);
    }

    private static int solarTint(
            int argb,
            Vector3fc sourceNormal,
            Quaternionf rotation,
            Vector3fc lightDirection,
            boolean outward
    ) {
        Vector3f normal = new Vector3f(sourceNormal).rotate(rotation).normalize();
        if (outward) {
            normal.mul(CubeExteriorWinding.flatNormalSign());
        }
        float diffuse = Math.max(0.0F, normal.dot(lightDirection));
        float brightness = 0.90F + diffuse * 0.10F;
        int alpha = argb >>> 24;
        int red = Math.round(((argb >>> 16) & 0xFF) * brightness);
        int green = Math.round(((argb >>> 8) & 0xFF) * brightness);
        int blue = Math.round((argb & 0xFF) * brightness);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int atmosphereSolarTint(
            int argb,
            Vector3fc sourceNormal,
            Quaternionf rotation,
            Vector3fc lightDirection
    ) {
        Vector3f normal = new Vector3f(sourceNormal).rotate(rotation).normalize();
        float diffuse = Math.max(0.0F, normal.dot(lightDirection));
        float brightness = 0.30F + diffuse * 0.70F;
        int alpha = argb >>> 24;
        int red = Math.round(((argb >>> 16) & 0xFF) * brightness);
        int green = Math.round(((argb >>> 8) & 0xFF) * brightness);
        int blue = Math.round((argb & 0xFF) * brightness);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
