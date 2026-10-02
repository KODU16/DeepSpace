package world.landfall.deepspace.client.hud;

import world.landfall.deepspace.Deepspace;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders the helmet's planet information when VSIE is absent. */
@EventBusSubscriber(value = Dist.CLIENT, modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.GAME)
@SuppressWarnings("removal")
public final class DeepSpaceHudRenderer {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation TARGET_FRAME =
            Deepspace.path("textures/hud/target_frame.png");
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    private static final int PANEL_BACKGROUND = 0xB0182028;
    private static final int PANEL_BORDER = 0xB060BFFF;
    private static final int TEXT_COLOR = 0xFFF2F7FF;
    private static final double AIM_DISTANCE = 10_000_000.0D;

    private DeepSpaceHudRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER
                || !canRender() || MC.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = MC.renderBuffers().bufferSource();
        beginSeeThroughRender();
        try {
            for (DeepSpaceHudBridge.Body body : DeepSpaceHudBridge.bodies(MC.level.dimension())) {
                if (body.hyperRelay()) {
                    renderRelay(event.getPoseStack(), buffers, camera, body);
                }
            }
            buffers.endBatch();
        } finally {
            endSeeThroughRender();
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiLayerEvent.Post event) {
        if (!VanillaGuiLayers.HOTBAR.equals(event.getName()) || !canRender() || MC.level == null) {
            return;
        }
        DeepSpaceHudBridge.Body aimed = findAimedPlanet(
                DeepSpaceHudBridge.bodies(MC.level.dimension()),
                MC.gameRenderer.getMainCamera().getPosition(),
                cameraLookVector()
        );
        if (aimed != null) {
            renderPlanetPanels(event.getGuiGraphics(), aimed, MC.gameRenderer.getMainCamera().getPosition());
        }
    }

    private static boolean canRender() {
        // VSIE owns the paired HUD, so this renderer is the standalone helmet fallback.
        return !MC.options.hideGui && !ModList.get().isLoaded("vsie")
                && HelmetHudState.isEnabled() && HelmetHudState.hasHudHelmet(MC.player);
    }

    /** Uses the actual model bounds, so a large nearby planet wins over a farther center point. */
    static DeepSpaceHudBridge.Body findAimedPlanet(List<DeepSpaceHudBridge.Body> bodies, Vec3 origin, Vec3 direction) {
        List<DeepSpaceHudMath.Candidate<DeepSpaceHudBridge.Body>> candidates = bodies.stream()
                .map(body -> new DeepSpaceHudMath.Candidate<>(body, bounds(body.bounds()), body.hyperRelay()))
                .toList();
        return DeepSpaceHudMath.findAimed(candidates, point(origin), point(direction), AIM_DISTANCE);
    }

    private static Vec3 cameraLookVector() {
        Vector3f look = MC.gameRenderer.getMainCamera().getLookVector();
        return new Vec3(look.x, look.y, look.z);
    }

    private static void renderRelay(PoseStack pose, MultiBufferSource buffer, Vec3 camera,
                                    DeepSpaceHudBridge.Body relay) {
        EntityRenderDispatcher dispatcher = MC.getEntityRenderDispatcher();
        double relaySize = Math.max(relay.bounds().getXsize(),
                Math.max(relay.bounds().getYsize(), relay.bounds().getZsize()));
        renderIcon(camera, relay.center(), relaySize, dispatcher, pose);
        renderText(camera, relay.center(), dispatcher, pose, buffer,
                Component.literal(relay.name()).withStyle(ChatFormatting.AQUA), relaySize);
    }

