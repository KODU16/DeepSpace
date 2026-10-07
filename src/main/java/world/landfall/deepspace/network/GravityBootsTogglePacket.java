package world.landfall.deepspace.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import world.landfall.deepspace.Deepspace;
import world.landfall.deepspace.physics.GravityBoots;

/** Applies a boot-switch request to the sender's actual equipped footwear on the server. */
public record GravityBootsTogglePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<GravityBootsTogglePacket> TYPE = new Type<>(Deepspace.path("gravity_boots_toggle"));
    public static final StreamCodec<FriendlyByteBuf, GravityBootsTogglePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> buffer.writeBoolean(packet.enabled()),
            buffer -> new GravityBootsTogglePacket(buffer.readBoolean()));

    public static void handle(GravityBootsTogglePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var boots = player.getItemBySlot(EquipmentSlot.FEET);
            if (!GravityBoots.hasAbility(boots)) return;
            GravityBoots.setEnabled(boots, packet.enabled());
            // Inventory slot synchronization persists the authoritative switch on both sides.
            player.inventoryMenu.broadcastChanges();
            player.displayClientMessage(Component.translatable(packet.enabled()
                    ? "message.deepspace.gravity_boots.enabled" : "message.deepspace.gravity_boots.disabled"), true);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
