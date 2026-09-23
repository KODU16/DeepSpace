package world.landfall.deepspace.command;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.render.PlanetRenderer;

import java.io.IOException;

/**
 * Exports the exact client-side planet textures and bindings for rendering bug reports.
 */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class PlanetTextureDebugCommand {
    private PlanetTextureDebugCommand() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("deepspaceclient")
                        .then(Commands.literal("texturedebug")
                                .executes(context -> dumpTextures()))
        );
    }

    private static int dumpTextures() {
        try {
            var output = PlanetRenderer.dumpGeneratedTextureDiagnostics();
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                net.minecraft.client.Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("Deep Space texture diagnostics: " + output.toAbsolutePath())
                );
            }
            return 1;
        } catch (IOException e) {
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                net.minecraft.client.Minecraft.getInstance().player.sendSystemMessage(
                        Component.literal("Failed to export Deep Space texture diagnostics: " + e.getMessage())
                );
            }
            return 0;
        }
    }
}
