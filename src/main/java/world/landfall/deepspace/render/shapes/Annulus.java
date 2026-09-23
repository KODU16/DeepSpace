package world.landfall.deepspace.render.shapes;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import foundry.veil.api.client.color.Color;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring mesh that preserves the original shader's 0.4-0.5 UV annulus.
 */
public class Annulus implements DeepSpaceRenderable {
    private static final int SEGMENTS = 64;
    private static final float INNER_RATIO = 0.8f;

    private final List<Triangle> triangles = new ArrayList<>(SEGMENTS * 2);
    private final Vector3f center;

    public Annulus(Vector3f center, float scale, Quaternionf rotation) {
        this.center = new Vector3f(center);
        Vector3f normal = new Vector3f(0, 1, 0).rotate(rotation);

        for (int segment = 0; segment < SEGMENTS; segment++) {
            float angle0 = (float) (Math.PI * 2.0 * segment / SEGMENTS);
            float angle1 = (float) (Math.PI * 2.0 * (segment + 1) / SEGMENTS);

            Vector3f outer0 = point(center, scale, angle0, rotation);
            Vector3f outer1 = point(center, scale, angle1, rotation);
            Vector3f inner0 = point(center, scale * INNER_RATIO, angle0, rotation);
            Vector3f inner1 = point(center, scale * INNER_RATIO, angle1, rotation);

            Vector2f outerUv0 = uv(0.5f, angle0);
            Vector2f outerUv1 = uv(0.5f, angle1);
            Vector2f innerUv0 = uv(0.4f, angle0);
            Vector2f innerUv1 = uv(0.4f, angle1);

            triangles.add(triangle(outer0, inner0, outer1, outerUv0, innerUv0, outerUv1, normal));
            triangles.add(triangle(outer1, inner0, inner1, outerUv1, innerUv0, innerUv1, normal));
        }
    }

    private static Vector3f point(Vector3f center, float radius, float angle, Quaternionf rotation) {
        return new Vector3f((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius, 0)
                .rotate(rotation)
                .add(center);
    }

    private static Vector2f uv(float radius, float angle) {
        return new Vector2f(
                0.5f + (float) Math.cos(angle) * radius,
                0.5f + (float) Math.sin(angle) * radius
        );
    }

    private static Triangle triangle(
            Vector3f a,
            Vector3f b,
            Vector3f c,
            Vector2f uvA,
            Vector2f uvB,
            Vector2f uvC,
            Vector3f normal
    ) {
        return new Triangle(
                new Vector3f[]{a, b, c},
                new Vector2f[]{uvA, uvB, uvC},
                new Vector3f[]{new Vector3f(normal), new Vector3f(normal), new Vector3f(normal)}
        );
    }

    @Override
    public void render(PoseStack stack, VertexConsumer consumer, Vector3fc dimensions, Quaternionf rotation) {
        renderTinted(stack, consumer, dimensions, rotation, Color.WHITE.argb());
    }

    /**
     * Stores tint and alpha in each vertex so Iris shader programs preserve ring transparency.
     */
    public void renderTinted(
            PoseStack stack,
            VertexConsumer consumer,
            Vector3fc dimensions,
            Quaternionf rotation,
            int argb
    ) {
        for (Triangle triangle : triangles) {
            for (int i = 0; i < 3; i++) {
                Vector3f vertex = new Vector3f(triangle.vertexes[i]).rotate(rotation);
                Vector2f uv = triangle.UV[i];
                Vector3f normal = triangle.normals[i];
                consumer.addVertex(
                        vertex.x + dimensions.x(),
                        vertex.y + dimensions.y(),
                        vertex.z + dimensions.z(),
                        argb,
                        uv.x,
                        uv.y,
                        0,
                        255,
                        normal.x,
                        normal.y,
                        normal.z
                );
            }
        }
    }
}
