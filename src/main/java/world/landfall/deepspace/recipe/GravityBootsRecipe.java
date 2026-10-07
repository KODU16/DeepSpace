package world.landfall.deepspace.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import world.landfall.deepspace.physics.GravityBoots;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.ModRecipes;

/** Adds the gravity-boot ability to any equipment that accepts the feet slot. */
public final class GravityBootsRecipe extends CustomRecipe {

    public GravityBootsRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findBoots(input, stack -> isFootEquipment(stack, level)) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        // matches has already checked slot capability using the world/player context unavailable during assembly.
        ItemStack equipment = findBoots(input, stack -> true);
        if (equipment == null) {
            return ItemStack.EMPTY;
        }
        // Copy the target first so enchantments, damage, names, and mod data survive crafting.
        ItemStack result = equipment.copyWithCount(1);
        CompoundTag data = result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putBoolean(GravityBoots.ABILITY_NBT_KEY, true);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return result;
    }

    /** Resolve standard equipment slots and modded canEquip overrides instead of a closed foot equipment tag. */
    private static boolean isFootEquipment(ItemStack stack, Level level) {
        EquipmentSlot slot = stack.getEquipmentSlot();
        if (slot == EquipmentSlot.FEET) return true;
        Equipable equipable = Equipable.get(stack);
        if (slot == null && equipable != null && equipable.getEquipmentSlot() == EquipmentSlot.FEET) return true;
        // Some modded footwear expose only canEquip; use living players or a cached server crafting probe.
        if (level.players().stream().anyMatch(player -> stack.canEquip(EquipmentSlot.FEET, player))) return true;
        return level instanceof ServerLevel serverLevel
                && stack.canEquip(EquipmentSlot.FEET, FakePlayerFactory.getMinecraft(serverLevel));
    }

    private static ItemStack findBoots(CraftingInput input, Predicate<ItemStack> eligible) {
        ItemStack source = ItemStack.EMPTY;
        ItemStack target = ItemStack.EMPTY;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ModItems.GRAVITY_BOOTS) && source.isEmpty()) {
                source = stack;
            } else if (target.isEmpty() && !stack.is(ModItems.GRAVITY_BOOTS) && eligible.test(stack)
                    && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .copyTag().getBoolean(GravityBoots.ABILITY_NBT_KEY)) {
                target = stack;
            } else {
                return null;
            }
        }
        return source.isEmpty() || target.isEmpty() ? null : target;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.GRAVITY_BOOTS.get();
    }
}
