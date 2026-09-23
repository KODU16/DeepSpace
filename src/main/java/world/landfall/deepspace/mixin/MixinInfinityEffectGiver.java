package world.landfall.deepspace.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import world.landfall.deepspace.planet.PlanetRegistry;

/** Keeps Infinity's generated dimension effect particle-free without adding a player tick scanner. */
@Pseudo
@Mixin(targets = {
        "net.lerariemann.infinity.options.EffectGiver",
        "net.codexarchonic.infinity.options.EffectGiver"
}, remap = false)
public abstract class MixinInfinityEffectGiver {
    @Redirect(
            method = "tryGiveEffect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z",
                    remap = false
            ),
            remap = false
    )
    private boolean deepspace$addParticleFreeEffect(ServerPlayer player, MobEffectInstance generated) {
        if (PlanetRegistry.getPlanetByDimension(player.level().dimension()) == null) {
            return player.addEffect(generated);
        }
        MobEffectInstance particleFree = new MobEffectInstance(
                generated.getEffect(),
                generated.getDuration(),
                generated.getAmplifier(),
                generated.isAmbient(),
                false,
                true
        );
        return player.addEffect(particleFree);
    }
}
