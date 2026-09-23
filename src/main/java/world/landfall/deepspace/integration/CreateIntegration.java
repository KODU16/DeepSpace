package world.landfall.deepspace.integration;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessing;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingTypeRegistry;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.foundation.data.CreateRegistrate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.item.JetHelmetItem;

import java.util.List;

public class CreateIntegration {
    private static final int FAN_FILL_AMOUNT = 10;

    private static void fillHelmet(ItemStack stack) {
        var component = stack.get(JetHelmetItem.JetHelmetComponent.SUPPLIER);
        if (component == null || component.infinite() || component.amount() >= component.capacity()) {
            return;
        }
        // A Create air current condenses ambient air into DeepSpace's canonical oxygen fluid.
        stack.set(JetHelmetItem.JetHelmetComponent.SUPPLIER,
                component.withAmount(component.amount() + FAN_FILL_AMOUNT));
    }

    public static void handleAir(List<Entity> entities, List<Pair<TransportedItemStackHandlerBehaviour, FanProcessingType>> handlers) {
        for (var x : handlers) {
            var behavior = x.getLeft().blockEntity.getBehaviour(DepotBehaviour.TYPE);
            if (behavior == null) continue;
            var stack = behavior.itemHandler.getStackInSlot(0);
            if (!x.getLeft().getWorld().isClientSide() && stack.is(ModItems.JET_HELMET_ITEM)
                    && x.getLeft().getWorld().getGameTime() % 4 == 0) {
                fillHelmet(stack);
            }
        }
        for (var x : entities) {
            if (x instanceof ItemEntity itemEntity) {
                if (itemEntity.getItem().is(ModItems.JET_HELMET_ITEM.get())) {
                    if (itemEntity.level().isClientSide() || itemEntity.tickCount % 4 != 0) continue;
                    var item = itemEntity.getItem();
                    fillHelmet(item);
                }
            }
        }
    }
    public static void register(IEventBus eventBus) {
        

        //FanProcessingTypeRegistry.init();
    }
    public static class AerateType implements FanProcessingType {

        @Override
        public boolean isValidAt(Level level, BlockPos pos) {
            return true;
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public boolean canProcess(ItemStack stack, Level level) {
            return stack.is(ModItems.JET_HELMET_ITEM.asItem()) && stack.has(JetHelmetItem.JetHelmetComponent.SUPPLIER);
        }

        @Override
        public @Nullable List<ItemStack> process(ItemStack stack, Level level) {
            if (!stack.has(JetHelmetItem.JetHelmetComponent.SUPPLIER))
                return List.of();
            fillHelmet(stack);
            return List.of(
                stack
            );
        }

        @Override
        public void spawnProcessingParticles(Level level, Vec3 pos) {

        }

        @Override
        public void morphAirFlow(AirFlowParticleAccess particleAccess, RandomSource random) {

        }

        @Override
        public void affectEntity(Entity entity, Level level) {

        }
    }
}