    private static void renderIcon(Vec3 camera, Vec3 target, double relaySize,
                                   EntityRenderDispatcher dispatcher, PoseStack pose) {
        Vec3 offset = safeRenderOffset(camera, target);
        // Match the relay model's far-plane scale so the frame stays on the structure.
        float scale = projectedRelaySize(camera, target, relaySize, offset) * 1.1F;
        pose.pushPose();
        try {
            pose.translate(offset.x, offset.y, offset.z);
            pose.mulPose(dispatcher.cameraOrientation());
            pose.scale(scale, scale, scale);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, TARGET_FRAME);
            BufferBuilder vertices = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX
            );
            Matrix4f matrix = pose.last().pose();
            vertices.addVertex(matrix, -0.5F, -0.5F, 0.0F).setUv(0.0F, 1.0F);
            vertices.addVertex(matrix, 0.5F, -0.5F, 0.0F).setUv(1.0F, 1.0F);
            vertices.addVertex(matrix, 0.5F, 0.5F, 0.0F).setUv(1.0F, 0.0F);
            vertices.addVertex(matrix, -0.5F, 0.5F, 0.0F).setUv(0.0F, 0.0F);
            BufferUploader.drawWithShader(vertices.buildOrThrow());
        } finally {
            pose.popPose();
        }
    }

    private static void renderText(Vec3 camera, Vec3 target, EntityRenderDispatcher dispatcher,
                                   PoseStack pose, MultiBufferSource buffer, Component text, double relaySize) {
        Vec3 offset = safeRenderOffset(camera, target);
        float scale = 0.003F * (float) Math.max(1.0D, offset.length());
        // Place the caption just below the projected model, regardless of clipping distance.
        float yOffset = projectedRelaySize(camera, target, relaySize, offset) * 0.6F / scale;
        pose.pushPose();
        try {
            pose.translate(offset.x, offset.y, offset.z);
            Quaternionf orientation = dispatcher.cameraOrientation();
            pose.mulPose(orientation);
            pose.scale(scale, -scale, -scale);
            pose.translate(0.0F, yOffset, 0.0F);
            MC.font.drawInBatch(text, -MC.font.width(text) / 2.0F, 0.0F, TEXT_COLOR, false,
                    pose.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
        } finally {
            pose.popPose();
        }
    }

    /** Keeps world-space markers inside the active far plane while preserving their apparent size. */
    private static Vec3 safeRenderOffset(Vec3 camera, Vec3 target) {
        Vec3 offset = target.subtract(camera);
        double safeDistance = Math.max(16.0D, MC.gameRenderer.getDepthFar() * 0.45D);
        double distance = offset.length();
        return distance > safeDistance ? offset.scale(safeDistance / distance) : offset;
    }

    private static float projectedRelaySize(Vec3 camera, Vec3 target, double relaySize, Vec3 offset) {
        return (float) (relaySize * offset.length() / Math.max(1.0D, camera.distanceTo(target)));
    }

    private static void renderPlanetPanels(GuiGraphics graphics, DeepSpaceHudBridge.Body planet, Vec3 camera) {
        List<Component> first = List.of(
                Component.literal(planet.name()),
                planet.discoverer().isBlank()
                        ? Component.translatable("gui.deepspace.hud.new_discovery")
                        : Component.translatable("gui.deepspace.hud.discoverer", planet.discoverer())
        );
        List<Component> second = new ArrayList<>();
        second.add(Component.translatable("gui.deepspace.hud.distance",
                String.format(Locale.ROOT, "%.1f", distanceToBounds(camera, planet.bounds()))));
        second.add(planet.planetType().isBlank()
                ? Component.translatable("gui.deepspace.hud.unknown_planet")
                : Component.translatable("gui.deepspace.planet_info.planet_type",
                        Component.translatable("gui.deepspace.planet_info.biome_type." + planet.planetType())));
        if (!planet.scanComplete() && planet.scanTotalChunks() > 0) {
            int percent = Math.max(0, Math.min(100,
                    Math.round(planet.scanCompletedChunks() * 100.0F / planet.scanTotalChunks())));
            second.add(Component.translatable("gui.deepspace.hud.surface_scanning",
                    planet.scanCompletedChunks(), planet.scanTotalChunks(), percent));
        }
        planet.fluids().forEach(id -> second.add(Component.literal("◆ ").append(translatedId("fluid", id))));
        planet.blocks().forEach(id -> second.add(Component.literal("◇ ").append(translatedId("block", id))));

        int x = graphics.guiWidth() / 2 + 24;
        int y = Math.max(12, graphics.guiHeight() / 2 - 62);
        drawPanel(graphics, first, x, y);
        drawPanel(graphics, second, x, y + panelHeight(first) + 5);
    }

    private static Component translatedId(String kind, String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            return Component.literal(rawId);
        }
        String key = kind + "." + id.getNamespace() + "." + id.getPath();
        Component translated = Component.translatable(key);
        return translated.getString().equals(key)
                ? Component.literal(id.getPath().replace('_', ' '))
                : translated;
    }

    private static double distanceToBounds(Vec3 point, AABB bounds) {
        return DeepSpaceHudMath.distanceToBounds(point(point), bounds(bounds));
    }

    private static DeepSpaceHudMath.Point point(Vec3 value) {
        return new DeepSpaceHudMath.Point(value.x, value.y, value.z);
    }

    private static DeepSpaceHudMath.Bounds bounds(AABB value) {
        return new DeepSpaceHudMath.Bounds(
                value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ
        );
    }

    private static void drawPanel(GuiGraphics graphics, List<Component> lines, int x, int y) {
        int width = lines.stream().mapToInt(MC.font::width).max().orElse(0) + 12;
        int height = panelHeight(lines);
        graphics.fill(x, y, x + width, y + height, PANEL_BACKGROUND);
        graphics.renderOutline(x, y, width, height, PANEL_BORDER);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(MC.font, lines.get(index), x + 6, y + 5 + index * 10, TEXT_COLOR, false);
        }
    }

    private static int panelHeight(List<Component> lines) {
        return 10 * lines.size() + 8;
    }

    private static void beginSeeThroughRender() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    private static void endSeeThroughRender() {
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
