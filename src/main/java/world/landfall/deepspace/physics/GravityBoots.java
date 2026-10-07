package world.landfall.deepspace.physics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.planet.GalaxyDimensions;

/** Restores native player gravity only over Sable structures in DeepSpace galaxies. */
public final class GravityBoots {
    public static final String ABILITY_NBT_KEY = "deepspace_gravity_boots";
    public static final String DISABLED_NBT_KEY = "deepspace_gravity_boots_disabled";
    private static final double REACH = 8.0D;

    private GravityBoots() { }

    public static boolean hasAbility(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(ModItems.GRAVITY_BOOTS)
                || stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(ABILITY_NBT_KEY));
    }

    public static boolean isEnabled(ItemStack stack) {
        return hasAbility(stack) && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                .copyTag().getBoolean(DISABLED_NBT_KEY);
    }

    /** Update only the switch flag, retaining enchantments, durability, and other mod data. */
    public static void setEnabled(ItemStack stack, boolean enabled) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(DISABLED_NBT_KEY, !enabled);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static boolean isGravityActive(Player player) {
        if (!GalaxyDimensions.isGalaxy(player.level().dimension())
                || !isEnabled(player.getItemBySlot(EquipmentSlot.FEET))) return false;
        SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) return false;
        // Riders may use plot-local coordinates; project feet into the structure bounds' world frame.
        Vec3 feet = Sable.HELPER.projectOutOfSubLevel(player.level(), player.position());
        double halfWidth = player.getBbWidth() * 0.5D;
        for (var subLevel : container.getAllSubLevels()) {
            var bounds = subLevel.boundingBox();
            // Compare the downward eight-block column beneath the player's feet, including exact endpoints.
            if (bounds.maxY() >= feet.y - REACH && bounds.minY() <= feet.y + 1.0E-3D
                    && bounds.maxX() >= feet.x - halfWidth && bounds.minX() <= feet.x + halfWidth
                    && bounds.maxZ() >= feet.z - halfWidth && bounds.minZ() <= feet.z + halfWidth) return true;
        }
        return false;
    }
}
