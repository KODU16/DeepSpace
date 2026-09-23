package world.landfall.deepspace.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.Sun;
import world.landfall.deepspace.render.shapes.Cube;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Reuses the world cube geometry and surface textures inside celestial GUI views. */
public final class CelestialGuiRenderer {
    private static final Cube UNIT_CUBE = new Cube(
            new Vector3f(-1.0F, -1.0F, -1.0F),
            new Vector3f(1.0F, 1.0F, 1.0F),
            1.0F,
            false
    );
    private static final Vector3f GUI_LIGHT_DIRECTION = new Vector3f(0.35F, -0.65F, 1.0F).normalize();
    private static final Vector3f[] FACE_NORMALS = {
            new Vector3f(0.0F, -1.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, -1.0F),
            new Vector3f(-1.0F, 0.0F, 0.0F),
            new Vector3f(0.0F, 0.0F, 1.0F),
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f(0.0F, 1.0F, 0.0F)
    };
    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    // GUI atmospheres are a lightweight shell pass, separate from the world-space atmosphere renderer.
    private static final RenderType GUI_ATMOSPHERE_TYPE = createGuiAtmosphereRenderType();
    private static final float GUI_ATMOSPHERE_SCALE = 1.10F;
    private static final int GUI_ATMOSPHERE_ALPHA = 84;
    private static final Map<ResourceLocation, RenderType> GUI_CELESTIAL_TYPES = new HashMap<>();

    private CelestialGuiRenderer() {
    }

    public static void renderPlanet(
            GuiGraphics graphics,
            Planet planet,
            int x,
            int y,
            int radius,
            float depthLayer,
            float depthRadius,
            Quaternionf rotation
    ) {
        renderPlanet(graphics, planet, x, y, radius, depthLayer, depthRadius, rotation, GUI_LIGHT_DIRECTION);
    }

    /** Renders a planet with a caller-provided star direction for system-map lighting. */
    public static void renderPlanet(
            GuiGraphics graphics,
            Planet planet,
            int x,
            int y,
            int radius,
            float depthLayer,
            float depthRadius,
            Quaternionf rotation,
            Vector3f lightDirection
    ) {
        renderCube(graphics, PlanetRenderer.getSurfaceTextures(planet), x, y, radius,
                depthLayer, depthRadius, rotation, 0xFFFFFFFF, false, lightDirection);
        renderAtmosphere(graphics, planet, x, y, radius, depthLayer, depthRadius, rotation, lightDirection);
    }

    public static void renderSun(
            GuiGraphics graphics,
            Sun sun,
            int x,
            int y,
            int radius,
            float depthLayer,
            float depthRadius,
            Quaternionf rotation
    ) {
        renderSun(graphics, sun, x, y, radius, depthLayer, depthRadius, rotation, GUI_LIGHT_DIRECTION);
    }

    public static void renderSun(
            GuiGraphics graphics,
            Sun sun,
            int x,
            int y,
            int radius,
            float depthLayer,
            float depthRadius,
            Quaternionf rotation,
            Vector3f lightDirection
    ) {
        renderCube(graphics, List.of(SunRenderer.getSurfaceTexture(sun)), x, y, radius,
                depthLayer, depthRadius, rotation, SunRenderer.getSurfaceVertexTint(sun), true, lightDirection);
    }

