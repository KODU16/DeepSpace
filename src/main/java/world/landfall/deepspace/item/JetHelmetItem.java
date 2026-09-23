package world.landfall.deepspace.item;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.NotNull;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModArmorMaterials;
import world.landfall.deepspace.ModFluidTags;
import world.landfall.deepspace.ModFluids;

import java.awt.Color;
import java.util.List;
import java.util.function.Supplier;

public class JetHelmetItem extends ArmorItem {
    public static final int OXYGEN_CAPACITY = 1000;

    public JetHelmetItem() {
        super(ModArmorMaterials.JET_ARMOR_MATERIAL, Type.HELMET, new Properties()
                .durability(Integer.MAX_VALUE)
                .component(JetHelmetComponent.SUPPLIER, JetHelmetComponent.full())
                .component(DataComponents.RARITY, Rarity.EPIC)
                .component(DataComponents.CUSTOM_DATA, CustomData.of(createModTag())));
    }

    private static CompoundTag createModTag() {
        var createTag = new CompoundTag();
        var data = new CompoundTag();
        data.put("Processing", StringTag.valueOf("BLASTING"));
        createTag.put("CreateData", data);
        return createTag;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var oxygen = stack.get(JetHelmetComponent.SUPPLIER);
        if (oxygen == null) {
            return;
        }
        if (oxygen.infinite()) {
            tooltip.add(Component.translatable("item.deepspace.jet_helmet.tooltip")
                    .append(Component.literal("Infinite").setStyle(Style.EMPTY.withColor(0x00FFFF))));
            return;
        }
        tooltip.add(Component.translatable("item.deepspace.jet_helmet.tooltip")
                .append(Component.literal(oxygen.amount() + "/" + oxygen.capacity() + " mB")));
    }

