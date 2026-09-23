package world.landfall.deepspace.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.render.CelestialRenderDiagnostics;
import world.landfall.deepspace.render.SpaceSolarLighting;

import java.util.List;

/**
 * Displays the live BSL point-solar state without requiring an external debugger.
 */
@SuppressWarnings("removal") // NeoForge currently requires the deprecated MOD bus selector here.
@EventBusSubscriber(modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SpaceLightingDebugOverlay {
    private static final long SNAPSHOT_INTERVAL_NANOS = 250_000_000L;
    private static boolean visible;
    private static long lastSnapshotNanos;
    private static List<String> cachedLines = List.of();

    private SpaceLightingDebugOverlay() {
    }

    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Deepspace.path("space_light_debug"), SpaceLightingDebugOverlay::render);
    }

    /**
     * Toggles the diagnostic panel and returns its new state.
     */
    public static boolean toggle() {
        visible = !visible;
        return visible;
    }

    public static boolean isVisible() {
        return visible;
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!visible) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        if (cachedLines.isEmpty() || now - lastSnapshotNanos >= SNAPSHOT_INTERVAL_NANOS) {
            // Four updates per second keep the panel useful without polling render resources every frame.
            cachedLines = new java.util.ArrayList<>(SpaceSolarLighting.debugSnapshot(minecraft.level).lines());
            cachedLines.addAll(CelestialRenderDiagnostics.overlayLines());
            lastSnapshotNanos = now;
        }
        List<String> lines = cachedLines;
        int lineHeight = minecraft.font.lineHeight + 2;
        int width = lines.stream().mapToInt(minecraft.font::width).max().orElse(180);
        int panelHeight = lines.size() * lineHeight + 8;
        graphics.fill(4, 4, width + 12, panelHeight, 0xC0101010);

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int color = colorFor(index, line);
            graphics.drawString(minecraft.font, line, 8, 8 + index * lineHeight, color, true);
        }
    }

    private static int colorFor(int index, String line) {
        if (index == 0) {
            return 0xFFFF55;
        }
        if (line.contains("assessment=BSL_POINT_SOLAR_ACTIVE")) {
            return 0x55FF55;
        }
        if (line.contains("error=") || line.startsWith("assessment=")) {
            return 0xFF7777;
        }
        return 0xFFFFFF;
    }
}
