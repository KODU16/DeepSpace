package world.landfall.deepspace.command;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.client.SpaceLightingDebugOverlay;
import world.landfall.deepspace.render.SpaceSolarLighting;

/**
 * Provides client-only status for the BSL point-solar path.
 */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class SpaceLightingDebugCommand {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SpaceLightingDebugCommand() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("deepspace_light_debug")
                        .executes(context -> toggleOverlay())
                        .then(Commands.literal("status")
                                .executes(context -> dumpStatus()))
                        .then(Commands.literal("report")
                                .executes(context -> writeReport()))
                        .then(Commands.literal("trace")
                                .executes(context -> toggleTrace()))
                        .then(Commands.literal("overlay")
                                .executes(context -> toggleOverlay()))
        );
    }

    private static int dumpStatus() {
        var snapshot = SpaceSolarLighting.debugSnapshot(Minecraft.getInstance().level);
        var lines = snapshot.lines();
        lines.forEach(SpaceLightingDebugCommand::sendMessage);
        LOGGER.info("[DeepSpace Light Debug] {}", String.join(" | ", lines));
        return 1;
    }

    private static int writeReport() {
        long reportId = SpaceSolarLighting.logDiagnosticReport(Minecraft.getInstance().level);
        sendMessage("Deep Space BSL diagnostic report " + reportId + " written to logs/latest.log.");
        sendMessage("Send the [DEEPSPACE-BSL-DIAG] BEGIN/END block for this report.");
        return 1;
    }

    private static int toggleTrace() {
        boolean enabled = SpaceSolarLighting.toggleTrace();
        sendMessage("Deep Space BSL trace " + (enabled ? "ON" : "OFF") + " (one line every 5 seconds).");
        return 1;
    }

    private static int toggleOverlay() {
        boolean visible = SpaceLightingDebugOverlay.toggle();
        sendMessage("DeepSpace light debug overlay " + (visible ? "ON" : "OFF") + ".");
        return 1;
    }

    private static void sendMessage(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(message));
        }
    }
}
