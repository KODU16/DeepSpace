package world.landfall.deepspace;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Consumer;

public final class ModFluids {
    public static final int OXYGEN_TINT = 0xFFD9F5FF;
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, Deepspace.MODID);
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(BuiltInRegistries.FLUID, Deepspace.MODID);

    public static final DeferredHolder<FluidType, FluidType> OXYGEN_TYPE = FLUID_TYPES.register(
            "oxygen",
            () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid.deepspace.oxygen")
                    .density(90)
                    .viscosity(150)
                    .temperature(90)
                    .canDrown(false)
                    .canSwim(false)
                    .supportsBoating(false)) {
                @Override
                @SuppressWarnings("removal") // NeoForge still invokes this deprecated client-extension hook.
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    // Reuse the animated water sprites and apply the oxygen-specific pale tint.
                    consumer.accept(new IClientFluidTypeExtensions() {
                        @Override
                        public int getTintColor() {
                            return OXYGEN_TINT;
                        }

                        @Override
                        public net.minecraft.resources.ResourceLocation getStillTexture() {
                            return ResourceLocation.withDefaultNamespace("block/water_still");
                        }

                        @Override
                        public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                            return ResourceLocation.withDefaultNamespace("block/water_flow");
                        }

                        @Override
                        public net.minecraft.resources.ResourceLocation getOverlayTexture() {
                            return ResourceLocation.withDefaultNamespace("block/water_overlay");
                        }
                    });
                }
            });

    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> OXYGEN = FLUIDS.register(
            "oxygen", () -> new BaseFlowingFluid.Source(properties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_OXYGEN = FLUIDS.register(
            "flowing_oxygen", () -> new BaseFlowingFluid.Flowing(properties()));

    private ModFluids() {
    }

    private static BaseFlowingFluid.Properties properties() {
        // Suppliers break the registration cycle between fluid, bucket, and liquid block.
        return new BaseFlowingFluid.Properties(OXYGEN_TYPE, OXYGEN, FLOWING_OXYGEN)
                .bucket(ModItems.OXYGEN_BUCKET)
                .block(ModBlocks.OXYGEN)
                .slopeFindDistance(2)
                .levelDecreasePerBlock(2)
                .tickRate(8)
                .explosionResistance(1.0F);
    }

    public static void register(IEventBus eventBus) {
        FLUID_TYPES.register(eventBus);
        FLUIDS.register(eventBus);
    }
}
