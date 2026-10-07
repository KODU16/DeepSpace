package world.landfall.deepspace.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.ModItems;
import world.landfall.deepspace.ModKeyMappings;
import world.landfall.deepspace.network.GravityBootsTogglePacket;
import world.landfall.deepspace.physics.GravityBoots;

/** Predicts the K toggle locally while the server stores the authoritative equipment NBT. */
@EventBusSubscriber(modid = Deepspace.MODID, value = Dist.CLIENT)
public final class GravityBootsControls {
    private GravityBootsControls() { }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        while (ModKeyMappings.TOGGLE_GRAVITY_BOOTS.consumeClick()) {
            if (client.screen != null || client.player == null) continue;
            var boots = client.player.getItemBySlot(EquipmentSlot.FEET);
            if (!GravityBoots.hasAbility(boots)) continue;
            boolean enabled = !GravityBoots.isEnabled(boots);
            GravityBoots.setEnabled(boots, enabled);
            PacketDistributor.sendToServer(new GravityBootsTogglePacket(enabled));
        }
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        // Upgraded modded footwear advertises the added ability without replacing its own tooltip.
        if (!event.getItemStack().is(ModItems.GRAVITY_BOOTS) && GravityBoots.hasAbility(event.getItemStack())) {
            event.getToolTip().add(Component.translatable("item.deepspace.gravity_boots.tooltip").withStyle(ChatFormatting.GRAY));
        }
    }
}
