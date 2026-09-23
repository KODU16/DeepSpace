package world.landfall.deepspace.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import world.landfall.deepspace.integration.InfinityBiomeGenerationRules;
import world.landfall.deepspace.integration.InfinityGeneratorPolicy;

import java.util.List;
import java.util.Random;

/** Applies DeepSpace's planet composition while Infinity constructs its generator. */
@Pseudo
@Mixin(targets = {
        "net.lerariemann.infinity.dimensions.RandomDimension",
        "net.codexarchonic.infinity.dimensions.RandomDimension"
}, remap = false)
public abstract class MixinInfinityRandomDimension {
    @Shadow @Final public long numericId;
    @Shadow @Final public Random random;
    @Shadow public MinecraftServer server;
    @Shadow public List<Long> random_biome_ids;

    @Shadow
    abstract CompoundTag randomBiomeSource();

    @Shadow
    abstract String randomNoiseSettings();

    @Inject(method = "randomDimensionGenerator", at = @At("HEAD"), cancellable = true, require = 0)
    private void deepspace$createConstrainedGenerator(CallbackInfoReturnable<CompoundTag> callback) {
        InfinityBiomeGenerationRules.GenerationPlan plan = InfinityBiomeGenerationRules.activePlan();
        if (plan == null) {
            return;
        }
        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:noise");
        generator.put("biome_source", randomBiomeSource());
        generator.putString("settings", InfinityGeneratorPolicy.resolveNoiseSettings(
                plan.prescribedBiomes() != null,
                this::randomNoiseSettings
        ));
        generator.putLong("seed", numericId ^ server.overworld().getSeed());
        callback.setReturnValue(generator);
    }

    @Inject(method = "randomBiomeSource", at = @At("HEAD"), cancellable = true, require = 0)
    private void deepspace$createConstrainedBiomeSource(CallbackInfoReturnable<CompoundTag> callback) {
        InfinityBiomeGenerationRules.GenerationPlan plan = InfinityBiomeGenerationRules.activePlan();
        if (plan != null) {
            callback.setReturnValue(InfinityBiomeGenerationRules.createBiomeSource(plan, random, random_biome_ids));
        }
    }
}