    private static void renderCube(
            GuiGraphics graphics,
            List<ResourceLocation> textures,
            int x,
            int y,
            int radius,
            float depthLayer,
            float depthRadius,
            Quaternionf rotation,
            int tint,
            boolean emissive,
            Vector3f lightDirection
    ) {
        if (textures.isEmpty()) {
            return;
        }
        graphics.flush();
        RenderSystem.enableDepthTest();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, depthLayer);
        pose.scale(radius, -radius, depthRadius);
        // Entity shaders multiply this global value into vertex color, so reset stale world-render tint.
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        for (int face = 0; face < 6; face++) {
            ResourceLocation texture = textures.size() == 1 ? textures.getFirst() : textures.get(face);
            // GUI lighting is calculated on the CPU so it remains stable without a world lightmap.
            int faceTint = emissive ? tint : shadeTint(tint, face, rotation, lightDirection);
            RenderType renderType = guiCelestialRenderType(texture);
            BufferBuilder builder = Tesselator.getInstance().begin(
                    VertexFormat.Mode.TRIANGLES,
                    DefaultVertexFormat.NEW_ENTITY
            );
            UNIT_CUBE.renderGuiFaceOutward(pose, builder, rotation, face, faceTint);
            renderType.draw(builder.buildOrThrow());
        }
        pose.popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
    }

    /** Draws a thin tinted shell so star-system and Paradise Probe previews show atmospheric planets. */
    private static void renderAtmosphere(
            GuiGraphics graphics,
            Planet planet,
            int x,
            int y,
            int radius,
            float depthLayer,
            float depthRadius,
            Quaternionf rotation,
            Vector3f lightDirection
    ) {
        if (!planet.hasAtmosphere()) {
            return;
        }
        int skyColor = planet.getSkyColor();
        if (skyColor == 0) {
            return;
        }
        graphics.flush();
        RenderSystem.enableDepthTest();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, depthLayer);
        pose.scale(radius * GUI_ATMOSPHERE_SCALE, -radius * GUI_ATMOSPHERE_SCALE,
                depthRadius * GUI_ATMOSPHERE_SCALE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        for (int face = 0; face < 6; face++) {
            BufferBuilder builder = Tesselator.getInstance().begin(
                    VertexFormat.Mode.TRIANGLES,
                    DefaultVertexFormat.NEW_ENTITY
            );
            UNIT_CUBE.renderGuiFaceOutward(pose, builder, rotation, face,
                    atmosphereTint(skyColor, face, rotation, lightDirection));
            GUI_ATMOSPHERE_TYPE.draw(builder.buildOrThrow());
        }
        pose.popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableDepthTest();
    }

    private static int shadeTint(int argb, int face, Quaternionf rotation, Vector3f lightDirection) {
        Vector3f normal = new Vector3f(FACE_NORMALS[face]).rotate(rotation).normalize();
        Vector3f rotatedLight = new Vector3f(lightDirection).rotate(rotation).normalize();
        float brightness = CelestialGuiViewMath.faceBrightness(normal.dot(rotatedLight));
        int alpha = argb >>> 24;
        int red = Math.round(((argb >>> 16) & 0xFF) * brightness);
        int green = Math.round(((argb >>> 8) & 0xFF) * brightness);
        int blue = Math.round((argb & 0xFF) * brightness);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int atmosphereTint(int rgb, int face, Quaternionf rotation, Vector3f lightDirection) {
        Vector3f normal = new Vector3f(FACE_NORMALS[face]).rotate(rotation).normalize();
        Vector3f rotatedLight = new Vector3f(lightDirection).rotate(rotation).normalize();
        float brightness = 0.82F + 0.18F * Math.clamp(normal.dot(rotatedLight), 0.0F, 1.0F);
        int red = Math.clamp(Math.round(((rgb >>> 16) & 0xFF) * brightness), 0, 255);
        int green = Math.clamp(Math.round(((rgb >>> 8) & 0xFF) * brightness), 0, 255);
        int blue = Math.clamp(Math.round((rgb & 0xFF) * brightness), 0, 255);
        return GUI_ATMOSPHERE_ALPHA << 24 | red << 16 | green << 8 | blue;
    }

    private static RenderType guiCelestialRenderType(ResourceLocation texture) {
        return GUI_CELESTIAL_TYPES.computeIfAbsent(texture, key -> {
            // The entity cutout path binds dynamic textures consistently in vanilla and Iris GUI rendering.
            RenderType.CompositeState state = RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_CUTOUT_SHADER)
                    .setTextureState(new RenderType.TextureStateShard(key, false, false))
                    .setTransparencyState(CelestialGuiRenderPolicy.blendSurfaceFaces()
                            ? RenderType.TRANSLUCENT_TRANSPARENCY
                            : RenderType.NO_TRANSPARENCY)
                    .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                    .setCullState(CelestialGuiRenderPolicy.cullSurfaceFaces()
                            ? RenderType.CULL
                            : RenderType.NO_CULL)
                    .setWriteMaskState(CelestialGuiRenderPolicy.writeSurfaceDepth()
                            ? RenderType.COLOR_DEPTH_WRITE
                            : RenderType.COLOR_WRITE)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .createCompositeState(true);
            return RenderType.create(
                    "deepspace_gui_celestial",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES,
                    256,
                    true,
                    false,
                    state
            );
        });
    }

    /** Uses the main GUI target and color-only writes so the shell cannot disturb model depth ordering. */
    private static RenderType createGuiAtmosphereRenderType() {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                .setTextureState(new RenderType.TextureStateShard(WHITE_TEXTURE, false, false))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(RenderType.LEQUAL_DEPTH_TEST)
                .setCullState(RenderType.NO_CULL)
                .setWriteMaskState(RenderType.COLOR_WRITE)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setOutputState(RenderStateShard.MAIN_TARGET)
                .createCompositeState(true);
        return RenderType.create(
                "deepspace_gui_atmosphere",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.TRIANGLES,
                256,
                true,
                false,
                state
        );
    }

    static void clearRenderTypes() {
        // Generated texture locations change after a planet data refresh.
        GUI_CELESTIAL_TYPES.clear();
    }
}
