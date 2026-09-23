package world.landfall.deepspace.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.planet.Galaxy;
import world.landfall.deepspace.planet.ParadiseRating;
import world.landfall.deepspace.planet.Planet;
import world.landfall.deepspace.planet.PlanetTextureTier;
import world.landfall.deepspace.planet.PlanetBiomeType;
import world.landfall.deepspace.planet.PlanetRegistry;
import world.landfall.deepspace.planet.RingWorldDimensions;
import world.landfall.deepspace.planet.Sun;
import world.landfall.deepspace.render.CelestialGuiRenderer;
import world.landfall.deepspace.render.CelestialGuiViewMath;
import world.landfall.deepspace.render.RingWorldRenderer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Interactive projected 3D view of one synchronized galaxy. */
public final class SolarSystemScreen extends Screen {
    private static final int VIEW_MARGIN = 16;
    private static final int HEADER_HEIGHT = 34;
    private static final int MENU_WIDTH = 112;
    private static final int MENU_BUTTON_HEIGHT = 24;
    private static final int TABLE_HEADER_HEIGHT = 25;
    private static final int TABLE_ROW_HEIGHT = 44;
    private static final double MIN_ZOOM = 0.25D;
    private static final double MAX_ZOOM = 8.0D;
    private static final ResourceLocation BACKGROUND = Deepspace.path("textures/gui/star_map_background.png");
    private static final int BACKGROUND_WIDTH = 1280;
    private static final int BACKGROUND_HEIGHT = 720;
    private static final int PANEL_BACKGROUND = 0xB0182028;
    private static final int PANEL_BORDER = 0xB060BFFF;
    private static final int PANEL_TEXT = 0xFFF2F7FF;

    private final StarMapScreen parent;
    private final Galaxy galaxy;
    private final List<CelestialBody> bodies = new ArrayList<>();
    private List<Planet> planets = List.of();
    private List<Planet> ringEdges = List.of();
    private Vec3 origin;
    private double baseScale;
    private double sceneExtent;
    private double zoom = 1.0D;
    private double panX;
    private double panY;
    private double yaw = Math.toRadians(32.0D);
    private double pitch = Math.toRadians(-24.0D);
    private boolean panning;
    private boolean rotating;
    private Mode mode = Mode.STAR_SYSTEM;
    private int tableScroll;

    SolarSystemScreen(StarMapScreen parent, Galaxy galaxy) {
        super(Component.literal(galaxy.name()));
        this.parent = parent;
        this.galaxy = galaxy;
    }

