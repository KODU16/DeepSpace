package world.landfall.deepspace.client;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterDimensionTransitionScreenEvent;
import world.landfall.deepspace.Deepspace;

@SuppressWarnings("removal") // NeoForge currently requires the deprecated MOD bus selector here.
@EventBusSubscriber(modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SeamlessTransitionClientRegistration {
    private static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, Deepspace.path("space"));

    private SeamlessTransitionClientRegistration() {
    }

    @SubscribeEvent
    public static void registerTransitionScreens(RegisterDimensionTransitionScreenEvent event) {
        // Incoming and outgoing rules cover every planet paired with Deep Space.
        event.registerIncomingEffect(SPACE, SeamlessReceivingLevelScreen::new);
        event.registerOutgoingEffect(SPACE, SeamlessReceivingLevelScreen::new);
    }
}
