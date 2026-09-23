package world.landfall.deepspace.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.network.GalaxyArrivalPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Renders the white wormhole flash and lower-left destination summary. */
@SuppressWarnings("removal") // NeoForge currently requires the deprecated MOD bus selector here.
@EventBusSubscriber(modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GalaxyArrivalOverlay {
    private static final int PANEL_MARGIN = 12;
    private static final int PANEL_PADDING = 8;
    private static final float HEADER_SCALE = 1.25F;

    private GalaxyArrivalOverlay() {
    }

    @SubscribeEvent
    public static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Deepspace.path("galaxy_arrival"), GalaxyArrivalOverlay::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        GalaxyArrivalState.Frame frame = GalaxyArrivalState.sample();
        if (frame.whiteAlpha() > 0.0F) {
            int alpha = Math.min(255, Math.max(0, Math.round(frame.whiteAlpha() * 255.0F)));
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24 | 0xFFFFFF);
            return;
        }
        if (frame.arrival() != null) {
            renderArrivalCard(graphics, frame.arrival());
        }
    }

    private static void renderArrivalCard(GuiGraphics graphics, GalaxyArrivalPacket arrival) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        Component header = Component.literal(arrival.galaxyName());
        List<Component> details = new ArrayList<>();
        if (!arrival.stars().isEmpty()) {
            // Keep binary and triple systems compact by placing every star on one space-separated line.
            String starLine = arrival.stars().stream()
                    .map(star -> star.name() + "/" + star.spectralSize())
                    .collect(Collectors.joining(" "));
            details.add(Component.literal(starLine));
        }
        details.add(Component.translatable("gui.deepspace.galaxy_arrival.planet_count", arrival.planetCount()));

        int lineHeight = font.lineHeight + 3;
        int headerHeight = Math.round(font.lineHeight * HEADER_SCALE) + 5;
        int contentWidth = details.stream().mapToInt(font::width).max().orElse(0);
        int headerWidth = Math.round(font.width(header) * HEADER_SCALE);
        int panelWidth = Math.max(headerWidth, contentWidth) + PANEL_PADDING * 2 + 3;
        int panelHeight = PANEL_PADDING * 2 + headerHeight + details.size() * lineHeight;
        int left = PANEL_MARGIN;
        int top = graphics.guiHeight() - PANEL_MARGIN - panelHeight;

        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xB0000000);
        graphics.fill(left, top, left + 2, top + panelHeight, 0xD8FFFFFF);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(left + PANEL_PADDING, top + PANEL_PADDING, 0.0F);
        pose.scale(HEADER_SCALE, HEADER_SCALE, 1.0F);
        graphics.drawString(font, header, 0, 0, 0xFFFFFF, true);
        pose.popPose();

        int textY = top + PANEL_PADDING + headerHeight;
        for (int index = 0; index < details.size(); index++) {
            graphics.drawString(
                    font,
                    details.get(index),
                    left + PANEL_PADDING,
                    textY + index * lineHeight,
                    0xF0F0F0,
                    true
            );
        }
    }
}
