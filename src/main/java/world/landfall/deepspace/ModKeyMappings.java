package world.landfall.deepspace;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public class ModKeyMappings {
    @SubscribeEvent
    public static void registerBindings(RegisterKeyMappingsEvent event) {
        // The hyper-relay K key lives in VSIE (vsieKeyMappings.KEY_ACTIVATE_HYPER_RELAY).
    }
}
