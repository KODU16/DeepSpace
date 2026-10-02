package world.landfall.deepspace.client.hud;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModKeyMappings;

/** Consumes the helmet HUD key once per press without modifying equipment or server state. */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class HelmetHudControls {
    private HelmetHudControls() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        while (ModKeyMappings.TOGGLE_HELMET_HUD.consumeClick()) {
            // Menu key presses must not toggle a HUD later when the screen closes.
            if (client.screen == null && HelmetHudState.hasHudHelmet(client.player)) HelmetHudState.toggle();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // A new world starts with the helmet's documented default of showing the HUD.
        HelmetHudState.reset();
    }
}
