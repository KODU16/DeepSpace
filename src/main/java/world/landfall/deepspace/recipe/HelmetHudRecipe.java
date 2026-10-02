package world.landfall.deepspace.recipe;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
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
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.ModRecipes;

public final class HelmetHudRecipe extends CustomRecipe {
    public static final TagKey<Item> HELMETS = TagKey.create(Registries.ITEM, Deepspace.path("helmets"));
    public static final String HUD_NBT_KEY = "deepspace_hud";

    public HelmetHudRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findHelmet(input, stack -> isHeadEquipment(stack, level)) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        // matches has already checked slot capability using the world/player context unavailable during assembly.
        ItemStack helmet = findHelmet(input, stack -> true);
        if (helmet == null) {
            return ItemStack.EMPTY;
        }
        // Copy the target first so enchantments, damage, names, and mod data survive crafting.
        ItemStack result = helmet.copyWithCount(1);
        CompoundTag data = result.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        data.putBoolean(HUD_NBT_KEY, true);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return result;
    }

    /** Resolve standard equipment slots and modded canEquip overrides instead of a closed helmet tag. */
    private static boolean isHeadEquipment(ItemStack stack, Level level) {
        EquipmentSlot slot = stack.getEquipmentSlot();
        if (slot == EquipmentSlot.HEAD) return true;
        Equipable equipable = Equipable.get(stack);
        if (slot == null && equipable != null && equipable.getEquipmentSlot() == EquipmentSlot.HEAD) return true;
        // Some modded hats expose only canEquip; use living players or a cached server crafting probe.
        if (level.players().stream().anyMatch(player -> stack.canEquip(EquipmentSlot.HEAD, player))) return true;
        return level instanceof ServerLevel serverLevel
                && stack.canEquip(EquipmentSlot.HEAD, FakePlayerFactory.getMinecraft(serverLevel));
    }

    private static ItemStack findHelmet(CraftingInput input, Predicate<ItemStack> eligible) {
        ItemStack source = ItemStack.EMPTY;
        ItemStack target = ItemStack.EMPTY;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ModItems.DEEPSPACE_HELMET) && source.isEmpty()) {
                source = stack;
            } else if (target.isEmpty() && !stack.is(ModItems.DEEPSPACE_HELMET) && eligible.test(stack)
                    && !stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                    .copyTag().getBoolean(HUD_NBT_KEY)) {
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
        return ModRecipes.HELMET_HUD.get();
    }
}