    @Override
    public @NotNull EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    @Override
    public boolean isDamaged(ItemStack stack) {
        return false;
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    @Override
    public boolean canBeHurtBy(ItemStack stack, DamageSource source) {
        return false;
    }

    @Override
    public Component getName(ItemStack stack) {
        var component = stack.get(JetHelmetComponent.SUPPLIER);
        return component == null || !component.infinite()
                ? Component.translatable("item.deepspace.jet_helmet")
                : Component.translatable("item.deepspace.jet_helmet.creative");
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        var component = stack.get(JetHelmetComponent.SUPPLIER);
        return component != null && !component.infinite();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        var component = stack.get(JetHelmetComponent.SUPPLIER);
        return component == null ? 0 : Math.round(component.fillRatio() * 13.0F);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        var component = stack.get(JetHelmetComponent.SUPPLIER);
        return component != null && component.fillRatio() > 0.33F ? Color.WHITE.getRGB() : Color.RED.getRGB();
    }

    public record JetHelmetComponent(FluidStack oxygen, int capacity) {
        private record Legacy(int currentOxygen, int maxOxygen) {
        }

        private static final Codec<JetHelmetComponent> FLUID_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                FluidStack.OPTIONAL_CODEC.fieldOf("oxygen").forGetter(JetHelmetComponent::oxygen),
                Codec.INT.fieldOf("capacity").forGetter(JetHelmetComponent::capacity)
        ).apply(instance, JetHelmetComponent::new));
        private static final Codec<Legacy> LEGACY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("current_oxygen").forGetter(Legacy::currentOxygen),
                Codec.INT.fieldOf("max_oxygen").forGetter(Legacy::maxOxygen)
        ).apply(instance, Legacy::new));

        // The alternative codec upgrades old integer oxygen saves to 10 mB per legacy unit.
        public static final Codec<JetHelmetComponent> CODEC = Codec.either(FLUID_CODEC, LEGACY_CODEC).xmap(
                value -> value.map(component -> component, legacy -> legacy.maxOxygen() < 0
                        ? creative()
                        : ofAmount(legacy.currentOxygen() * 10, legacy.maxOxygen() * 10)),
                Either::left);
        public static final StreamCodec<RegistryFriendlyByteBuf, JetHelmetComponent> STREAM_CODEC = StreamCodec.composite(
                FluidStack.OPTIONAL_STREAM_CODEC, JetHelmetComponent::oxygen,
                ByteBufCodecs.INT, JetHelmetComponent::capacity,
                JetHelmetComponent::new);
        public static final DeferredRegister.DataComponents REGISTRAR =
                DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Deepspace.MODID);
        public static final Supplier<DataComponentType<JetHelmetComponent>> SUPPLIER = REGISTRAR.registerComponentType(
                "jet_helmet",
                builder -> builder.persistent(CODEC).networkSynchronized(STREAM_CODEC));

        public JetHelmetComponent {
            oxygen = oxygen.copy();
            if (capacity >= 0 && oxygen.getAmount() > capacity) {
                oxygen.setAmount(capacity);
            }
        }

        public static JetHelmetComponent full() {
            return ofAmount(OXYGEN_CAPACITY, OXYGEN_CAPACITY);
        }

        public static JetHelmetComponent creative() {
            return new JetHelmetComponent(FluidStack.EMPTY, -1);
        }

        public static JetHelmetComponent ofAmount(int amount, int capacity) {
            return new JetHelmetComponent(
                    amount <= 0 ? FluidStack.EMPTY : new FluidStack(ModFluids.OXYGEN.get(), Math.min(amount, capacity)),
                    capacity);
        }

        public static void register(IEventBus eventBus) {
            REGISTRAR.register(eventBus);
        }

        public boolean infinite() {
            return capacity < 0;
        }

        public int amount() {
            return infinite() ? Integer.MAX_VALUE : oxygen.getAmount();
        }

        public float fillRatio() {
            return infinite() ? 1.0F : Math.clamp((float) amount() / Math.max(1, capacity), 0.0F, 1.0F);
        }

        public int playerOxygen() {
            return Math.round(fillRatio() * Player.TOTAL_AIR_SUPPLY);
        }

        public JetHelmetComponent withAmount(int amount) {
            if (infinite()) {
                return this;
            }
            FluidStack copy = oxygen.isEmpty()
                    ? new FluidStack(ModFluids.OXYGEN.get(), Math.clamp(amount, 0, capacity))
                    : oxygen.copyWithAmount(Math.clamp(amount, 0, capacity));
            return new JetHelmetComponent(copy, capacity);
        }
    }

    public static final class OxygenFluidHandler implements IFluidHandlerItem {
        private final ItemStack container;

        public OxygenFluidHandler(ItemStack container) {
            this.container = container;
        }

        private JetHelmetComponent component() {
            return container.getOrDefault(JetHelmetComponent.SUPPLIER, JetHelmetComponent.ofAmount(0, OXYGEN_CAPACITY));
        }

        @Override
        public ItemStack getContainer() {
            return container;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return tank == 0 ? component().oxygen().copy() : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            var component = component();
            return tank == 0 && !component.infinite() ? component.capacity() : 0;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0 && stack.is(ModFluidTags.OXYGEN);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            var component = component();
            if (component.infinite() || !isFluidValid(0, resource)) {
                return 0;
            }
            int accepted = Math.min(resource.getAmount(), component.capacity() - component.amount());
            if (accepted > 0 && action.execute()) {
                FluidStack stored = component.oxygen().isEmpty()
                        ? resource.copyWithAmount(accepted)
                        : component.oxygen().copyWithAmount(component.amount() + accepted);
                container.set(JetHelmetComponent.SUPPLIER, new JetHelmetComponent(stored, component.capacity()));
            }
            return accepted;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            var component = component();
            if (component.infinite() || resource.isEmpty() || component.oxygen().isEmpty()
                    || !resource.is(ModFluidTags.OXYGEN)) {
                return FluidStack.EMPTY;
            }
            return drain(Math.min(resource.getAmount(), component.amount()), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            var component = component();
            if (component.infinite() || maxDrain <= 0 || component.oxygen().isEmpty()) {
                return FluidStack.EMPTY;
            }
            int drained = Math.min(maxDrain, component.amount());
            FluidStack result = component.oxygen().copyWithAmount(drained);
            if (action.execute()) {
                container.set(JetHelmetComponent.SUPPLIER, component.withAmount(component.amount() - drained));
            }
            return result;
        }
    }
}