    @Override
    protected void init() {
        // The galaxy view is mouse-driven even when it was opened from a previously captured game cursor.
        Minecraft.getInstance().mouseHandler.releaseMouse();
        bodies.clear();
        origin = galaxy.sun().getCenter();
        galaxy.suns().forEach(sun -> bodies.add(CelestialBody.star(sun, origin)));
        planets = PlanetRegistry.getPlanetsForGalaxy(galaxy.dimension()).stream()
                .filter(planet -> !planet.isWormhole())
                .sorted(Comparator.comparing(Planet::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        ringEdges = planets.stream().filter(Planet::isRingWorldEdge).toList();
        planets.stream()
                // Ring sections are drawn once as their shared GeckoLib structure below.
                .filter(planet -> !planet.isRingWorldEdge())
                .forEach(planet -> bodies.add(CelestialBody.planet(planet, origin)));
        sceneExtent = bodies.stream()
                .mapToDouble(body -> body.position.length() + body.radius)
                .max().orElse(1.0D);
        if (isRingWorld()) {
            sceneExtent = Math.max(sceneExtent, RingWorldDimensions.OUTER_RADIUS);
        }
        baseScale = Math.max(0.004D, Math.min(width, height - HEADER_HEIGHT) * 0.38D / sceneExtent);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, width, height, 0xFF090D17);
        drawSidebar(graphics, mouseX, mouseY);
        List<ProjectedBody> projected = List.of();
        if (mode == Mode.STAR_SYSTEM) {
            drawBackground(graphics);
            graphics.enableScissor(contentLeft(), HEADER_HEIGHT, width - VIEW_MARGIN, height - VIEW_MARGIN);
            drawOrbitGuides(graphics);
            projected = bodies.stream()
                    .map(body -> new ProjectedBody(body, project(body.position)))
                    // project() returns the signed camera-forward distance: larger means farther.
                    .sorted(Comparator.comparingDouble((ProjectedBody value) -> value.point.depth).reversed())
                    .toList();
            for (int index = 0; index < projected.size(); index++) {
                ProjectedBody body = projected.get(index);
                int radius = displayRadius(body.body);
                CelestialGuiViewMath.DepthGroup depthGroup =
                        CelestialGuiViewMath.depthGroup(index, projected.size(), radius);
                drawBody(graphics, body, depthGroup);
                drawBodyLabel(graphics, body, depthGroup.labelLayer());
                graphics.flush();
            }
            // Draw the ring after the bodies so depth writes can hide stars that belong behind it.
            drawRingWorld(graphics, projected);
            drawPlayer(graphics);
            graphics.disableScissor();
        } else {
            drawParadiseProbe(graphics);
        }

        graphics.fill(0, 0, width, HEADER_HEIGHT, 0xEE151923);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        if (mode == Mode.STAR_SYSTEM) {
            graphics.drawString(font, "左键拖拽平移 · 右键拖拽旋转 · 滚轮缩放 · ESC 返回星图",
                    contentLeft() + 4, 21, 0xFFAAAFC0, false);
        }
        drawFrame(graphics);
        if (mode == Mode.STAR_SYSTEM) {
            Planet hovered = findHoveredPlanet(projected, mouseX, mouseY);
            if (hovered != null) {
                drawPlanetInfo(graphics, hovered, mouseX, mouseY);
            }
        }
    }

    /** Keeps the current galaxy selected while switching between its visual map and planet ratings. */
    private void drawSidebar(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(VIEW_MARGIN, HEADER_HEIGHT, contentLeft() - 6, height - VIEW_MARGIN, 0xEE141A22);
        drawMenuButton(graphics, Mode.STAR_SYSTEM, HEADER_HEIGHT + 10, mouseX, mouseY);
        drawMenuButton(graphics, Mode.PARADISE_PROBE, HEADER_HEIGHT + 40, mouseX, mouseY);
    }

    private void drawMenuButton(GuiGraphics graphics, Mode buttonMode, int y, int mouseX, int mouseY) {
        int left = VIEW_MARGIN + 6;
        int right = contentLeft() - 12;
        boolean hovered = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + MENU_BUTTON_HEIGHT;
        int color = mode == buttonMode ? 0xFF465E76 : hovered ? 0xFF303D4A : 0xFF202A34;
        graphics.fill(left, y, right, y + MENU_BUTTON_HEIGHT, color);
        graphics.drawCenteredString(font, Component.translatable(buttonMode.translationKey),
                (left + right) / 2, y + 8, mode == buttonMode ? 0xFFA9E2FF : 0xFFD4DEE6);
    }

    /** Lists only the planets hosted by the galaxy currently open in this screen. */
    private void drawParadiseProbe(GuiGraphics graphics) {
        int left = contentLeft();
        int right = width - VIEW_MARGIN;
        int top = HEADER_HEIGHT;
        int nameX = left + 54;
        int gradeX = left + Math.max(170, (right - left) * 62 / 100);
        int scoreX = left + Math.max(245, (right - left) * 82 / 100);
        graphics.fill(left, top, right, height - VIEW_MARGIN, 0xEE11171C);
        graphics.fill(left, top, right, top + TABLE_HEADER_HEIGHT, 0xFF343D42);
        graphics.drawString(font, Component.translatable("gui.deepspace.paradise.planet"), nameX, top + 8, 0xFFFFFFFF, false);
        graphics.drawString(font, Component.translatable("gui.deepspace.paradise.grade"), gradeX, top + 8, 0xFFFFFFFF, false);
        graphics.drawString(font, Component.translatable("gui.deepspace.paradise.score"), scoreX, top + 8, 0xFFFFFFFF, false);

        int rowsTop = top + TABLE_HEADER_HEIGHT;
        graphics.enableScissor(left, rowsTop, right, height - VIEW_MARGIN);
        for (int index = 0; index < planets.size(); index++) {
            Planet planet = planets.get(index);
            int rowTop = rowsTop + index * TABLE_ROW_HEIGHT - tableScroll;
            if (rowTop + TABLE_ROW_HEIGHT <= rowsTop || rowTop >= height - VIEW_MARGIN) {
                continue;
            }
            graphics.fill(left, rowTop, right, rowTop + TABLE_ROW_HEIGHT,
                    index % 2 == 0 ? 0xCC1A242B : 0xCC202C33);
            int centerY = rowTop + TABLE_ROW_HEIGHT / 2;
            if (planet.isRingWorldEdge()) {
                graphics.drawCenteredString(font, Component.translatable("gui.deepspace.paradise.ring_world"),
                        left + 27, centerY - 4, 0xFFFFD783);
            } else {
                CelestialGuiViewMath.ProbeRotation probeRotation = CelestialGuiViewMath.paradiseProbeRotation();
                CelestialGuiRenderer.renderPlanet(
                        graphics, planet, left + 27, centerY, 14,
                        120.0F, 10.0F, new Quaternionf()
                                .rotateZ(probeRotation.roll())
                                .rotateX(probeRotation.pitch())
                                .rotateY(probeRotation.yaw())
                );
            }
            graphics.drawString(font, planet.getName(), nameX, centerY - 4, 0xFFEAF1F3, false);
            if ((planet.getTexture().isPresent() || planet.getGeneratedTextureTier() == PlanetTextureTier.FULL)
                    && planet.getSurfaceScanStatus() == Planet.SurfaceScanStatus.COMPLETE) {
                ParadiseRating.Result rating = ParadiseRating.evaluate(
                        planet, FluidTextureColorProbe.hasBlueOceanTexture(planet)
                );
                graphics.drawString(font, rating.grade().name(), gradeX, centerY - 4,
                        rating.grade().color(), false);
                graphics.drawString(font, Integer.toString(rating.score()), scoreX, centerY - 4,
                        0xFFFFFFFF, false);
            } else {
                Component unknown = Component.translatable("gui.deepspace.paradise.unknown");
                graphics.drawString(font, unknown, gradeX, centerY - 4, 0xFF9AA7B0, false);
                graphics.drawString(font, unknown, scoreX, centerY - 4, 0xFF9AA7B0, false);
            }
        }
        graphics.disableScissor();
    }

    private void drawBackground(GuiGraphics graphics) {
        int viewportWidth = Math.max(1, width - contentLeft() - VIEW_MARGIN);
        int viewportHeight = Math.max(1, height - HEADER_HEIGHT - VIEW_MARGIN);
        double scale = Math.max(viewportWidth / (double) BACKGROUND_WIDTH,
                viewportHeight / (double) BACKGROUND_HEIGHT);
        int sourceWidth = Math.max(1, Math.min(BACKGROUND_WIDTH, (int) Math.round(viewportWidth / scale)));
        int sourceHeight = Math.max(1, Math.min(BACKGROUND_HEIGHT, (int) Math.round(viewportHeight / scale)));
        graphics.blit(BACKGROUND, contentLeft(), HEADER_HEIGHT, viewportWidth, viewportHeight,
                (BACKGROUND_WIDTH - sourceWidth) * 0.5F, (BACKGROUND_HEIGHT - sourceHeight) * 0.5F,
                sourceWidth, sourceHeight, BACKGROUND_WIDTH, BACKGROUND_HEIGHT);
        graphics.fill(contentLeft(), HEADER_HEIGHT, width - VIEW_MARGIN, height - VIEW_MARGIN, 0x88010914);
    }

    private void drawFrame(GuiGraphics graphics) {
        int color = 0xFF6F7890;
        graphics.fill(contentLeft() - 2, HEADER_HEIGHT - 2, width - VIEW_MARGIN + 2, HEADER_HEIGHT, color);
        graphics.fill(contentLeft() - 2, height - VIEW_MARGIN, width - VIEW_MARGIN + 2, height - VIEW_MARGIN + 2, color);
        graphics.fill(contentLeft() - 2, HEADER_HEIGHT - 2, contentLeft(), height - VIEW_MARGIN + 2, color);
        graphics.fill(width - VIEW_MARGIN, HEADER_HEIGHT - 2, width - VIEW_MARGIN + 2, height - VIEW_MARGIN + 2, color);
    }

    private void drawOrbitGuides(GuiGraphics graphics) {
        for (CelestialBody body : bodies) {
            if (body.star) {
                continue;
            }
            double orbitRadius = Math.hypot(body.position.x, body.position.z);
            if (orbitRadius < 1.0D) {
                continue;
            }
            ProjectedPoint previous = project(new Vec3(orbitRadius, body.position.y, 0.0D));
            for (int segment = 1; segment <= 64; segment++) {
                double angle = Math.PI * 2.0D * segment / 64.0D;
                ProjectedPoint next = project(new Vec3(
                        Math.cos(angle) * orbitRadius,
                        body.position.y,
                        Math.sin(angle) * orbitRadius
                ));
                drawLine(graphics, previous.x, previous.y, next.x, next.y, 0x446C7893);
                previous = next;
            }
        }
    }

    /** Shares the primary star's depth origin so the star and all four ring sections occlude physically. */
    private void drawRingWorld(GuiGraphics graphics, List<ProjectedBody> projected) {
        if (!isRingWorld() || projected.isEmpty()) {
            return;
        }
        int primaryStarIndex = 0;
        for (int index = 0; index < projected.size(); index++) {
            if (projected.get(index).body.sun == galaxy.sun()) {
                primaryStarIndex = index;
                break;
            }
        }
        float ringRadius = (float) (RingWorldDimensions.OUTER_RADIUS * baseScale * zoom);
        CelestialGuiViewMath.DepthGroup depthGroup = CelestialGuiViewMath.depthGroup(
                primaryStarIndex,
                projected.size(),
                ringRadius
        );
        ProjectedPoint center = project(Vec3.ZERO);
        CelestialGuiViewMath.ViewRotation viewRotation = CelestialGuiViewMath.viewRotation(yaw, pitch);
        Quaternionf rotation = new Quaternionf()
                .rotateX(viewRotation.pitch())
                .rotateY(viewRotation.yaw());
        RingWorldRenderer.renderGuiRingWorld(
                graphics,
                galaxy,
                ringEdges,
                (int) Math.round(center.x),
                (int) Math.round(center.y),
                (float) (baseScale * zoom),
                depthGroup.modelLayer(),
                rotation
        );
        graphics.flush();
    }

    private boolean isRingWorld() {
        return !ringEdges.isEmpty() || galaxy.brokenRingSections() != 0;
    }

    private void drawBody(
            GuiGraphics graphics,
            ProjectedBody projected,
            CelestialGuiViewMath.DepthGroup depthGroup
    ) {
        CelestialBody body = projected.body;
        int radius = displayRadius(body);
        int x = (int) Math.round(projected.point.x);
        int y = (int) Math.round(projected.point.y);
        CelestialGuiViewMath.ViewRotation viewRotation = CelestialGuiViewMath.viewRotation(yaw, pitch);
        Quaternionf rotation = new Quaternionf()
                .rotateX(viewRotation.pitch())
                .rotateY(viewRotation.yaw());
        if (body.star) {
            CelestialGuiRenderer.renderSun(
                    graphics, body.sun, x, y, radius,
                    depthGroup.modelLayer(), depthGroup.modelHalfDepth(), rotation
            );
        } else {
            Vector3f starLight = new Vector3f(
                    (float) (galaxy.sun().getCenter().x - body.planet.getCenter().x),
                    (float) (galaxy.sun().getCenter().y - body.planet.getCenter().y),
                    (float) (galaxy.sun().getCenter().z - body.planet.getCenter().z)
            ).normalize();
            CelestialGuiRenderer.renderPlanet(
                    graphics, body.planet, x, y, radius,
                    depthGroup.modelLayer(), depthGroup.modelHalfDepth(), rotation, starLight
            );
        }
    }

    private void drawBodyLabel(GuiGraphics graphics, ProjectedBody projected, float labelLayer) {
        CelestialBody body = projected.body;
        int radius = displayRadius(body);
        int x = (int) Math.round(projected.point.x);
        int y = (int) Math.round(projected.point.y);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, labelLayer);
        graphics.drawCenteredString(font, body.name, x, y - radius - font.lineHeight - 4, 0xFFE8EAF0);
        pose.popPose();
    }

