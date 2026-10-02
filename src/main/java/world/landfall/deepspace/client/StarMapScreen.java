package world.landfall.deepspace.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.StarMapGraphLayout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Achievement-style draggable and zoomable view of the player's discovered wormhole graph. */
public final class StarMapScreen extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();
    private static final int VIEW_MARGIN = 16;
    private static final int HEADER_HEIGHT = 34;
    private static final double MIN_ZOOM = 0.35D;
    private static final double MAX_ZOOM = 3.0D;
    private static final double ZOOM_STEP = 1.15D;
    private static final int CURRENT_COLOR = 0xFF55FF55;
    private static final int EXPLORED_COLOR = 0xFFFFD76A;
    private static final int UNKNOWN_COLOR = 0xFFB0B0B0;
    private static final int EDGE_COLOR = 0xFFD0C7A7;
    private static final ResourceLocation BACKGROUND_TEXTURE = Deepspace.path("textures/gui/star_map_background.png");
    private static final int BACKGROUND_WIDTH = 1280;
    private static final int BACKGROUND_HEIGHT = 720;

    private final Set<ResourceLocation> explored;
    private final ResourceLocation currentGalaxy;
    private final long sessionId = SESSION_SEQUENCE.incrementAndGet();
    private StarMapGraphLayout.Layout layout;
    private double panX;
    private double panY;
    private double zoom = 1.0D;
    private boolean dragging;
    private double lastMouseX;
    private double lastMouseY;

    private StarMapScreen(Collection<ResourceLocation> explored, ResourceLocation currentGalaxy) {
        super(Component.translatable("gui.deepspace.star_map.title"));
        this.explored = new LinkedHashSet<>(explored);
        this.currentGalaxy = currentGalaxy;
    }

    public static void open(Collection<ResourceLocation> explored, ResourceLocation currentGalaxy) {
        Minecraft.getInstance().setScreen(new StarMapScreen(explored, currentGalaxy));
    }

    @Override
    protected void init() {
        if (layout == null) {
            layout = buildLayout();
            panX = 0.0D;
            panY = 0.0D;
        }
    }

    private StarMapGraphLayout.Layout buildLayout() {
        Map<String, StarMapGraphLayout.Node> nodes = new LinkedHashMap<>();
        List<StarMapGraphLayout.Portal> portals = new ArrayList<>();
        if (currentGalaxy != null) {
            explored.add(currentGalaxy);
        }

        for (ResourceLocation galaxyId : explored) {
            Galaxy galaxy = galaxy(galaxyId);
            if (galaxy == null) {
                continue;
            }
            String sourceId = galaxyId.toString();
            nodes.put(sourceId, new StarMapGraphLayout.Node(sourceId, galaxy.name(), true));
            Vec3 eclipticCenter = galaxy.sun().getCenter();
            for (Planet wormhole : PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension())) {
                if (!wormhole.isWormhole()) {
                    continue;
                }
                String targetId = wormhole.getDimension().location().toString();
                Galaxy targetGalaxy = galaxy(wormhole.getDimension().location());
                boolean targetExplored = explored.contains(wormhole.getDimension().location());
                String targetLabel = targetExplored && targetGalaxy != null ? targetGalaxy.name() : "?";
                nodes.putIfAbsent(targetId, new StarMapGraphLayout.Node(targetId, targetLabel, targetExplored));
                Vec3 offset = wormhole.getCenter().subtract(eclipticCenter);
                double angle = Math.atan2(offset.z, offset.x);
                portals.add(new StarMapGraphLayout.Portal(sourceId, targetId, angle));
            }
        }
        String root = currentGalaxy == null ? "" : currentGalaxy.toString();
        return StarMapGraphLayout.arrange(nodes.values(), portals, root);
    }

    private static Galaxy galaxy(ResourceLocation dimensionId) {
        return PlanetRegistry.getGalaxyByDimension(ResourceKey.create(Registries.DIMENSION, dimensionId));
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        // Screen.render applies the vanilla background blur, so it must run before the crisp map foreground.
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xFF17140F);
        drawFrame(graphics);
        drawSpaceBackground(graphics);
        graphics.enableScissor(VIEW_MARGIN, HEADER_HEIGHT, width - VIEW_MARGIN, height - VIEW_MARGIN);
        drawGrid(graphics);
        if (layout != null) {
            drawEdges(graphics);
            drawNodes(graphics);
        }
        graphics.disableScissor();

        graphics.fill(0, 0, width, HEADER_HEIGHT, 0xEE242018);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("gui.deepspace.star_map.hint"), VIEW_MARGIN + 4, 21, 0xFFAAA38F, false);
        DeepSpaceManualButton.render(this, graphics, mouseX, mouseY);
    }

    private void drawFrame(GuiGraphics graphics) {
        graphics.fill(VIEW_MARGIN - 2, HEADER_HEIGHT - 2, width - VIEW_MARGIN + 2, height - VIEW_MARGIN + 2, 0xFF8A7652);
        graphics.fill(VIEW_MARGIN, HEADER_HEIGHT, width - VIEW_MARGIN, height - VIEW_MARGIN, 0xFF29251C);
    }

    private void drawSpaceBackground(GuiGraphics graphics) {
        int viewportWidth = Math.max(1, width - VIEW_MARGIN * 2);
        int viewportHeight = Math.max(1, height - HEADER_HEIGHT - VIEW_MARGIN);
        double scale = Math.max(
                viewportWidth / (double) BACKGROUND_WIDTH,
                viewportHeight / (double) BACKGROUND_HEIGHT
        );
        int sourceWidth = Math.max(1, Math.min(BACKGROUND_WIDTH, (int) Math.round(viewportWidth / scale)));
        int sourceHeight = Math.max(1, Math.min(BACKGROUND_HEIGHT, (int) Math.round(viewportHeight / scale)));
        float sourceX = (BACKGROUND_WIDTH - sourceWidth) * 0.5F;
        float sourceY = (BACKGROUND_HEIGHT - sourceHeight) * 0.5F;
        // Cover-crop retains the nebula composition at any GUI aspect ratio without stretching it.
        graphics.blit(
                BACKGROUND_TEXTURE,
                VIEW_MARGIN,
                HEADER_HEIGHT,
                viewportWidth,
                viewportHeight,
                sourceX,
                sourceY,
                sourceWidth,
                sourceHeight,
                BACKGROUND_WIDTH,
                BACKGROUND_HEIGHT
        );
        graphics.fill(VIEW_MARGIN, HEADER_HEIGHT, width - VIEW_MARGIN, height - VIEW_MARGIN, 0x33030A14);
    }

    private void drawGrid(GuiGraphics graphics) {
        int spacing = Math.max(12, (int) Math.round(32.0D * zoom));
        int originX = Math.floorMod((int) Math.round(viewCenterX() + panX), spacing);
        int originY = Math.floorMod((int) Math.round(viewCenterY() + panY), spacing);
        for (int x = originX; x < width; x += spacing) {
            graphics.fill(x, HEADER_HEIGHT, x + 1, height - VIEW_MARGIN, 0x332F473B);
        }
        for (int y = HEADER_HEIGHT + originY; y < height - VIEW_MARGIN; y += spacing) {
            graphics.fill(VIEW_MARGIN, y, width - VIEW_MARGIN, y + 1, 0x332F473B);
        }
    }

    private void drawEdges(GuiGraphics graphics) {
        for (StarMapGraphLayout.Edge edge : layout.edges()) {
            StarMapGraphLayout.Point first = layout.positions().get(edge.firstId());
            StarMapGraphLayout.Point second = layout.positions().get(edge.secondId());
            if (first == null || second == null) {
                continue;
            }
            double x1 = screenX(first.x());
            double y1 = screenY(first.y());
            double x2 = screenX(second.x());
            double y2 = screenY(second.y());
            double length = Math.hypot(x2 - x1, y2 - y1);
            float angle = (float) Math.atan2(y2 - y1, x2 - x1);
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x1, y1, 0.0D);
            pose.mulPose(Axis.ZP.rotation(angle));
            graphics.fill(0, -1, Math.max(1, (int) Math.round(length)), 1, EDGE_COLOR);
            pose.popPose();
        }
    }

    private void drawNodes(GuiGraphics graphics) {
        for (Map.Entry<String, StarMapGraphLayout.Node> entry : layout.nodes().entrySet()) {
            StarMapGraphLayout.Point point = layout.positions().get(entry.getKey());
            if (point == null) {
                continue;
            }
            int x = (int) Math.round(screenX(point.x()));
            int y = (int) Math.round(screenY(point.y()));
            StarMapGraphLayout.Node node = entry.getValue();
            boolean current = currentGalaxy != null && entry.getKey().equals(currentGalaxy.toString());
            int labelColor = current ? CURRENT_COLOR : node.explored() ? EXPLORED_COLOR : UNKNOWN_COLOR;
            int haloRadius = Math.max(5, (int) Math.round(8.0D * Math.sqrt(zoom)));
            ResourceLocation nodeDimension = ResourceLocation.tryParse(entry.getKey());
            Galaxy nodeGalaxy = nodeDimension == null ? null : galaxy(nodeDimension);
            List<Integer> starColors = node.explored() && nodeGalaxy != null
                    ? nodeGalaxy.suns().stream().map(sun -> sun.getColor()).toList()
                    : List.of(UNKNOWN_COLOR & 0xFFFFFF);
            int spacing = Math.max(6, (int) Math.round(10.0D * Math.sqrt(zoom)));
            int groupHalfWidth = spacing * (starColors.size() - 1) / 2;
            Component label = node.explored() ? Component.literal(node.label()) : Component.literal("?");
            if (node.explored() && isNodeHovered(entry.getKey(), lastMouseX, lastMouseY)) {
                int halfWidth = Math.max(groupHalfWidth + haloRadius + 5, font.width(label) / 2 + 4);
                drawSelectionBox(graphics, x - halfWidth, y - haloRadius - 5,
                        x + halfWidth, y + haloRadius + font.lineHeight + 8);
            }
            if (current) {
                // A shared green halo retains the current-galaxy marker without replacing spectral star colors.
                drawDisc(graphics, x, y, haloRadius + groupHalfWidth + 3, withAlpha(CURRENT_COLOR, 24));
            }
            for (int index = 0; index < starColors.size(); index++) {
                int starX = x + index * spacing - groupHalfWidth;
                drawStarGlow(graphics, starX, y, haloRadius, starColors.get(index));
            }
            graphics.drawCenteredString(font, label, x, y + haloRadius + 5, labelColor);
        }
    }

    private void drawSelectionBox(GuiGraphics graphics, int left, int top, int right, int bottom) {
        int color = 0xFFE9D894;
        graphics.fill(left, top, right + 1, top + 1, color);
        graphics.fill(left, bottom, right + 1, bottom + 1, color);
        graphics.fill(left, top, left + 1, bottom + 1, color);
        graphics.fill(right, top, right + 1, bottom + 1, color);
        graphics.fill(left + 1, top + 1, right, bottom, 0x221D2740);
    }

    private boolean isNodeHovered(String nodeId, double mouseX, double mouseY) {
        if (layout == null || mouseY < HEADER_HEIGHT || mouseY > height - VIEW_MARGIN) {
            return false;
        }
        StarMapGraphLayout.Point point = layout.positions().get(nodeId);
        StarMapGraphLayout.Node node = layout.nodes().get(nodeId);
        if (point == null || node == null) {
            return false;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(nodeId);
        Galaxy nodeGalaxy = dimension == null ? null : galaxy(dimension);
        int stars = nodeGalaxy == null ? 1 : nodeGalaxy.suns().size();
        int spacing = Math.max(6, (int) Math.round(10.0D * Math.sqrt(zoom)));
        int haloRadius = Math.max(5, (int) Math.round(8.0D * Math.sqrt(zoom)));
        int halfWidth = Math.max(spacing * (stars - 1) / 2 + haloRadius + 7, font.width(node.label()) / 2 + 6);
        double x = screenX(point.x());
        double y = screenY(point.y());
        return mouseX >= x - halfWidth && mouseX <= x + halfWidth
                && mouseY >= y - haloRadius - 7 && mouseY <= y + haloRadius + font.lineHeight + 10;
    }

    private static void drawStarGlow(GuiGraphics graphics, int x, int y, int radius, int color) {
        // Alpha-blended concentric discs provide a vanilla-compatible glow without requiring Iris shaders.
        drawDisc(graphics, x, y, radius, withAlpha(color, 28));
        drawDisc(graphics, x, y, Math.max(3, radius * 3 / 4), withAlpha(color, 54));
        drawDisc(graphics, x, y, Math.max(2, radius / 2), withAlpha(color, 130));
        drawDisc(graphics, x, y, Math.max(1, radius / 4), 0xFF000000 | brighten(color, 170));
        graphics.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);
    }

    private static void drawDisc(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        int squaredRadius = radius * radius;
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int halfWidth = (int) Math.floor(Math.sqrt(squaredRadius - offsetY * offsetY));
            graphics.fill(
                    centerX - halfWidth,
                    centerY + offsetY,
                    centerX + halfWidth + 1,
                    centerY + offsetY + 1,
                    color
            );
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha & 0xFF) << 24 | color & 0xFFFFFF;
    }

    private static int brighten(int color, int amount) {
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        red += (255 - red) * amount / 255;
        green += (255 - green) * amount / 255;
        blue += (255 - blue) * amount / 255;
        return red << 16 | green << 8 | blue;
    }

    private double screenX(double graphX) {
        return viewCenterX() + panX + graphX * zoom;
    }

    private double screenY(double graphY) {
        return viewCenterY() + panY + graphY * zoom;
    }

    private double viewCenterX() {
        return width * 0.5D;
    }

    private double viewCenterY() {
        return (HEADER_HEIGHT + height - VIEW_MARGIN) * 0.5D;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && DeepSpaceManualButton.contains(this, mouseX, mouseY)) {
            minecraft.setScreen(new DeepSpaceManualScreen(this));
            return true;
        }
        if (button == 0 && mouseY >= HEADER_HEIGHT && mouseY <= height - VIEW_MARGIN) {
            for (Map.Entry<String, StarMapGraphLayout.Node> entry : layout.nodes().entrySet()) {
                if (!entry.getValue().explored() || !isNodeHovered(entry.getKey(), mouseX, mouseY)) {
                    continue;
                }
                ResourceLocation dimension = ResourceLocation.tryParse(entry.getKey());
                Galaxy selectedGalaxy = dimension == null ? null : galaxy(dimension);
                if (selectedGalaxy != null) {
                    minecraft.setScreen(new SolarSystemScreen(this, selectedGalaxy));
                    return true;
                }
            }
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double zoomBefore = zoom;
        boolean handled = applyScroll(mouseX, mouseY, scrollX, scrollY);
        LOGGER.info(
                "[DEEPSPACE-STAR-MAP] phase=SCROLL_INPUT session={} source=SCREEN deltaX={} deltaY={} "
                        + "zoomBefore={} zoomAfter={} handled={}",
                sessionId, scrollX, scrollY, zoomBefore, zoom, handled
        );
        return handled || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    boolean applyScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Some input mods report a wheel through the horizontal axis, so preserve it as a fallback.
        double scroll = scrollY != 0.0D ? scrollY : scrollX;
        if (scroll == 0.0D) {
            return false;
        }
        double previous = zoom;
        double requested = scroll > 0.0D ? zoom * ZOOM_STEP : zoom / ZOOM_STEP;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requested));
        double ratio = zoom / previous;
        // Keep the graph coordinate under the cursor fixed, matching the advancement screen zoom behavior.
        panX = mouseX - viewCenterX() - (mouseX - viewCenterX() - panX) * ratio;
        panY = mouseY - viewCenterY() - (mouseY - viewCenterY() - panY) * ratio;
        return true;
    }

    long sessionId() {
        return sessionId;
    }

    double zoomValue() {
        return zoom;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
