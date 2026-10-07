package world.landfall.deepspace;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@SuppressWarnings("removal")
@EventBusSubscriber(modid = Deepspace.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModKeyMappings {
    // A DeepSpace binding works with both the standalone and optional VSIE helmet renderers.
    public static final KeyMapping TOGGLE_HELMET_HUD = new KeyMapping("key.deepspace.toggle_helmet_hud",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.deepspace");
    // The gravity-boot preference can be rebound independently of the helmet HUD.
    public static final KeyMapping TOGGLE_GRAVITY_BOOTS = new KeyMapping("key.deepspace.toggle_gravity_boots",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.deepspace");
    @SubscribeEvent
    public static void registerBindings(RegisterKeyMappingsEvent event) {
        // Key mappings belong on the mod event bus rather than the gameplay event bus.
        event.register(TOGGLE_HELMET_HUD);
        event.register(TOGGLE_GRAVITY_BOOTS);
    }
}
