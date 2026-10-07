package world.landfall.deepspace.client;

import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import world.landfall.deepspace.physics.GravityBoots;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModAttatchments;
import world.landfall.deepspace.physics.EntityGravityRegistry;

public class SpacePlayerEvents {
    @EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
    public static class Tick {
        // Gravity is resolved per calculation by MixinEntityGravity; never cache it in NoGravity.
        @SubscribeEvent
        public static void fallEvent(LivingFallEvent event) {
            if (event.getEntity() instanceof Player player) {
                var dimension = player.level().dimension().location();
                var zeroGravity = EntityGravityRegistry.isZeroGravityDimension(dimension.toString());
                // Active gravity boots restore ordinary falling behavior as well as acceleration.
                event.setDistance(zeroGravity && !GravityBoots.isGravityActive(player)
                        ? 0f : event.getDistance());
            }
        }

        @SubscribeEvent
        public static void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            event.getEntity().setData(ModAttatchments.LAST_OXYGENATED, 0f);
        }
    }
}
