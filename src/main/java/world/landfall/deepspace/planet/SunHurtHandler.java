package world.landfall.deepspace.planet;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.damagesource.DamageTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import world.landfall.deepspace.Deepspace;

@EventBusSubscriber(modid = Deepspace.MODID)
public class SunHurtHandler {

    @SubscribeEvent
    public static void serverPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().getServer() == null) return;
        var player = event.getEntity();
        var galaxy = PlanetRegistry.getGalaxyByDimension(player.level().dimension());
        var dimension = player.level().dimension().location();
        if (galaxy == null) return;
        boolean touching = galaxy.suns().stream().anyMatch(sun -> sun.isPlayerTouching(player));
        boolean inHeatRadius = galaxy.suns().stream()
                .anyMatch(sun -> player.position().distanceTo(sun.getCenter()) <= sun.getHurtRadius());
        if (touching && GalaxyDimensions.isGalaxy(dimension)) {
            player.hurt(player.damageSources().inFire(),Float.MAX_VALUE);
        }
        if (inHeatRadius && GalaxyDimensions.isGalaxy(dimension)) {
            player.setRemainingFireTicks(20);
        }
    }
}
