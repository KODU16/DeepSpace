package world.landfall.deepspace;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import world.landfall.deepspace.item.JetHelmetItem;
import world.landfall.deepspace.physics.SpaceEnvironment;

@EventBusSubscriber(modid = Deepspace.MODID)
public final class OxygenPlayerEvents {
    private static final int CONSUMPTION_INTERVAL = 40;
    private static final int CONSUMPTION_PER_INTERVAL = 10;

    private OxygenPlayerEvents() {
    }

    @SubscribeEvent
    public static void playerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }

        float lastOxygenated = player.getData(ModAttatchments.LAST_OXYGENATED);
        player.setData(ModAttatchments.LAST_OXYGENATED, lastOxygenated + 0.05F);
        boolean vacuum = SpaceEnvironment.isVacuumDimension(player.level().dimension().location().toString());
        if (!vacuum) {
            return;
        }

        boolean inOxygenatedArea = lastOxygenated < 3.0F;
        if (player.isCreative() || inOxygenatedArea) {
            player.setAirSupply(Player.TOTAL_AIR_SUPPLY);
            return;
        }

        var helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        var oxygen = helmet.is(ModItems.JET_HELMET_ITEM)
                ? helmet.get(JetHelmetItem.JetHelmetComponent.SUPPLIER)
                : null;
        if (oxygen != null && (oxygen.infinite() || oxygen.amount() > 0)) {
            player.setAirSupply(oxygen.playerOxygen());
            if (!oxygen.infinite() && player.tickCount % CONSUMPTION_INTERVAL == 0) {
                // Consume the stored tagged fluid on the logical server so clients cannot desynchronize it.
                helmet.set(JetHelmetItem.JetHelmetComponent.SUPPLIER,
                        oxygen.withAmount(oxygen.amount() - CONSUMPTION_PER_INTERVAL));
            }
            return;
        }

        player.setAirSupply(0);
        if (player.tickCount % 10 == 0) {
            player.hurt(ModDamageTypes.noAirDamage(player), oxygen == null ? 2.0F : 1.0F);
        }
    }

    @SubscribeEvent
    public static void playerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            event.getEntity().setData(ModAttatchments.LAST_OXYGENATED, 0.0F);
        }
    }
}