    private int displayRadius(CelestialBody body) {
        int radius = (int) Math.round(body.radius * baseScale * zoom);
        // Keep zoom proportional; only enforce a tiny readable minimum for distant bodies.
        return Math.max(radius, body.star ? 2 : 2);
    }

    private void drawPlayer(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !client.level.dimension().equals(galaxy.dimension())) {
            return;
        }
        ProjectedPoint point = project(client.player.position().subtract(origin));
        int x = (int) Math.round(point.x);
        int y = (int) Math.round(point.y);
        graphics.fill(x - 4, y - 1, x + 5, y + 2, 0xFF55FF88);
        graphics.fill(x - 1, y - 4, x + 2, y + 5, 0xFF55FF88);
        graphics.drawString(font, "玩家", x + 7, y - 4, 0xFF72FF9A, false);
    }

    /** Selects the nearest visible projected body, allowing a foreground star to block a rear planet. */
    private Planet findHoveredPlanet(List<ProjectedBody> projected, double mouseX, double mouseY) {
        if (mouseX < contentLeft() || mouseX > width - VIEW_MARGIN
                || mouseY < HEADER_HEIGHT || mouseY > height - VIEW_MARGIN) {
            return null;
        }
        List<HoverTarget> targets = new ArrayList<>();
        for (ProjectedBody projectedBody : projected) {
            int radius = displayRadius(projectedBody.body);
            double dx = mouseX - projectedBody.point.x;
            double dy = mouseY - projectedBody.point.y;
            if (dx * dx + dy * dy <= (radius + 3.0D) * (radius + 3.0D)) {
                targets.add(new HoverTarget(projectedBody.body.planet, projectedBody.point.depth));
            }
        }
        for (Planet ringEdge : ringEdges) {
            ProjectedBounds bounds = projectedBounds(ringEdge.getModelBounds());
            if (bounds.contains(mouseX, mouseY)) {
                targets.add(new HoverTarget(ringEdge, project(ringEdge.getCenter().subtract(origin)).depth));
            }
        }
        return targets.stream()
                // project() defines smaller signed forward depth as nearer to the viewer.
                .min(Comparator.comparingDouble(HoverTarget::depth))
                .map(HoverTarget::planet)
                .orElse(null);
    }

    /** Projects all eight model-box corners so rotated ring sections remain mouse-selectable. */
    private ProjectedBounds projectedBounds(AABB bounds) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{bounds.minX, bounds.maxX}) {
            for (double y : new double[]{bounds.minY, bounds.maxY}) {
                for (double z : new double[]{bounds.minZ, bounds.maxZ}) {
                    ProjectedPoint point = project(new Vec3(x, y, z).subtract(origin));
                    minX = Math.min(minX, point.x);
                    minY = Math.min(minY, point.y);
                    maxX = Math.max(maxX, point.x);
                    maxY = Math.max(maxY, point.y);
                }
            }
        }
        return new ProjectedBounds(minX, minY, maxX, maxY);
    }

    /** Mirrors the in-world DeepSpace HUD fields in a cursor-anchored pair of panels. */
    private void drawPlanetInfo(GuiGraphics graphics, Planet planet, int mouseX, int mouseY) {
        List<Component> identity = List.of(
                Component.literal(planet.getName()),
                planet.getDiscovererName().isBlank()
                        ? Component.translatable("gui.deepspace.planet_info.new_discovery")
                        : Component.translatable("gui.deepspace.planet_info.discoverer", planet.getDiscovererName())
        );
        List<Component> details = new ArrayList<>();
        details.add(Component.translatable(
                "gui.deepspace.planet_info.star_distance",
                String.format(Locale.ROOT, "%.1f", distanceToBounds(galaxy.sun().getCenter(), planet.getModelBounds()))
        ));
        details.add(planet.getPlanetTypeBiome().isBlank()
                ? Component.translatable("gui.deepspace.planet_info.unknown_planet")
                : Component.translatable(
                        "gui.deepspace.planet_info.planet_type",
                        translatedBiomeType(planet.getPlanetTypeBiome())
                ));
        if (planet.getSurfaceScanTotalChunks() > 0
                && planet.getSurfaceScanCompletedChunks() < planet.getSurfaceScanTotalChunks()) {
            int percent = Math.clamp(
                    Math.round(planet.getSurfaceScanCompletedChunks() * 100.0F
                            / planet.getSurfaceScanTotalChunks()), 0, 100);
            details.add(Component.translatable(
                    "gui.deepspace.planet_info.surface_scanning",
                    planet.getSurfaceScanCompletedChunks(), planet.getSurfaceScanTotalChunks(), percent
            ));
        } else if (planet.getSurfaceScanStatus() != Planet.SurfaceScanStatus.COMPLETE) {
            details.add(Component.translatable("gui.deepspace.planet_info.surface_unknown"));
        } else {
            planet.getSampledFluids().stream().limit(2)
                    .forEach(sample -> details.add(Component.literal("◆ ").append(translatedId("fluid", sample.id()))));
            planet.getSampledBlocks().stream().limit(5)
                    .forEach(sample -> details.add(Component.literal("◇ ").append(translatedId("block", sample.id()))));
        }

        int panelWidth = Math.max(panelWidth(identity), panelWidth(details));
        int totalHeight = panelHeight(identity) + 5 + panelHeight(details);
        int x = mouseX + 14;
        if (x + panelWidth > width - 6) {
            x = mouseX - panelWidth - 14;
        }
        int y = Math.clamp(mouseY + 12, HEADER_HEIGHT + 4, Math.max(HEADER_HEIGHT + 4, height - totalHeight - 6));
        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        // The star-map models occupy a deep 3D range, so the hover card needs its own foreground layer.
        pose.translate(0.0F, 0.0F, CelestialGuiViewMath.foregroundLayer());
        drawPanel(graphics, identity, x, y, panelWidth);
        drawPanel(graphics, details, x, y + panelHeight(identity) + 5, panelWidth);
        graphics.flush();
        pose.popPose();
    }

    private Component translatedId(String kind, String rawId) {
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

    /** Planet names always use one translated generic biome category. */
    private Component translatedBiomeType(String rawId) {
        return Component.translatable(
                "gui.deepspace.planet_info.biome_type." + PlanetBiomeType.classify(rawId)
        );
    }

    private static double distanceToBounds(Vec3 point, AABB bounds) {
        double dx = Math.max(Math.max(bounds.minX - point.x, 0.0D), point.x - bounds.maxX);
        double dy = Math.max(Math.max(bounds.minY - point.y, 0.0D), point.y - bounds.maxY);
        double dz = Math.max(Math.max(bounds.minZ - point.z, 0.0D), point.z - bounds.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private int panelWidth(List<Component> lines) {
        return lines.stream().mapToInt(font::width).max().orElse(0) + 12;
    }

    private static int panelHeight(List<Component> lines) {
        return lines.size() * 10 + 8;
    }

    private void drawPanel(GuiGraphics graphics, List<Component> lines, int x, int y, int panelWidth) {
        int height = panelHeight(lines);
        // Overlay rectangles never consult the celestial depth buffer.
        graphics.fill(RenderType.guiOverlay(), x, y, x + panelWidth, y + height, PANEL_BACKGROUND);
        graphics.fill(RenderType.guiOverlay(), x, y, x + panelWidth, y + 1, PANEL_BORDER);
        graphics.fill(RenderType.guiOverlay(), x, y + height - 1, x + panelWidth, y + height, PANEL_BORDER);
        graphics.fill(RenderType.guiOverlay(), x, y + 1, x + 1, y + height - 1, PANEL_BORDER);
        graphics.fill(RenderType.guiOverlay(), x + panelWidth - 1, y + 1, x + panelWidth, y + height - 1, PANEL_BORDER);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(font, lines.get(index), x + 6, y + 5 + index * 10, PANEL_TEXT, false);
        }
    }

    private ProjectedPoint project(Vec3 point) {
        double yawCos = Math.cos(yaw);
        double yawSin = Math.sin(yaw);
        double x = CelestialGuiViewMath.projectedHorizontal(point.x, point.z, yaw);
        double z = point.x * yawSin + point.z * yawCos;
        double pitchCos = Math.cos(pitch);
        double pitchSin = Math.sin(pitch);
        double y = point.y * pitchCos - z * pitchSin;
        double depth = CelestialGuiViewMath.projectedDepth(point.x, point.y, point.z, yaw, pitch);
        double scale = baseScale * zoom;
        return new ProjectedPoint(viewCenterX() + panX + x * scale,
                (HEADER_HEIGHT + height - VIEW_MARGIN) * 0.5D + panY - y * scale, depth);
    }

    private static void drawLine(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x1, y1, 0.0D);
        pose.mulPose(Axis.ZP.rotation((float) Math.atan2(y2 - y1, x2 - x1)));
        graphics.fill(0, 0, Math.max(1, (int) Math.ceil(length)), 1, color);
        pose.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && selectMode(mouseX, mouseY)) {
            return true;
        }
        if (mode != Mode.STAR_SYSTEM || mouseX < contentLeft()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (mouseY < HEADER_HEIGHT || mouseY > height - VIEW_MARGIN) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            panning = true;
            return true;
        }
        if (button == 1) {
            rotating = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (mode == Mode.STAR_SYSTEM && panning && button == 0) {
            panX += dragX;
            panY += dragY;
            return true;
        }
        if (mode == Mode.STAR_SYSTEM && rotating && button == 1) {
            yaw = CelestialGuiViewMath.yawAfterDrag(yaw, dragX);
            pitch = Math.clamp(
                    CelestialGuiViewMath.pitchAfterDrag(pitch, dragY),
                    -Math.PI * 0.49D,
                    Math.PI * 0.49D
            );
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && panning) {
            panning = false;
            return true;
        }
        if (button == 1 && rotating) {
            rotating = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double scroll = scrollY != 0.0D ? scrollY : scrollX;
        if (scroll == 0.0D) {
            return false;
        }
        if (mode == Mode.PARADISE_PROBE) {
            int contentHeight = planets.size() * TABLE_ROW_HEIGHT;
            int viewportHeight = Math.max(0, height - VIEW_MARGIN - HEADER_HEIGHT - TABLE_HEADER_HEIGHT);
            tableScroll = Math.clamp(tableScroll - (int) Math.round(scroll * TABLE_ROW_HEIGHT / 2.0),
                    0, Math.max(0, contentHeight - viewportHeight));
            return true;
        }
        double previous = zoom;
        zoom = Math.clamp(scroll > 0.0D ? zoom * 1.15D : zoom / 1.15D, MIN_ZOOM, MAX_ZOOM);
        double ratio = zoom / previous;
        panX = mouseX - viewCenterX() - (mouseX - viewCenterX() - panX) * ratio;
        double centerY = (HEADER_HEIGHT + height - VIEW_MARGIN) * 0.5D;
        panY = mouseY - centerY - (mouseY - centerY - panY) * ratio;
        return true;
    }

    private boolean selectMode(double mouseX, double mouseY) {
        int left = VIEW_MARGIN + 6;
        int right = contentLeft() - 12;
        if (mouseX < left || mouseX >= right) {
            return false;
        }
        if (mouseY >= HEADER_HEIGHT + 10 && mouseY < HEADER_HEIGHT + 10 + MENU_BUTTON_HEIGHT) {
            mode = Mode.STAR_SYSTEM;
        } else if (mouseY >= HEADER_HEIGHT + 40 && mouseY < HEADER_HEIGHT + 40 + MENU_BUTTON_HEIGHT) {
            mode = Mode.PARADISE_PROBE;
        } else {
            return false;
        }
        panning = false;
        rotating = false;
        return true;
    }

    private int contentLeft() {
        return VIEW_MARGIN + MENU_WIDTH;
    }

    private double viewCenterX() {
        return (contentLeft() + width - VIEW_MARGIN) * 0.5D;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ProjectedPoint(double x, double y, double depth) {
    }

    private enum Mode {
        STAR_SYSTEM("gui.deepspace.terminal.star_map"),
        PARADISE_PROBE("gui.deepspace.terminal.paradise_probe");

        private final String translationKey;

        Mode(String translationKey) {
            this.translationKey = translationKey;
        }
    }

    private record ProjectedBounds(double minX, double minY, double maxX, double maxY) {
        private boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private record HoverTarget(Planet planet, double depth) {
    }

    private record CelestialBody(
            String name,
            Vec3 position,
            double radius,
            boolean star,
            Planet planet,
            Sun sun
    ) {
        private static CelestialBody star(Sun sun, Vec3 origin) {
            return new CelestialBody(sun.getName(), sun.getCenter().subtract(origin),
                    sun.getModelRadius(), true, null, sun);
        }

        private static CelestialBody planet(Planet planet, Vec3 origin) {
            Vec3 size = planet.getBoundingBoxMax().subtract(planet.getBoundingBoxMin());
            double radius = Math.max(size.x, Math.max(size.y, size.z)) * 0.5D;
            return new CelestialBody(planet.getName(), planet.getCenter().subtract(origin),
                    radius, false, planet, null);
        }
    }

    private record ProjectedBody(CelestialBody body, ProjectedPoint point) {
    }
}
