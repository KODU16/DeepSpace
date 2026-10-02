package world.landfall.deepspace.client.hud;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.recipe.HelmetHudRecipe;

/** Checks whether the equipped helmet carries DeepSpace's planet HUD. */
public final class HelmetHudState {
    // This preference is independent of which HUD renderer is installed or which helmet is equipped.
    private static boolean enabled = true;

    private HelmetHudState() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static void toggle() {
        enabled = !enabled;
    }

    static void reset() {
        enabled = true;
    }

    public static boolean hasHudHelmet(Player player) {
        if (player == null) {
            return false;
        }
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        return !helmet.isEmpty() && (Deepspace.path("deepspace_helmet")
                .equals(BuiltInRegistries.ITEM.getKey(helmet.getItem()))
                // Equipment already in HEAD is eligible regardless of its item class or tags.
                || helmet.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getBoolean(HelmetHudRecipe.HUD_NBT_KEY));
    }
}
