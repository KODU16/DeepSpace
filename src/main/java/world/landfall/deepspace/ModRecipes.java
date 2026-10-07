package world.landfall.deepspace;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import world.landfall.deepspace.recipe.HelmetHudRecipe;
import world.landfall.deepspace.recipe.GravityBootsRecipe;

import java.util.function.Supplier;

public final class ModRecipes {
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Deepspace.MODID);
    // A custom recipe retains all components of the helmet receiving the HUD.
    public static final Supplier<RecipeSerializer<HelmetHudRecipe>> HELMET_HUD =
            SERIALIZERS.register("helmet_hud", () -> new SimpleCraftingRecipeSerializer<>(HelmetHudRecipe::new));

    // Upgrade arbitrary foot equipment while preserving its original components.
    public static final Supplier<RecipeSerializer<GravityBootsRecipe>> GRAVITY_BOOTS =
            SERIALIZERS.register("gravity_boots_upgrade", () -> new SimpleCraftingRecipeSerializer<>(GravityBootsRecipe::new));

    private ModRecipes() {
    }

    public static void register(IEventBus bus) {
        SERIALIZERS.register(bus);
    }
}
